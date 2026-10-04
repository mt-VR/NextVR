// The VINS-Mono estimator: what the phone's Java side drives, and what it reads back.
//
// Upstream runs this as a ROS node: subscribers for the IMU and for the tracked features, a thread
// that batches measurements, and publishers for the odometry the AR demo draws. Here the subscribers
// become the calls below, and the publishers become [latestPose] — the pose the VR home wants, in the
// same numbers `pubOdometry` put into nav_msgs/Odometry (see upstream/vins_estimator/src/utility/
// visualization.cpp, which stays out of the build because nothing here publishes to rviz).
#pragma once

#include <string>

namespace vins {

/** Where the headset is, as one frame of VINS-Mono output. */
struct Pose {
    /** Position of the camera in the estimator's world, metres: x, y, z (z is up, gravity along -z). */
    double p[3];
    /** Rotation of the camera in that world, x, y, z, w. */
    double q[4];
    /** Velocity, m/s, for anyone who wants to look ahead. */
    double v[3];
    /** The IMU-propagated pose of the newest reading, for a smoother view than the last frame's. */
    double propagatedP[3];
    double propagatedQ[4];
    /** Seconds on the sensors' clock: what the pose (or the propagated one) belongs to. */
    double stamp = 0;
    /** True once the sliding window has been solved, so [p] and [q] mean something. */
    bool tracked = false;
    /** True while the estimator is solving, false while it is still initializing or has failed. */
    bool solving = false;
    /** How many features the last solved frame carried: a hint about how well the room is seen. */
    int features = 0;
    /** The image-to-IMU time offset the estimator settled on, seconds (0 while it is fixed). */
    double td = 0;
    /** Seconds since the newest processed frame; the app calls the room lost past a second of it. */
    double age = 0;
};

/**
 * Starts both halves of the tracker with the device's config file (the YAML the app writes, in the
 * shape of upstream's `config/euroc/euroc_config.yaml`). Returns false with a reason when anything
 * is missing, having left nothing running.
 */
bool startTracker(const std::string &configPath, std::string *error);

/** Stops the estimator's thread and forgets the buffers. Safe to call twice. */
void stopTracker();

/** One IMU reading on the sensors' clock, in the units Android reports (m/s^2 with gravity, rad/s). */
void pushImu(double tSec, double ax, double ay, double az, double gx, double gy, double gz);

/** The newest pose; false when nothing has been solved yet. */
bool latestPose(Pose *out);

/** Back to the beginning: the sliding window is cleared, so "here" becomes the current spot. */
void restartTracker();

/** True between [startTracker] and [stopTracker]. */
bool running();

}  // namespace vins
