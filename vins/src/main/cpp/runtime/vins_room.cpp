// The room scan, fitted: see vins_room.h for what this is for and what it is not.
//
// Everything here runs on the estimator's own thread, once per solved frame while a scan is open and
// then once more when it closes. The fits are RANSAC over the points the solver already produced, so
// there is no new vision, no new frame and no OpenCV: a plane through three points and a count of
// how many agree with it is the whole of it.
#include "runtime/vins_room.h"

#include <algorithm>
#include <cmath>
#include <unordered_map>

// bionic's <cmath> has it; a host compiler told to be strict may not, and this file is the one place
// in the module that is also built off the phone (tools/room_scan_test.cpp).
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace vins {
namespace {

/** How level a plane has to be to count as a floor: 25 degrees off vertical. */
constexpr double kFloorNormalZ = 0.9063;  // cos(25 deg)
/** How upright a plane has to be to count as a wall: 25 degrees off horizontal. */
constexpr double kWallNormalZ = 0.4226;  // sin(25 deg)
/** Inlier slack for a plane fit, metres. A phone sees a floor to a few centimetres. */
constexpr double kPlaneEpsilon = 0.06;
/** Random proposals a RANSAC fit gets. Points come in hundreds; a plane is not a needle. */
constexpr int kRansacRounds = 120;
/** How near the camera a floor must be *below* it to be a floor and not a table edge. */
constexpr double kMinDropBelow = 0.35;
/** The largest angular gap two walls may leave between them before they are one wall. */
constexpr double kWallMergeAngle = 0.26;  // rad, 15 degrees
/** ...and the largest gap in their distance, metres. */
constexpr double kWallMergeDistance = 0.3;
/** Inliers before a plane is a wall rather than a smear of outliers. */
constexpr int kMinWallPoints = 40;
/** How many walls one scan will name. A room has four; anything past that is noise finding a shape. */
constexpr int kMaxWalls = 4;
/** Where the quantiles fall: 0.5, 0.8, 0.9, 0.95 of the points, nearest first. */
constexpr double kQuantileFractions[ROOM_QUANTILES] = {0.5, 0.8, 0.9, 0.95};

/** [v] with its z dropped and the rest made unit length: a wall only has a heading. */
Eigen::Vector3d headingOf(const Eigen::Vector3d &v) {
    Eigen::Vector3d h(v.x(), v.y(), 0.0);
    const double n = h.norm();
    return n > 1e-9 ? Eigen::Vector3d(h.x() / n, h.y() / n, 0.0) : Eigen::Vector3d(1.0, 0.0, 0.0);
}

/** The bin of a full turn [angle] falls in, for the wall histogram. */
int binOf(double angle) {
    int bin = static_cast<int>(std::floor(angle / (2 * M_PI) * ROOM_WALL_BINS)) % ROOM_WALL_BINS;
    if (bin < 0) bin += ROOM_WALL_BINS;
    return bin;
}

/** A spread small enough that [a] and [b] are the same angle on a circle. */
double wrapAngle(double a) {
    while (a > M_PI) a -= 2 * M_PI;
    while (a < -M_PI) a += 2 * M_PI;
    return a;
}

/**
 * The least-squares plane through a set of points, as `normal.dot(p) + height = 0`, with the normal
 * turned to point up. This is the refit half of a RANSAC fit: propose, count, then fit properly to
 * everything that agreed, which is what takes a hundred noisy sightings down to a few millimetres.
 */
bool fitPlane(const std::vector<Eigen::Vector3d> &points, Eigen::Vector3d *normal, double *height) {
    if (points.size() < 3) return false;
    Eigen::Vector3d centroid = Eigen::Vector3d::Zero();
    for (const auto &p : points) centroid += p;
    centroid /= static_cast<double>(points.size());
    Eigen::Matrix3d covariance = Eigen::Matrix3d::Zero();
    for (const auto &p : points) {
        const Eigen::Vector3d d = p - centroid;
        covariance += d * d.transpose();
    }
    covariance /= static_cast<double>(points.size());
    // The plane's normal is the direction the points are thinnest in: the smallest eigenvalue.
    Eigen::SelfAdjointEigenSolver<Eigen::Matrix3d> solver(covariance);
    if (solver.info() != Eigen::Success) return false;
    Eigen::Vector3d n = solver.eigenvectors().col(0);
    if (n.z() < 0) n = -n;
    if (n.norm() < 1e-9) return false;
    n.normalize();
    *normal = n;
    *height = -n.dot(centroid);
    return true;
}

/** How many of [points] are within [epsilon] of the plane, and which ones. */
int countInliers(const std::vector<Eigen::Vector3d> &points, const Eigen::Vector3d &normal, double height,
                 double epsilon, std::vector<int> *which) {
    int count = 0;
    for (int i = 0; i < static_cast<int>(points.size()); i++) {
        if (std::fabs(normal.dot(points[i]) + height) <= epsilon) {
            count++;
            if (which != nullptr) which->push_back(i);
        }
    }
    return count;
}

}  // namespace

void RoomScanner::reset() {
    state_ = ScanState::IDLE;
    points_.clear();
    ids_.clear();
    slots_.clear();
    result_ = RoomScan();
    start_ = -1;
    now_ = 0;
    cameraCentre_ = Eigen::Vector3d::Zero();
    cameraFrames_ = 0;
}

void RoomScanner::beginScan(double windowSeconds) {
    reset();
    state_ = ScanState::SCANNING;
    window_ = windowSeconds > 0 ? windowSeconds : 4.0;
    // [start_] is left unset: the window is measured from the first frame's stamp (see [elapsed]).
}

void RoomScanner::addFrame(const std::vector<int> &ids, const std::vector<Eigen::Vector3d> &worldPoints,
                          const Eigen::Vector3d &cameraPosition, double stampSeconds) {
    if (state_ != ScanState::SCANNING) return;
    now_ = stampSeconds;
    if (start_ < 0) start_ = stampSeconds;
    // The running mean of where the camera was, not the sum divided by the count again every frame:
    // [dropMedian] measures the floor against this, so an average that walks toward zero would put
    // the floor at the wrong depth under the head.
    cameraFrames_++;
    cameraCentre_ += (cameraPosition - cameraCentre_) / cameraFrames_;

    const size_t count = std::min(ids.size(), worldPoints.size());
    for (size_t i = 0; i < count; i++) {
        const Eigen::Vector3d &p = worldPoints[i];
        if (!std::isfinite(p.x()) || !std::isfinite(p.y()) || !std::isfinite(p.z())) continue;
        // A room is not 40 metres deep and not 2 cm from the lens; anything else is a bad depth.
        const double range = p.norm();
        if (range > ROOM_POINT_RANGE || range < ROOM_MIN_DEPTH) continue;

        // One slot per feature id, replaced by the newest sighting: a corner seen from six frames is
        // still one corner, and a floor fitted to six sightings of each of a hundred corners is a
        // floor fitted to the hundred corners.
        auto found = slots_.find(ids[i]);
        if (found != slots_.end()) {
            points_[found->second] = p;
            continue;
        }
        if (static_cast<int>(ids_.size()) >= kMaxFeatures) continue;  // a session this long is not a scan
        slots_[ids[i]] = points_.size();
        ids_.push_back(ids[i]);
        points_.push_back(p);
    }
    if (elapsed() >= window_) {
        // The window is over, so the scan is over: [finish] fits what was collected or, if there
        // was not enough of it, marks the scan failed. Waiting for both the window *and* a point
        // threshold here would leave a scan that never saw a room stuck in SCANNING forever, with
        // the home's status line spinning on a scan that will never answer.
        finish();
    } else {
        // The progress the settings and the home show, so a scan that is quietly collecting is not
        // the same silence as one that will never finish.
        result_.state = ScanState::SCANNING;
        result_.progress = window_ > 0 ? std::min(1.0, elapsed() / window_) : 1.0;
        result_.pointCount = static_cast<int>(points_.size());
    }
}

void RoomScanner::finish() {
    result_ = RoomScan();
    result_.state = ScanState::DONE;
    result_.progress = 1.0;
    result_.pointCount = static_cast<int>(points_.size());
    if (result_.pointCount < ROOM_MIN_POINTS) {
        // Not enough to say anything: better a scan that admits it saw nothing than a floor invented
        // from twenty points, which would be drawn across the floor of a room the user is in.
        result_.state = ScanState::FAILED;
        state_ = ScanState::FAILED;
        return;
    }

    result_.quantiles = quantiles();
    if (fitFloor(&result_.floorNormal, &result_.floorHeight, &result_.floorCentre, &result_.floorRadius,
                 &result_.floorPointCount)) {
        result_.floorFound = true;
    }
    fitWalls(&result_.wallNormals, &result_.wallDistances, &result_.wallHistogram);

    // How far under the eye the floor sits. This is the one number in the signature that measures a
    // person rather than a place, which is exactly why it is kept: the same head in the same room
    // drops the same distance every session, and a session whose height has drifted is a session
    // whose accelerometer bias is wrong - the dominant way VINS-Mono drifts at all.
    if (result_.floorFound) {
        std::vector<double> drops;
        drops.reserve(points_.size());
        for (const auto &p : points_) {
            if (std::fabs(result_.floorNormal.dot(p) - result_.floorHeight) > kPlaneEpsilon) continue;
            drops.push_back((p - cameraCentre_).z());
        }
        std::sort(drops.begin(), drops.end());
        if (!drops.empty()) {
            result_.dropMedian = -drops[drops.size() / 2];
            result_.dropTenth = -drops[drops.size() / 10];
        }
    }

    // Without a floor there is no room: every number below is measured against the floor, and a scan
    // of a ceiling would otherwise come back as a confident description of somewhere else.
    result_.valid = result_.floorFound;
    if (!result_.valid) result_.state = ScanState::FAILED;
    // Close the scan on the scanner's own clock too: [result_.state] is what the caller reads, but
    // [state_] is what stops [addFrame] from collecting - and from overwriting this result - on the
    // frames after the fit, and what makes [done()] true.
    state_ = result_.state;
}

bool RoomScanner::fitFloor(Eigen::Vector3d *normal, double *height, Eigen::Vector3d *centre,
                           double *radius, int *inliers) const {
    // The scan's centre is the middle of the points the scan saw. Not the camera's start - a session
    // that walks three metres across a room starts nowhere near its middle, and the signature has to
    // mean the same thing from wherever the head happened to be put on.
    Eigen::Vector3d middle = Eigen::Vector3d::Zero();
    for (const auto &p : points_) middle += p;
    middle /= static_cast<double>(points_.size());

    // Only what is well under the eye can be a floor. A table 20 cm below the head is not one.
    // [finish] has already closed the scan by the time this runs, so this is the fit over everything
    // the scan collected - the whole cloud, not one frame's worth of it.
    std::vector<Eigen::Vector3d> candidates;
    candidates.reserve(points_.size());
    for (const auto &p : points_) {
        if (p.z() < middle.z() - kMinDropBelow) candidates.push_back(p);
    }
    if (static_cast<int>(candidates.size()) < ROOM_MIN_PLANE_POINTS) return false;

    // Propose: three points at random. Count how many of the candidates the plane through them holds,
    // keeping the best; then fit to the inliers properly and count again, twice, which is where the
    // plane actually comes from - the proposals only decide *which* points.
    std::vector<int> best;
    unsigned seed = 0x9e3779b9u;
    auto nextRandom = [&seed, &candidates]() {
        seed = seed * 1664525u + 1013904223u;
        return static_cast<size_t>(seed >> 8) % candidates.size();
    };

    Eigen::Vector3d bestNormal(0, 0, 1);
    double bestHeight = 0;
    for (int round = 0; round < kRansacRounds; round++) {
        const size_t i = nextRandom();
        const size_t j = nextRandom();
        const size_t k = nextRandom();
        if (i == j || j == k || i == k) continue;
        Eigen::Vector3d n = (candidates[j] - candidates[i]).cross(candidates[k] - candidates[i]);
        if (n.norm() < 1e-9) continue;
        n.normalize();
        // Only near-level planes: a floor in a room is not 20 degrees off, and admitting tilted ones
        // is how a big tabletop ends up drawn as the floor.
        if (std::fabs(n.z()) < kFloorNormalZ) continue;
        if (n.z() < 0) n = -n;
        const double h = -n.dot(candidates[i]);
        std::vector<int> hits;
        if (countInliers(candidates, n, h, kPlaneEpsilon, &hits) > static_cast<int>(best.size())) {
            best = hits;
            bestNormal = n;
            bestHeight = h;
        }
    }
    if (best.size() < ROOM_MIN_PLANE_POINTS) return false;

    for (int pass = 0; pass < 2; pass++) {
        std::vector<Eigen::Vector3d> inlierPoints;
        inlierPoints.reserve(best.size());
        for (int i : best) inlierPoints.push_back(candidates[i]);
        Eigen::Vector3d n;
        double h = 0;
        if (!fitPlane(inlierPoints, &n, &h)) return false;
        std::vector<int> hits;
        if (countInliers(candidates, n, h, kPlaneEpsilon, &hits) < ROOM_MIN_PLANE_POINTS) return false;
        best = hits;
        bestNormal = n;
        bestHeight = h;
    }
    *normal = bestNormal;
    // Hand the plane back as a height (`n . p == height`) rather than the offset (`n . p + offset =
    // 0`) the fit works in internally: what a caller compares between two sessions is how high the
    // floor is - a signed number, one eye-height below the origin - and an offset carries the
    // opposite sign of exactly that number.
    *height = -bestHeight;
    *inliers = static_cast<int>(best.size());
    Eigen::Vector3d sum = Eigen::Vector3d::Zero();
    double far = 0;
    for (int i : best) {
        sum += candidates[i];
        const Eigen::Vector3d d = candidates[i] - middle;
        far = std::max(far, std::hypot(d.x(), d.y()));
    }
    *centre = sum / static_cast<double>(best.size());
    *radius = far;
    return true;
}

bool RoomScanner::fitWalls(std::vector<Eigen::Vector3d> *normals, std::vector<double> *distances,
                           std::vector<double> *histogram) const {
    // Everything above the floor and off to the side: the walls a headset ever sees. A wall's normal
    // is turned upright afterwards, since only its heading is kept.
    Eigen::Vector3d middle = Eigen::Vector3d::Zero();
    for (const auto &p : points_) middle += p;
    middle /= static_cast<double>(points_.size());

    std::vector<Eigen::Vector3d> candidates;
    candidates.reserve(points_.size());
    for (const auto &p : points_) {
        if (p.z() < middle.z() - kMinDropBelow) continue;                          // that is the floor
        if (std::hypot(p.x() - middle.x(), p.y() - middle.y()) < 0.3) continue;    // straight overhead
        candidates.push_back(p);
    }
    if (static_cast<int>(candidates.size()) < kMinWallPoints) return false;

    // A room has more than one wall, so this is a loop rather than a single fit: take the best
    // upright plane, refit it to its own inliers, *remove those inliers* and ask again with what is
    // left. Stopping at the first plane is how a scan ends up describing one wall of a room as the
    // room; running out of candidates is how it ends up describing a doorframe as a second wall.
    std::vector<Eigen::Vector3d> working = candidates;
    for (int wall = 0; wall < kMaxWalls; wall++) {
        if (static_cast<int>(working.size()) < kMinWallPoints) break;

        unsigned seed = 0x85ebca6bu + static_cast<unsigned>(wall) * 0x27d4eb2fu;
        auto nextRandom = [&seed, &working]() {
            seed = seed * 1664525u + 1013904223u;
            return static_cast<size_t>(seed >> 8) % working.size();
        };

        Eigen::Vector3d bestNormal(1, 0, 0);
        double bestHeight = 0;
        std::vector<int> best;
        for (int round = 0; round < kRansacRounds; round++) {
            const size_t i = nextRandom();
            const size_t j = nextRandom();
            const size_t k = nextRandom();
            if (i == j || j == k || i == k) continue;
            Eigen::Vector3d n = (working[j] - working[i]).cross(working[k] - working[i]);
            if (n.norm() < 1e-9) continue;
            n.normalize();
            if (std::fabs(n.z()) > kWallNormalZ) continue;  // level: floor or ceiling, not a wall
            const double h = -n.dot(working[i]);
            std::vector<int> hits;
            if (countInliers(working, n, h, kPlaneEpsilon * 2, &hits) > static_cast<int>(best.size())) {
                best = hits;
                bestNormal = n;
                bestHeight = h;
            }
        }
        if (best.size() < kMinWallPoints) break;

        // Fitting to the inliers rather than to the three points that proposed the plane is what
        // takes the wall off the slope of the noise and onto the wall.
        Eigen::Vector3d fitted = bestNormal;
        double height = bestHeight;
        for (int pass = 0; pass < 2; pass++) {
            std::vector<Eigen::Vector3d> inlierPoints;
            inlierPoints.reserve(best.size());
            for (int i : best) inlierPoints.push_back(working[i]);
            Eigen::Vector3d n;
            double h = 0;
            if (!fitPlane(inlierPoints, &n, &h)) return false;
            std::vector<int> hits;
            if (countInliers(working, n, h, kPlaneEpsilon * 2, &hits) < kMinWallPoints) break;
            best = hits;
            fitted = n;
            height = h;
        }

        const Eigen::Vector3d heading = headingOf(fitted);
        const double distance = std::fabs(heading.dot(middle) + height);
        const double headingAngle = std::atan2(heading.y(), heading.x());

        // A heading within a few degrees and a distance within a few centimetres is the same wall
        // seen twice - a corner, or the two halves of a wall the head straddled - not two walls.
        bool duplicate = false;
        for (size_t i = 0; i < normals->size(); i++) {
            const double other = std::atan2((*normals)[i].y(), (*normals)[i].x());
            if (std::fabs(wrapAngle(headingAngle - other)) < kWallMergeAngle &&
                std::fabs(distance - (*distances)[i]) < kWallMergeDistance) {
                duplicate = true;
                break;
            }
        }
        if (!duplicate) {
            normals->push_back(heading);
            distances->push_back(distance);
        }

        // Take the inliers away and look at the rest of the room.
        std::vector<bool> spent(working.size(), false);
        for (int i : best) spent[i] = true;
        std::vector<Eigen::Vector3d> rest;
        rest.reserve(working.size() - best.size());
        for (size_t i = 0; i < working.size(); i++) {
            if (!spent[i]) rest.push_back(working[i]);
        }
        working.swap(rest);
    }

    if (normals->empty()) return false;
    histogram->assign(ROOM_WALL_BINS, 0.0);
    for (const auto &n : *normals) (*histogram)[binOf(std::atan2(n.y(), n.x()))] += 1.0;
    double sum = 0;
    for (double v : *histogram) sum += v;
    if (sum > 0) {
        for (double &v : *histogram) v /= sum;
    }
    return true;
}

std::vector<double> RoomScanner::quantiles() const {
    Eigen::Vector3d middle = Eigen::Vector3d::Zero();
    for (const auto &p : points_) middle += p;
    middle /= static_cast<double>(points_.size());

    std::vector<double> distances;
    distances.reserve(points_.size());
    for (const auto &p : points_) distances.push_back((p - middle).norm());
    std::sort(distances.begin(), distances.end());

    std::vector<double> out(ROOM_QUANTILES, 0.0);
    for (int i = 0; i < ROOM_QUANTILES; i++) {
        size_t index = static_cast<size_t>(kQuantileFractions[i] * (distances.size() - 1) + 0.5);
        if (index >= distances.size()) index = distances.size() - 1;
        out[i] = distances[index];
    }
    return out;
}

}  // namespace vins
