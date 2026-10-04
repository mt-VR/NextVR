// The queues between the two halves of VINS-Mono, standing in for the ROS topics of the upstream
// graph (feature_tracker publishes "feature", the estimator subscribes to it and to the IMU).
//
// A phone has one process, so the messages travel through these two deques under one mutex, and the
// estimator's thread waits on the same condition the callbacks notify.
#pragma once

#include <condition_variable>
#include <deque>
#include <mutex>

#include "sensor_msgs/Imu.h"
#include "sensor_msgs/PointCloud.h"

namespace vins {

struct Buffers {
    std::mutex mutex;
    std::condition_variable ready;
    std::deque<sensor_msgs::ImuConstPtr> imu;
    std::deque<sensor_msgs::PointCloudConstPtr> features;

    void clear() {
        std::lock_guard<std::mutex> lock(mutex);
        imu.clear();
        features.clear();
    }
};

Buffers &buffers();

}  // namespace vins
