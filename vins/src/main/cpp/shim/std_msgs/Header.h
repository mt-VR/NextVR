// The message header VINS-Mono stamps every image and IMU reading with.
#pragma once
#include <cstdint>
#include <string>
#include <ros/ros.h>

namespace std_msgs {

struct Header {
    uint32_t seq = 0;
    ros::Time stamp;
    std::string frame_id;
};

}  // namespace std_msgs
