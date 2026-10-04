// The VINS-Mono front end: optical flow on a raw grayscale frame, tracked features out to the
// estimator. This is upstream's `feature_tracker_node.cpp` image callback with the ROS subscriber
// taken out — the frame arrives from `VinsCore.pushFrame()` instead of a camera topic, and the
// message goes straight into the queue of `vins_queue.h` instead of a publisher.
#pragma once

#include <string>

namespace cv {
class Mat;
}

namespace vins {

/** Reads the camera calibration named by the config file. False with a reason when it cannot. */
bool prepareFrontend(std::string *error);

/** Forgets the tracked points: the next frame starts a fresh stream (also used after a reset). */
void resetFrontend();

/**
 * One grayscale frame (CV_8UC1, the size the config names) at [tSec] on the sensors' clock. The
 * tracking runs here, on the caller's thread, as it ran in the ROS callback; only the message of
 * tracked features is handed to the estimator's queue.
 */
void trackFrame(const cv::Mat &gray, double tSec);

}  // namespace vins
