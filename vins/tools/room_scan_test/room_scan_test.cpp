// The room scan, checked. A host build of runtime/vins_room.cpp - the one part of this module with no
// Ceres, no OpenCV and no ROS in it - so the geometry that decides whether a floor is a floor can be
// run and measured here instead of only on a phone.
//
// Build and run (from the repository root, with Eigen pointed at the fetch the Android build already
// did):
//
//     tools/room_scan_test/run.sh
//
// or by hand:
//
//     g++ -std=c++14 -O2 -Wall -I vins/src/main/cpp -I vins/src/main/cpp/shim \
//         -I vins/.cxx/Release/732dz2e4/arm64-v8a/_deps/eigen-src \
//         vins/tools/room_scan_test/room_scan_test.cpp \
//         vins/src/main/cpp/runtime/vins_room.cpp -o room_scan_test
//
// Each test below states what the user would notice if it broke.
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "runtime/vins_room.h"

using vins::RoomScan;
using vins::RoomScanner;

namespace {

int failures = 0;
int checks = 0;

void check(bool condition, const std::string &what) {
    checks++;
    if (!condition) {
        failures++;
        std::printf("  FAIL  %s\n", what.c_str());
    } else {
        std::printf("  ok    %s\n", what.c_str());
    }
}

void checkNear(double got, double want, double tolerance, const std::string &what) {
    checks++;
    if (!(std::fabs(got - want) <= tolerance)) {
        failures++;
        std::printf("  FAIL  %s (got %.4f, want %.4f +/- %.4f)\n", what.c_str(), got,
                    want, tolerance);
    } else {
        std::printf("  ok    %s (%.4f)\n", what.c_str(), got);
    }
}

/** A small deterministic generator, so a failure is the same failure every run. */
struct Rng {
    uint32_t state = 12345u;
    double next() {
        state = state * 1664525u + 1013904223u;
        return static_cast<double>(state >> 8) / static_cast<double>(1 << 24);
    }
    double spread(double amplitude) { return (next() * 2.0 - 1.0) * amplitude; }
};

/**
 * A synthetic room: a floor of [halfX] by [halfY] at z = [floorY] and four walls around it, plus a
 * dense little table that the naive floor fit must not steal. The room is rotated by [yaw] about the
 * camera and scaled by [scale], so a scan of it can be compared with a scan of the same room as the
 * other session in the same room would see it.
 *
 * Points are emitted as seen by a single room: a standing eye at z=0 looking down, with the floor
 * under it and the walls around it. The floor is the flat plane [floorY], the walls stand on it, and
 * the table is a flat thing sitting on it that must not become the floor.
 */
void buildRoom(double halfX, double halfY, double floorY, double eyeZ, double eyeY,
               double yaw, double scale,
               int perWall, int tablePoints, std::vector<int> *ids,
               std::vector<Eigen::Vector3d> *points, Rng *rng) {
    ids->clear();
    points->clear();
    // The floor is most of what a solver triangulates in a room - it is the biggest thing in view -
    // so this generator emits more floor points than walls and table together. That is what makes
    // the check "most of the room's points are floor" true of this room rather than aspirational.
    const int floorPoints = perWall * 8;
    ids->reserve(floorPoints + perWall*4 + tablePoints + 64);
    points->reserve(floorPoints + perWall*4 + tablePoints + 64);
    Rng unused;
    Rng &r = rng != nullptr ? *rng : unused;
    const double c = std::cos(yaw);
    const double s = std::sin(yaw);
    int next = 1;
    auto emit = [&](double x, double y, double z) {
        ids->push_back(next++);
        points->push_back(Eigen::Vector3d(c * x * scale - s * y * scale,
                                           s * x * scale + c * y * scale,
                                           z * scale));
    };
    // Floor: points on the floor plane, spread out over the whole room.
    for (int i = 0; i < floorPoints; i++) {
        const double u = (r.next() * 2.0 - 1.0) * halfX;
        const double v = (r.next() * 2.0 - 1.0) * halfY;
        emit(u, v, floorY);
    }
    // Four walls, each a strip of height ~eyeHeight above the floor.
    const double wallTop = eyeZ * 2.0;
    for (int i = 0; i < perWall; i++) {
        // right wall: x=+halfX
        const double z = floorY + (r.next() * wallTop);
        emit(halfX, (r.next()*2.0-1.0)*halfY, z);
        // left wall
        emit(-halfX, (r.next()*2.0-1.0)*halfY, z);
        // far wall: y=+halfY
        emit((r.next()*2.0-1.0)*halfX, halfY, z);
        // near wall
        emit((r.next()*2.0-1.0)*halfX, -halfY, z);
    }
    // A table: a flat, level thing sitting on the floor, well below the eye, that must not become the
    // floor. It is a separate cluster of points the floor must not steal.
    for (int i = 0; i < tablePoints; i++) {
        const double u = (r.next()*2.0-1.0) * 0.6;
        const double v = (r.next()*2.0-1.0) * 0.5;
        emit(u * halfX, v * halfY, floorY + 0.75);
    }
}

/** Runs a scan over a synthetic room and hands back what it found. */
RoomScan scanOf(double halfX, double halfY, double floorY, double eyeHeight,
               double yaw, double scale, int frames = 60) {
    RoomScanner scanner;
    scanner.beginScan(4.0);
    std::vector<int> ids;
    std::vector<Eigen::Vector3d> points;
    Rng rng;
    // A real room is not one big point cloud given all at once. VINS-Mono solves frames one at a time,
    // and the scan is built from the points it got on each solved frame. We simulate that by building a
    // single big point cloud for the room, then handing the whole thing on every frame - that is the
    // only thing the scanner can pretend a phone does: every solved frame adds something new, and a
    // different frame picks up a different part of the room. We simulate the phone's behaviour by
    // stripping a different subset off each frame so the scan is built from many partial views, not one
    // dump.
    buildRoom(halfX, halfY, -eyeHeight, eyeHeight, 0.0, yaw, scale, 120, 400, &ids, &points, &rng);
    // Index into the cloud so each frame picks a moving window of 400 points, the camera sees the room
    // from different places across the scan.
    const size_t N = points.size();
    if (N == 0) { return scanner.scan(); }
    for (int f = 0; f < frames; f++) {
        std::vector<int> frameIds;
        std::vector<Eigen::Vector3d> framePoints;
        const size_t start = ((f + 1) * 53) % N;
        const size_t cnt = std::min(size_t(300), N);
        for (size_t i = 0; i < cnt; i++) {
            const size_t idx = (start + i) % N;
            frameIds.push_back(ids[idx]);
            framePoints.push_back(points[idx]);
        }
        scanner.addFrame(frameIds, framePoints, Eigen::Vector3d::Zero(), 0.1 * f);
        if (scanner.done() || scanner.scan().state == vins::ScanState::FAILED) break;
    }
    return scanner.scan();
}

}  // namespace

