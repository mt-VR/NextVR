// The room scan: what a VINS-Mono world looks like as a *place*, not as a stream of poses.
//
// Upstream VINS-Mono tracks features and nothing else. It follows the head well inside a session and
// has no idea a floor or a wall exists, so (a) the home has no room model to draw, and (b) nothing
// ever corrects the drift a session accumulates - the README's limits 1 and 2. Upstream's answer to
// (b) is `pose_graph/`, with a 58 MB BRIEF vocabulary and a keyframe database, and this build does
// not have it (see vins/README.txt, limit 2).
//
// This is the other answer, and the one a room actually invites: the room *is* the map. A scan
// watches the estimator's own triangulated features for a few seconds at the start of a session and
// fits the handful of planes a room is made of - a floor, and the walls that stand on it - out of
// nothing but the points the solver already produced. What comes out is small, metric and
// recognisable: a floor plane, a footprint, a set of walls, and a signature that does not care which
// way the head was facing when it was taken. Two sessions in one room produce the same signature
// whatever the start, so the second one can be anchored onto the first and its drift cancelled.
//
// Deliberately free of Ceres, OpenCV and ROS, so it can be built and run on a host (see
// `vins/tools/room_scan_test.cpp`, which is the only place this file's algorithms are checked) and so
// the estimator's own sources stay untouched.
#pragma once

#include <cmath>
#include <unordered_map>
#include <vector>

#include <eigen3/Eigen/Dense>

namespace vins {

/** Bins in the wall histogram: 10 degrees of heading each, a full turn. */
constexpr int ROOM_WALL_BINS = 36;
/** Quantile radii the signature is built from: half, four fifths, nine tenths, and the last tenth. */
constexpr int ROOM_QUANTILES = 4;
/** How far out of the room a point is allowed to be, metres. A room's far wall is nearer than this. */
constexpr double ROOM_MAX_RANGE = 12.0;
/** The furthest a point may sit from the camera that saw it, metres. */
constexpr double ROOM_POINT_RANGE = 10.0;
/** Points a scan needs before it will fit anything at all. */
constexpr int ROOM_MIN_POINTS = 120;
/** Inliers a plane needs before the scan will believe it. */
constexpr int ROOM_MIN_PLANE_POINTS = 60;
/** A feature nearer than this, or further, than this, is not a wall: metres. */
constexpr double ROOM_MIN_DEPTH = 0.2;
constexpr double ROOM_MAX_DEPTH = 10.0;

/** How far a session's scan is through. The scanner owns its own clock, so nothing else needs one. */
enum class ScanState { IDLE, SCANNING, DONE, FAILED };

/**
 * One scan of one room: the geometry that was seen, and the numbers to recognise it by.
 *
 * Everything here is in the estimator's own world of the session that produced it - z up, metres,
 * with wherever the camera started as the origin - except the signature, which is deliberately built
 * to survive that. A signature compared between two sessions can only answer "same room?" if it is
 * blind to the two things that differ every time: which way the head was facing, and where in the
 * room it happened to start. The quantiles below survive both (a distance from the scan's centre does
 * not care about the heading), which is why they, and not the raw footprint, are what is stored.
 */
struct RoomScan {
    /** True once a scan finished with enough points to be worth keeping. */
    bool valid = false;
    /** Where the scan got to, for the status line. */
    ScanState state = ScanState::IDLE;
    /** 0..1 through the scan window, for the same reason. */
    double progress = 0;
    /** Distinct points the scan saw, and how many of them the floor took. */
    int pointCount = 0;
    int floorPointCount = 0;

    /**
     * The floor's height: every floor point `p` satisfies `floorNormal.dot(p) == floorHeight`, with
     * the normal a unit vector pointing *up*. The world is z-up, so a level floor's normal is
     * (0, 0, 1) and [floorHeight] is then simply the height the floor sits at - one eye-height below
     * where the session started, about -1.6. Found or not; [floorHeight] alone says nothing.
     */
    bool floorFound = false;
    Eigen::Vector3d floorNormal{0.0, 0.0, 1.0};
    double floorHeight = 0;
    /** The centre of the floor the scan saw, in the world: where the room's middle turned out to be. */
    Eigen::Vector3d floorCentre{Eigen::Vector3d::Zero()};

    /**
     * The walls: unit normals with their z taken to zero (a wall is vertical, so only its heading is
     * worth keeping) and, for each, the distance from the scan's centre to the plane. Paired with the
     * [ROOM_WALL_BINS] histogram below, which is the same information in a form two scans can be
     * compared in.
     */
    std::vector<Eigen::Vector3d> wallNormals;
    std::vector<double> wallDistances;
    /** Heading of each wall, 0..1 over the 36 bins of a full turn. Empty when no wall was found. */
    std::vector<double> wallHistogram;

    /**
     * The signature. [quantiles] are the distances from the scan's centre at which half, four fifths,
     * nine tenths and nineteen twentieths of the room's points fall, in metres, left to right; they
     * are what says how big this place is, and comparing two scans' is what says how much one
     * session's scale drifted. [dropBelowCamera] is the median and the tenth percentile of how far
     * the floor sits under the head, which is both a strong cue (people do not change height) and the
     * one number a vertical drift can be corrected against.
     */
    std::vector<double> quantiles;
    double dropMedian = 0;
    double dropTenth = 0;

