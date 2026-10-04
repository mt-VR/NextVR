// One IMU reading, in the shape the estimator's queue expects.
#pragma once
#include <memory>
#include <geometry_msgs/Vector3.h>
#include <std_msgs/Header.h>

namespace sensor_msgs {

struct Imu {
    std_msgs::Header header;
    geometry_msgs::Vector3 linear_acceleration;  // m/s^2, with gravity in it
    geometry_msgs::Vector3 angular_velocity;     // rad/s
};

using ImuPtr = std::shared_ptr<Imu>;
using ImuConstPtr = std::shared_ptr<const Imu>;

}  // namespace sensor_msgs
