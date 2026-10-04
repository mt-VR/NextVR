// Small stand-in for the part of ROS that VINS-Mono's core touches.
//
// The upstream estimator (see ../upstream, and UPSTREAM.txt next to this module) is a ROS node
// family: the nodes exchange messages and read parameters from a node handle. Only three things
// from ROS reach the algorithm itself — log lines, an assert, and a stamped header — so those are
// what this header provides, and nothing else is needed to run VINS-Mono on a phone.
//
// Kept deliberately close to ROS's own spelling so the vendored sources stay untouched.
#pragma once

#include <android/log.h>
#include <cstdint>
#include <sstream>
#include <string>

#define VINS_LOG_TAG "PhoneXR-VINS"

#define VINS_LOG(level, fmt, ...) __android_log_print(level, VINS_LOG_TAG, fmt, ##__VA_ARGS__)

#define ROS_DEBUG(...) VINS_LOG(ANDROID_LOG_DEBUG, __VA_ARGS__)
#define ROS_INFO(...) VINS_LOG(ANDROID_LOG_INFO, __VA_ARGS__)
#define ROS_WARN(...) VINS_LOG(ANDROID_LOG_WARN, __VA_ARGS__)
#define ROS_ERROR(...) VINS_LOG(ANDROID_LOG_ERROR, __VA_ARGS__)

// ROS streams a value into the logger; the same through a stringstream and one line.
#define ROS_LOG_STREAM(level, args)                        \
    do {                                                   \
        std::ostringstream vins_log_stream;                \
        vins_log_stream << args;                           \
        __android_log_print(level, VINS_LOG_TAG, "%s", vins_log_stream.str().c_str()); \
    } while (0)

#define ROS_DEBUG_STREAM(args) ROS_LOG_STREAM(ANDROID_LOG_DEBUG, args)
#define ROS_INFO_STREAM(args) ROS_LOG_STREAM(ANDROID_LOG_INFO, args)
#define ROS_WARN_STREAM(args) ROS_LOG_STREAM(ANDROID_LOG_WARN, args)
#define ROS_ERROR_STREAM(args) ROS_LOG_STREAM(ANDROID_LOG_ERROR, args)

namespace ros {

/** Seconds since the boot of the monotonic clock the sensors stamp with (see VinsCore.kt). */
class Time {
  public:
    Time() : sec(0), nsec(0) {}
    Time(double t) { fromSec(t); }

    double toSec() const { return static_cast<double>(sec) + 1e-9 * static_cast<double>(nsec); }
    Time &fromSec(double t) {
        sec = static_cast<uint32_t>(t);
        nsec = static_cast<uint32_t>((t - static_cast<double>(sec)) * 1e9);
        return *this;
    }

    bool isZero() const { return sec == 0 && nsec == 0; }

    uint32_t sec;
    uint32_t nsec;
};

/** Only here because upstream's readParameters(ros::NodeHandle&) keeps its signature. */
class NodeHandle {
  public:
    NodeHandle() = default;
    explicit NodeHandle(const std::string & /*namespace*/) {}
    void shutdown() {}
};

inline Time now() { return Time(); }

}  // namespace ros

// <ros/ros.h> is also how upstream gets at ROS_ASSERT and ROS_BREAK; both are declared in their
// own header there, and this one has to be included last, once the log macros exist.
#include "assert.h"
