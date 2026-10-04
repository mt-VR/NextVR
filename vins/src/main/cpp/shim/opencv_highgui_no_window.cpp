// The two highgui calls upstream's feature tracker makes, with nothing behind them.
//
// FeatureTracker::makeUncertaintyImage() (upstream, untouched) ends with cv::imshow + cv::waitKey: on
// a desktop ROS build that pops up a window of the uncertainty map and stops until a key is pressed,
// which is the whole use of highgui in the files this module builds. OpenCV for Android has no highgui
// at all — the AAR carries no such module — so the two symbols would be an undefined reference at
// link time, and a phone has no window to draw into and no key to wait for anyway.
//
// Defining them here keeps upstream's file as upstream wrote it.
#include <opencv2/core.hpp>

namespace cv {

void imshow(const String & /*winname*/, InputArray /*mat*/) {}

int waitKey(int /*delay*/) { return -1; }

}  // namespace cv
