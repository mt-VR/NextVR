// The VINS-Mono estimator: the sliding-window solver, its thread, and the pose it settles on.
//
// Almost all of this is upstream's `vins_estimator/src/estimator_node.cpp`, with ROS taken out:
//  - the IMU and feature subscribers become [pushImu] and the queue the front end fills;
//  - the odometry publisher becomes [latestPose], holding the same numbers (the last window frame's
//    pose, and the IMU-propagated one that `pubLatestOdometry` sent for low latency);
//  - the restart topic becomes [restartTracker];
//  - rviz visualisation and the pose-graph relocalisation are out of the build, so the lines that
//    published them are gone. The batching, the interpolation of an IMU reading across an image
//    timestamp and the wait-for-IMU rule are untouched.
#include "runtime/vins_runtime.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <vector>

#include <opencv2/opencv.hpp>

#include "estimator.h"
#include "runtime/vins_config.h"
#include "runtime/vins_frontend.h"
#include "runtime/vins_queue.h"
#include "sensor_msgs/Imu.h"
#include "sensor_msgs/PointCloud.h"

namespace vins {
namespace {

Estimator estimator;

std::mutex mState;
std::mutex mEstimator;

// The IMU-propagated pose between two frames, exactly as upstream keeps it in the node.
double currentTime = -1;
double latestTime = 0;
Eigen::Vector3d tmpP;
Eigen::Quaterniond tmpQ;
Eigen::Vector3d tmpV;
Eigen::Vector3d tmpBa;
Eigen::Vector3d tmpBg;
Eigen::Vector3d acc0;
Eigen::Vector3d gyr0;
bool initImu = true;
double lastImuT = 0;

std::atomic<bool> isRunning{false};
std::thread processThread;

std::mutex mPose;
Pose pose;

/** Upstream's `predict()`: one IMU reading pushed forward from the last known state. */
void predict(const sensor_msgs::ImuConstPtr &imu) {
    const double t = imu->header.stamp.toSec();
    if (initImu) {
        latestTime = t;
        initImu = false;
        return;
    }
    const double dt = t - latestTime;
    latestTime = t;

    Eigen::Vector3d linearAcceleration{imu->linear_acceleration.x, imu->linear_acceleration.y, imu->linear_acceleration.z};
    Eigen::Vector3d angularVelocity{imu->angular_velocity.x, imu->angular_velocity.y, imu->angular_velocity.z};

    Eigen::Vector3d unAcc0 = tmpQ * (acc0 - tmpBa) - estimator.g;
    Eigen::Vector3d unGyr = 0.5 * (gyr0 + angularVelocity) - tmpBg;
    tmpQ = tmpQ * Utility::deltaQ(unGyr * dt);
    Eigen::Vector3d unAcc1 = tmpQ * (linearAcceleration - tmpBa) - estimator.g;
    Eigen::Vector3d unAcc = 0.5 * (unAcc0 + unAcc1);

    tmpP = tmpP + dt * tmpV + 0.5 * dt * dt * unAcc;
    tmpV = tmpV + dt * unAcc;

    acc0 = linearAcceleration;
    gyr0 = angularVelocity;
}

/** Upstream's `update()`: take the newest solved state and run the IMU that came after it. */
void update() {
    latestTime = currentTime;
    tmpP = estimator.Ps[WINDOW_SIZE];
    tmpQ = estimator.Rs[WINDOW_SIZE];
    tmpV = estimator.Vs[WINDOW_SIZE];
    tmpBa = estimator.Bas[WINDOW_SIZE];
    tmpBg = estimator.Bgs[WINDOW_SIZE];
    acc0 = estimator.acc_0;
    gyr0 = estimator.gyr_0;

    Buffers &queue = buffers();
    std::deque<sensor_msgs::ImuConstPtr> pending;
    {
        std::lock_guard<std::mutex> lock(queue.mutex);
        pending = queue.imu;
    }
    for (auto &imu : pending) predict(imu);
}

/** Upstream's `getMeasurements()`: IMU readings grouped under each image. */
std::vector<std::pair<std::vector<sensor_msgs::ImuConstPtr>, sensor_msgs::PointCloudConstPtr>>
getMeasurements() {
    std::vector<std::pair<std::vector<sensor_msgs::ImuConstPtr>, sensor_msgs::PointCloudConstPtr>> measurements;
    Buffers &queue = buffers();
    while (true) {
        if (queue.imu.empty() || queue.features.empty()) return measurements;

        if (!(queue.imu.back()->header.stamp.toSec() > queue.features.front()->header.stamp.toSec() + estimator.td)) {
            // The IMU has not caught up with the oldest image yet: come back when it has.
            return measurements;
        }
        if (!(queue.imu.front()->header.stamp.toSec() < queue.features.front()->header.stamp.toSec() + estimator.td)) {
            ROS_WARN("throwing an image with no IMU before it, only should happen at the beginning");
            queue.features.pop_front();
            continue;
        }
        sensor_msgs::PointCloudConstPtr image = queue.features.front();
        queue.features.pop_front();

        std::vector<sensor_msgs::ImuConstPtr> imus;
        while (queue.imu.front()->header.stamp.toSec() < image->header.stamp.toSec() + estimator.td) {
            imus.emplace_back(queue.imu.front());
            queue.imu.pop_front();
        }
        imus.emplace_back(queue.imu.front());
        if (imus.empty()) ROS_WARN("no imu between two image");
        measurements.emplace_back(imus, image);
    }
}

/** The odometry `pubOdometry` published, kept for [latestPose] instead of a ROS topic. */
void publishOdometry(double stamp) {
    std::lock_guard<std::mutex> lock(mPose);
    if (estimator.solver_flag != Estimator::SolverFlag::NON_LINEAR) {
        pose.solving = false;
        pose.tracked = false;
        return;
    }
    const Eigen::Quaterniond q(estimator.Rs[WINDOW_SIZE]);
    pose.p[0] = estimator.Ps[WINDOW_SIZE].x();
    pose.p[1] = estimator.Ps[WINDOW_SIZE].y();
    pose.p[2] = estimator.Ps[WINDOW_SIZE].z();
    pose.q[0] = q.x();
    pose.q[1] = q.y();
    pose.q[2] = q.z();
    pose.q[3] = q.w();
    pose.v[0] = estimator.Vs[WINDOW_SIZE].x();
    pose.v[1] = estimator.Vs[WINDOW_SIZE].y();
    pose.v[2] = estimator.Vs[WINDOW_SIZE].z();
    pose.stamp = stamp;
    pose.tracked = true;
    pose.solving = true;
    pose.features = static_cast<int>(estimator.f_manager.getFeatureCount());
    pose.td = estimator.td;
    pose.age = 0;
}

/** The `pubLatestOdometry` pose, so a render can be shown for the newest instant, not the last frame. */
void publishPropagated(double stamp) {
    std::lock_guard<std::mutex> lock(mPose);
    pose.propagatedP[0] = tmpP.x();
    pose.propagatedP[1] = tmpP.y();
    pose.propagatedP[2] = tmpP.z();
    pose.propagatedQ[0] = tmpQ.x();
    pose.propagatedQ[1] = tmpQ.y();
    pose.propagatedQ[2] = tmpQ.z();
    pose.propagatedQ[3] = tmpQ.w();
    if (pose.stamp > 0) pose.age = stamp - pose.stamp;
}

// The thread of upstream's `process()`.
void process() {
    Buffers &queue = buffers();
    while (isRunning.load()) {
        std::vector<std::pair<std::vector<sensor_msgs::ImuConstPtr>, sensor_msgs::PointCloudConstPtr>> measurements;
        {
            std::unique_lock<std::mutex> lock(queue.mutex);
            queue.ready.wait_for(lock, std::chrono::milliseconds(50), [&] {
                if (!isRunning.load()) return false;
                measurements = getMeasurements();
                return !measurements.empty();
            });
        }
        if (measurements.empty()) continue;

        std::lock_guard<std::mutex> estimatorLock(mEstimator);
        for (auto &measurement : measurements) {
            auto image = measurement.second;
            for (auto &imu : measurement.first) {
                const double t = imu->header.stamp.toSec();
                const double imgT = image->header.stamp.toSec() + estimator.td;
                double dx = imu->linear_acceleration.x, dy = imu->linear_acceleration.y, dz = imu->linear_acceleration.z;
                double rx = imu->angular_velocity.x, ry = imu->angular_velocity.y, rz = imu->angular_velocity.z;
                if (t <= imgT) {
                    if (currentTime < 0) currentTime = t;
                    const double dt = t - currentTime;
                    ROS_ASSERT(dt >= 0);
                    currentTime = t;
                    estimator.processIMU(dt, Vector3d(dx, dy, dz), Vector3d(rx, ry, rz));
                } else {
                    // The reading straddles the image: split it across the gap, as upstream does.
                    const double dt1 = imgT - currentTime;
                    const double dt2 = t - imgT;
                    currentTime = imgT;
                    ROS_ASSERT(dt1 >= 0);
                    ROS_ASSERT(dt2 >= 0);
                    ROS_ASSERT(dt1 + dt2 > 0);
                    const double w1 = dt2 / (dt1 + dt2);
                    const double w2 = dt1 / (dt1 + dt2);
                    dx = w1 * dx + w2 * imu->linear_acceleration.x;
                    dy = w1 * dy + w2 * imu->linear_acceleration.y;
                    dz = w1 * dz + w2 * imu->linear_acceleration.z;
                    rx = w1 * rx + w2 * imu->angular_velocity.x;
                    ry = w1 * ry + w2 * imu->angular_velocity.y;
                    rz = w1 * rz + w2 * imu->angular_velocity.z;
                    estimator.processIMU(dt1, Vector3d(dx, dy, dz), Vector3d(rx, ry, rz));
                }
            }

            map<int, vector<pair<int, Eigen::Matrix<double, 7, 1>>>> features;
            for (unsigned int i = 0; i < image->points.size(); i++) {
                const int v = static_cast<int>(image->channels[0].values[i] + 0.5f);
                const int featureId = v / NUM_OF_CAM;
                const int cameraId = v % NUM_OF_CAM;
                ROS_ASSERT(image->points[i].z == 1);
                Eigen::Matrix<double, 7, 1> xyzUvVelocity;
                xyzUvVelocity << image->points[i].x, image->points[i].y, static_cast<double>(image->points[i].z),
                    image->channels[1].values[i], image->channels[2].values[i], image->channels[3].values[i],
                    image->channels[4].values[i];
                features[featureId].emplace_back(cameraId, xyzUvVelocity);
            }
            estimator.processImage(features, image->header);
            publishOdometry(image->header.stamp.toSec());
        }

        {
            std::lock_guard<std::mutex> stateLock(mState);
            if (estimator.solver_flag == Estimator::SolverFlag::NON_LINEAR) update();
            publishPropagated(latestTime);
        }
    }
}

}  // namespace

bool startTracker(const std::string &configPath, std::string *error) {
    if (isRunning.load()) return true;
    if (!loadParameters(configPath, error)) return false;
    // A start after a stop begins from nothing: no window, no marginals, no drift carried over.
    estimator.clearState();
    estimator.setParameter();
    if (!prepareFrontend(error)) return false;

    {
        Buffers &queue = buffers();
        std::lock_guard<std::mutex> lock(queue.mutex);
        queue.imu.clear();
        queue.features.clear();
    }
    {
        std::lock_guard<std::mutex> lock(mPose);
        pose = Pose();
    }
    currentTime = -1;
    lastImuT = 0;
    initImu = true;
    isRunning.store(true);
    processThread = std::thread(process);
    ROS_INFO("VINS-Mono estimator running");
    return true;
}

void stopTracker() {
    if (!isRunning.exchange(false)) return;
    buffers().ready.notify_all();
    if (processThread.joinable()) processThread.join();
    resetFrontend();
    ROS_INFO("VINS-Mono estimator stopped");
}

void pushImu(double tSec, double ax, double ay, double az, double gx, double gy, double gz) {
    if (!isRunning.load()) return;
    if (tSec <= lastImuT) {
        ROS_WARN("imu message out of order, dropped");
        return;
    }
    lastImuT = tSec;

    sensor_msgs::ImuPtr imu(new sensor_msgs::Imu);
    imu->header.stamp.fromSec(tSec);
    imu->linear_acceleration.x = ax;
    imu->linear_acceleration.y = ay;
    imu->linear_acceleration.z = az;
    imu->angular_velocity.x = gx;
    imu->angular_velocity.y = gy;
    imu->angular_velocity.z = gz;

    Buffers &queue = buffers();
    {
        std::lock_guard<std::mutex> lock(queue.mutex);
        // A tracker paused and resumed can be handed readings older than the queue's; dropping the
        // stale end is cheaper than letting the estimator wait on a clock that repeats itself.
        if (!queue.imu.empty() && queue.imu.back()->header.stamp.toSec() >= tSec) queue.imu.clear();
        queue.imu.push_back(imu);
        // A paused estimator must not fall behind forever: keep the newest readings only.
        while (queue.imu.size() > 2000) queue.imu.pop_front();
    }
    queue.ready.notify_one();

    {
        std::lock_guard<std::mutex> stateLock(mState);
        predict(imu);
        if (estimator.solver_flag == Estimator::SolverFlag::NON_LINEAR) publishPropagated(tSec);
    }
}

bool latestPose(Pose *out) {
    if (out == nullptr) return false;
    std::lock_guard<std::mutex> lock(mPose);
    *out = pose;
    return pose.stamp > 0;
}

void restartTracker() {
    if (!isRunning.load()) return;
    Buffers &queue = buffers();
    {
        std::lock_guard<std::mutex> lock(queue.mutex);
        queue.features.clear();
        queue.imu.clear();
    }
    {
        std::lock_guard<std::mutex> lock(mEstimator);
        estimator.clearState();
        estimator.setParameter();
    }
    resetFrontend();
    {
        std::lock_guard<std::mutex> lock(mPose);
        pose = Pose();
    }
    currentTime = -1;
    lastImuT = 0;
    initImu = true;
    ROS_WARN("VINS-Mono restarted: the room ahead becomes the room again");
}

bool running() { return isRunning.load(); }

}  // namespace vins
