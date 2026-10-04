// ROS_ASSERT/ROS_BREAK/ROS_ASSERT_MSG as plain aborts: a broken estimator should stop, not drift.
#pragma once
#include <cstdlib>
#include <cstdio>
#include "ros.h"

#define ROS_ASSERT(cond)                                                        \
    do {                                                                        \
        if (!(cond)) {                                                          \
            ROS_ERROR("assertion failed: %s at %s:%d", #cond, __FILE__, __LINE__); \
            abort();                                                            \
        }                                                                       \
    } while (0)

#define ROS_ASSERT_MSG(cond, fmt, ...)                                          \
    do {                                                                        \
        if (!(cond)) {                                                          \
            ROS_ERROR("assertion failed: %s (%s) at %s:%d", #cond, fmt, __FILE__, __LINE__); \
            abort();                                                            \
        }                                                                       \
    } while (0)

#define ROS_BREAK()                                                             \
    do {                                                                        \
        ROS_ERROR("ROS_BREAK at %s:%d", __FILE__, __LINE__);                    \
        abort();                                                                \
    } while (0)
