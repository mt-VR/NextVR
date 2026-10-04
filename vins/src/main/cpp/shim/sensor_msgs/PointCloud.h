// Tracked features of one image: what the front end hands to the estimator, exactly as the
// "feature" topic of upstream feature_tracker_node.cpp.
#pragma once
#include <memory>
#include <vector>
#include <geometry_msgs/Point32.h>
#include <std_msgs/Float32.h>
#include <std_msgs/Header.h>

namespace sensor_msgs {

struct ChannelFloat32 {
    std::string name;
    std::vector<float> values;
};

struct PointCloud {
    std_msgs::Header header;
    std::vector<geometry_msgs::Point32> points;
    std::vector<ChannelFloat32> channels;
};

using PointCloudPtr = std::shared_ptr<PointCloud>;
using PointCloudConstPtr = std::shared_ptr<const PointCloud>;

}  // namespace sensor_msgs
