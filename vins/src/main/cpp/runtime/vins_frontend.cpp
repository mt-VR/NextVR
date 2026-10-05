#include "runtime/vins_frontend.h"

#include <algorithm>
#include <cmath>
#include <set>
#include <vector>

#include <opencv2/opencv.hpp>

#include "feature_tracker.h"
#include "runtime/vins_queue.h"
#include "runtime/vins_runtime.h"
#include "sensor_msgs/PointCloud.h"

namespace vins {

namespace {

FeatureTracker trackerData[NUM_OF_CAM];
double firstImageTime = 0;
double lastImageTime = 0;
int pubCount = 1;
bool firstImageFlag = true;
bool initPub = false;
bool cameraReady = false;

/**
 * Upstream's frequency control, unchanged: the tracker runs on every frame, but features are handed
 * to the estimator at [FREQ] Hz, which is what keeps the solver's cost steady on a phone.
 */
bool timeForAFrame(double t) {
    if (FREQ <= 0) return true;
    if (firstImageTime == 0) {
        firstImageTime = t;
        pubCount = 1;
        return false;
    }
    const double elapsed = t - firstImageTime;
    if (elapsed <= 0) return false;
    const double rate = 1.0 * pubCount / elapsed;
    const bool publish = rate <= FREQ;
    if (std::fabs(rate - FREQ) < 0.01 * FREQ) {
        firstImageTime = t;
        pubCount = 0;
    }
    return publish;
}

}  // namespace

bool prepareFrontend(std::string *error) {
    for (int i = 0; i < NUM_OF_CAM; i++) {
        trackerData[i].readIntrinsicParameter(CAM_NAMES[i]);
        if (!trackerData[i].m_camera) {
            if (error != nullptr) *error = "the camera calibration in the config file could not be read";
            return false;
        }
    }
    cameraReady = true;
    return true;
}

void resetFrontend() {
    firstImageFlag = true;
    firstImageTime = 0;
    lastImageTime = 0;
    pubCount = 1;
    initPub = false;
}

void trackFrame(const cv::Mat &gray, double tSec) {
    // Not while the estimator is down: a frame pushed between a stop and the next start would
    // otherwise be tracked into nobody — and, on a reconfigure, race the front end's own setup.
    if (!running() || !cameraReady) return;
    if (gray.type() != CV_8UC1 || gray.cols != COL || gray.rows != ROW) {
        ROS_WARN("frame %dx%d does not match the configured %dx%d, dropping it", gray.cols, gray.rows, COL, ROW);
        return;
    }

    if (firstImageFlag) {
        firstImageFlag = false;
        firstImageTime = tSec;
        lastImageTime = tSec;
        return;
    }
    // A long pause or a clock that jumped back leaves the tracker holding points of a room that is
    // gone: both halves start again, which is what upstream signals on its "restart" topic.
    if (tSec - lastImageTime > 1.0 || tSec < lastImageTime) {
        ROS_WARN("image stream discontinued, resetting the feature tracker");
        firstImageFlag = true;
        lastImageTime = 0;
        pubCount = 1;
        initPub = false;
        restartTracker();
        return;
    }
    lastImageTime = tSec;

    PUB_THIS_FRAME = timeForAFrame(tSec);

    for (int i = 0; i < NUM_OF_CAM; i++) trackerData[i].readImage(gray.rowRange(ROW * i, ROW * (i + 1)), tSec);

    for (unsigned int i = 0;; i++) {
        bool completed = false;
        for (int j = 0; j < NUM_OF_CAM; j++) completed |= trackerData[j].updateID(i);
        if (!completed) break;
    }

    if (!PUB_THIS_FRAME) return;
    pubCount++;

    sensor_msgs::PointCloudPtr features(new sensor_msgs::PointCloud);
    sensor_msgs::ChannelFloat32 idOfPoint, uOfPoint, vOfPoint, velocityXOfPoint, velocityYOfPoint;

    features->header.stamp.fromSec(tSec);
    features->header.frame_id = "world";

    for (int i = 0; i < NUM_OF_CAM; i++) {
        auto &unPts = trackerData[i].cur_un_pts;
        auto &curPts = trackerData[i].cur_pts;
        auto &ids = trackerData[i].ids;
        auto &ptsVelocity = trackerData[i].pts_velocity;
        for (unsigned int j = 0; j < ids.size(); j++) {
            if (trackerData[i].track_cnt[j] <= 1) continue;
            const int pId = ids[j];
            geometry_msgs::Point32 p;
            p.x = unPts[j].x;
            p.y = unPts[j].y;
            p.z = 1;
            features->points.push_back(p);
            idOfPoint.values.push_back(static_cast<float>(pId * NUM_OF_CAM + i));
            uOfPoint.values.push_back(curPts[j].x);
            vOfPoint.values.push_back(curPts[j].y);
            velocityXOfPoint.values.push_back(ptsVelocity[j].x);
            velocityYOfPoint.values.push_back(ptsVelocity[j].y);
        }
    }
    features->channels.push_back(idOfPoint);
    features->channels.push_back(uOfPoint);
    features->channels.push_back(vOfPoint);
    features->channels.push_back(velocityXOfPoint);
    features->channels.push_back(velocityYOfPoint);

    // The first image carries no optical speed, so upstream does not publish it; the same here.
    if (!initPub) {
        initPub = true;
        return;
    }
    Buffers &queue = buffers();
    {
        std::lock_guard<std::mutex> lock(queue.mutex);
        queue.features.push_back(features);
    }
    queue.ready.notify_one();
}

}  // namespace vins