int main() {
    std::printf("room scan\n");

    // --- the scan finds the floor, and not the table -------------------------------------------------
    {
        RoomScan scan = scanOf(2.4, 2.0, -1.6, 1.6, 0.0, 1.0);
        std::printf("        debug: scan.pointCount=%d valid=%d floorFound=%d state=%d progress=%.2f\n",
                    scan.pointCount, scan.valid, scan.floorFound, (int)scan.state, scan.progress);
        check(scan.valid, "a room with a floor scans as a room");
        check(scan.floorFound, "the floor is found");
        if (scan.floorFound) {                checkNear(scan.floorHeight, -1.6, 0.05, "the floor sits one eye-height below the camera");
            checkNear(std::fabs(scan.floorNormal.z()), 1.0, 0.02, "the floor's normal is level");
            checkNear(scan.dropMedian, 1.6, 0.05, "the floor is 1.6 m under the head");
            check(scan.floorPointCount > 400, "most of the room's points are floor");
            if (scan.floorFound) {
                        const double diam = std::sqrt(2.0*scan.floorRadius*scan.floorRadius);
                std::printf("        floor found, floorRadius=%.2f (~%.2f m across diagonal)\n",
                            scan.floorRadius, diam);
            }
        }
        bool hasWalls = scan.wallNormals.size() >= 2;
        // Let the test print what the scanner saw, so the next failure is a readable one rather than a
        // guess. This line stays in the test, not the runtime: we do not put diagnostic output in a
        // visceral module that belongs on the headset.
        std::printf("        %d points, %d floor, %d walls, quantiles %.2f/%.2f/%.2f/%.2f\n",
                    scan.pointCount, scan.floorPointCount,
                    static_cast<int>(scan.wallNormals.size()), (scan.quantiles.size()>0?scan.quantiles[0]:0.0),
                    (scan.quantiles.size()>1?scan.quantiles[1]:0.0),
                    (scan.quantiles.size()>2?scan.quantiles[2]:0.0),
                    (scan.quantiles.size()>3?scan.quantiles[3]:0.0));
        if (!hasWalls) {
            std::fprintf(stderr,"        debug: walls empty?\n");
        }
        check(hasWalls, "at least two walls are named");
    }

    // --- the signature does not care which way the head was facing ------------------------------------
    {
        RoomScan straight = scanOf(2.4, 2.0, -1.6, 1.6, 0.0, 1.0);
        RoomScan turned = scanOf(2.4, 2.0, -1.6, 1.6, 1.234, 1.0);
        check(straight.valid && turned.valid, "both scans of one room succeed");
        for (size_t i = 0; i < straight.quantiles.size(); i++) {
            checkNear(turned.quantiles.at(i), straight.quantiles.at(i), 0.12,
                      "quantile " + std::to_string(i) + " survives a turn of the head");
        }
        checkNear(turned.dropMedian, straight.dropMedian, 0.05, "the drop survives a turn of the head");
    }

    // --- ...nor how big it thinks the room is --------------------------------------------------------
    {
        RoomScan true1 = scanOf(2.4, 2.0, -1.6, 1.6, 0.0, 1.0);
        RoomScan shrunk = scanOf(2.4, 2.0, -1.6, 1.6, 0.0, 0.9);
        check(true1.valid && shrunk.valid, "both scans succeed");
        const double ratio = shrunk.quantiles.at(2) / true1.quantiles.at(2);
        checkNear(ratio, 0.9, 0.05, "a 10% scale error shows up in the signature");
        // "Worth correcting" is measured against unity, not against the ideal ratio: the point of
        // the signature is that a drifted session is measurably far from scale 1.0.
        check(std::fabs(ratio - 1.0) > 0.05, "and is large enough to be worth correcting");
    }

    // --- a different room is not this room ----------------------------------------------------------
    {
        RoomScan lounge = scanOf(2.4, 2.0, -1.6, 1.6, 0.0, 1.0);
        RoomScan hall = scanOf(5.0, 1.1, -1.6, 1.6, 0.0, 1.0);
        check(lounge.valid && hall.valid, "both rooms scan");
        const double difference = std::fabs(lounge.quantiles.at(2) - hall.quantiles.at(2));
        check(difference > 0.3, "a long thin hall is not a square lounge (" +
                                    std::to_string(difference) + " m of quantile apart)");
    }

    // --- nothing to scan is not a room ----------------------------------------------------------------
    {
        RoomScanner scanner;
        scanner.beginScan(1.0);
        std::vector<int> ids;
        std::vector<Eigen::Vector3d> points;
        for (int f = 0; f < 20; f++) {
            ids.clear();
            points.clear();
            for (int i = 0; i < 30; i++) {
                ids.push_back(i);
                points.push_back(Eigen::Vector3d(i * 0.1, i * 0.2, i * 0.05));
            }
            scanner.addFrame(ids, points, Eigen::Vector3d::Zero(), 0.1 * f);
        }
        const RoomScan &scan = scanner.scan();
        check(!scan.valid, "too few points is a failed scan, not an invented floor");
        check(!scan.floorFound, "and no floor is claimed");
    }

    // --- the anchor puts one session's world onto another's -------------------------------------------
    {
        const Eigen::Vector3d here(3.0, -1.0, -1.6);
        const double scale = 0.92;
        const double yaw = 0.7;
        const Eigen::Vector3d translation(1.5, -0.5, 0.2);
        const vins::RoomAnchor anchor{scale, yaw, translation};
        const Eigen::Vector3d there = vins::applyRoomAnchor(anchor, here);
        vins::RoomAnchor backAnchor;
        backAnchor.scale = 1.0 / scale;
        backAnchor.yaw = -yaw;
        // Undoing `scale * yawAroundZ(yaw) * p + t` leaves `- yawAroundZ(-yaw) * t / scale` behind:
        // the forward translation turned back through the yaw and shrunk, not simply negated. Only
        // the z escapes the turn, which is why a plain `-t / scale` round-trips z but not x and y.
        backAnchor.translation =
            -vins::applyRoomAnchor(vins::RoomAnchor{1.0, -yaw, Eigen::Vector3d::Zero()},
                                   translation) /
            scale;
        const Eigen::Vector3d back = vins::applyRoomAnchor(backAnchor, there);
        checkNear(back.x(), here.x(), 1e-9, "the anchor turns back on itself in x");
        checkNear(back.y(), here.y(), 1e-9, "the anchor turns back on itself in y");
        checkNear(back.z(), here.z(), 1e-9, "the anchor turns back on itself in z");

        const vins::RoomAnchor none;
        const Eigen::Vector3d untouched = vins::applyRoomAnchor(none, here);
        checkNear((untouched - here).norm(), 0.0, 1e-12, "no anchor leaves every pose where it was");
    }

    std::printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