    /** The furthest floor point from the scan's centre, metres; a room's rough radius. */
    double floorRadius = 0;
};

/**
 * Collects the estimator's triangulated features during a session's first few seconds and fits the
 * room out of them.
 *
 * One instance lives in the runtime for the length of a tracker run. It is fed the world's points on
 * every solved frame, and it decides for itself when the scan is complete; nothing polls it except
 * [scan]. It holds no locks of its own: the estimator's thread owns it, and it only answers under
 * that thread's [mEstimator] (see runtime/vins_backend.cpp).
 */
class RoomScanner {
public:
    /**
     * Starts a scan, discarding anything a previous one left. Called on every start and every restart,
     * which is what makes this a scan *on every boot*: nothing about a room survives a restart, and
     * the room is looked at again from scratch.
     */
    void beginScan(double windowSeconds);

    /**
     * One solved frame's worth of the estimator's triangulated features, in the world, with the
     * camera's own position in that world.
     *
     * Points are kept one per feature id rather than one per observation: the same physical corner is
     * seen from frame to frame, and keeping every sighting would fit the floor to whichever wall the
     * head spent the scan looking at. This is also what bounds the memory.
     *
     * [stampSeconds] is on the sensors' clock and is what the scan window is measured in. It is a
     * separate argument rather than something [elapsed] guesses: the runtime's frame timestamps are
     * the same clock the IMU runs on, so there is nothing to convert and nothing to get wrong.
     */
    void addFrame(const std::vector<int> &ids, const std::vector<Eigen::Vector3d> &worldPoints,
                  const Eigen::Vector3d &cameraPosition, double stampSeconds);

    /**
     * Seconds since the scan began, or 0 when none is running. Measured from the first frame the
     * scan saw rather than from [beginScan], because the stamps are the sensors' own clock: a
     * window counted from zero would already be over on the first frame of a phone whose clock
     * started hours ago.
     */
    double elapsed() const { return state_ == ScanState::SCANNING && start_ >= 0 ? now_ - start_ : 0; }

    /** True while the scan is still collecting. */
    bool scanning() const { return state_ == ScanState::SCANNING; }

    /** True when the scan is done and its result may be read. */
    bool done() const { return state_ == ScanState::DONE; }

    /** The result. Valid, and only interesting, once [done()]. */
    const RoomScan &scan() const { return result_; }

    /** Throws the scan away and goes back to [ScanState::IDLE]. */
    void reset();

    /** The most distinct points the scanner keeps before it starts forgetting the oldest. */
    static constexpr int kMaxFeatures = 4000;

private:
    /** The scan's own clock, pushed by the runtime: [addFrame] has no timestamp to spare. */
    void setNow(double stampSeconds) { now_ = stampSeconds; }

    /** Fits everything, once the window has closed or enough points have arrived. */
    void finish();

    /** The plane through the points under the camera that the most of them agree on. */
    bool fitFloor(Eigen::Vector3d *normal, double *height, Eigen::Vector3d *centre, double *radius,
                  int *inliers) const;

    /** The near-vertical planes above the floor, as [ROOM_WALL_BINS] bins of heading. */
    bool fitWalls(std::vector<Eigen::Vector3d> *normals, std::vector<double> *distances,
                  std::vector<double> *histogram) const;

    /** Distances from the scan's centre at the quantiles in ROOM_QUANTILES. */
    std::vector<double> quantiles() const;

    ScanState state_ = ScanState::IDLE;
    /** The stamp of the first frame the scan saw, or -1 until one arrives. */
    double start_ = -1;
    double now_ = 0;
    double window_ = 4.0;
    /** Where the camera was, averaged over the frames the scan has seen. */
    Eigen::Vector3d cameraCentre_{Eigen::Vector3d::Zero()};
    int cameraFrames_ = 0;
    /** The one newest sighting of each feature, by the estimator's feature id. */
    std::vector<Eigen::Vector3d> points_;
    std::vector<int> ids_;
    /**
     * Where each of those ids sits in [points_], so [addFrame] can replace a sighting in constant
     * time. Scanning the vector for it instead is O(features^2) on a phone, on the estimator's own
     * thread, on every frame of every scan.
     */
    std::unordered_map<int, size_t> slots_;
    RoomScan result_;
};

/**
 * The correction that puts one session's world onto another's: a similarity about the vertical, which
 * is all the room geometry can justify and all the drift needs.
 *
 * [scale] is the ratio of the two rooms' true size (a session that thinks the room is 8% small has
 * [scale] above one), [yaw] turns the session's heading onto the room's, and [translation] moves it.
 * Applying it is `p -> scale * yawAroundZ(yaw) * p + translation`, which is what
 * `vins::setRoomAnchor` does to every pose the estimator goes on to publish.
 */
struct RoomAnchor {
    double scale = 1.0;
    double yaw = 0.0;
    Eigen::Vector3d translation{Eigen::Vector3d::Zero()};
};

/** The world point [p] seen through [anchor]. */
inline Eigen::Vector3d applyRoomAnchor(const RoomAnchor &anchor, const Eigen::Vector3d &p) {
    const double c = std::cos(anchor.yaw);
    const double s = std::sin(anchor.yaw);
    return Eigen::Vector3d(anchor.scale * (c * p.x() - s * p.y()), anchor.scale * (s * p.x() + c * p.y()),
                            anchor.scale * p.z()) +
           anchor.translation;
}

}  // namespace vins
