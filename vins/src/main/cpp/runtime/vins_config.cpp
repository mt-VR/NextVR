// Where the two VINS-Mono nodes keep their numbers, and how a phone's YAML gets into them.
//
// The variables are upstream's (declared by `upstream/feature_tracker/src/parameters.h` and
// `upstream/vins_estimator/src/parameters.h`); the reading of them is upstream's two
// `readParameters()` bodies, merged because a phone has one config file, not a launch file per node.
#include "runtime/vins_config.h"

#include <eigen3/Eigen/Dense>
#include <opencv2/opencv.hpp>
#include <opencv2/core/eigen.hpp>
#include <string>
#include <vector>

#include "ros/ros.h"

// --- feature tracker (upstream feature_tracker/src/parameters.cpp) -----------------------------
int ROW;
int COL;
int FOCAL_LENGTH;
std::string IMAGE_TOPIC;
std::string IMU_TOPIC;
std::string FISHEYE_MASK;
std::vector<std::string> CAM_NAMES;
int MAX_CNT;
int MIN_DIST;
int FREQ;
double F_THRESHOLD;
int SHOW_TRACK;
int STEREO_TRACK;
int EQUALIZE;
int FISHEYE;
bool PUB_THIS_FRAME;

// --- estimator (upstream vins_estimator/src/parameters.cpp) -------------------------------------
double INIT_DEPTH;
double MIN_PARALLAX;
double ACC_N, ACC_W;
double GYR_N, GYR_W;

std::vector<Eigen::Matrix3d> RIC;
std::vector<Eigen::Vector3d> TIC;
Eigen::Vector3d G{0.0, 0.0, 9.8};

double BIAS_ACC_THRESHOLD;
double BIAS_GYR_THRESHOLD;
double SOLVER_TIME;
int NUM_ITERATIONS;
int ESTIMATE_EXTRINSIC;
int ESTIMATE_TD;
int ROLLING_SHUTTER;
std::string EX_CALIB_RESULT_PATH;
std::string VINS_RESULT_PATH;
double TD, TR;

namespace vins {

bool loadParameters(const std::string &configPath, std::string *error) {
    cv::FileStorage settings(configPath, cv::FileStorage::READ);
    if (!settings.isOpened()) {
        if (error != nullptr) *error = "cannot open " + configPath;
        return false;
    }

    // Topics have no meaning without ROS; the names are kept for the log line, as upstream does.
    settings["imu_topic"] >> IMU_TOPIC;
    settings["image_topic"] >> IMAGE_TOPIC;

    // The front end: what to look for in a frame and how often to hand it to the estimator.
    MAX_CNT = settings["max_cnt"];
    MIN_DIST = settings["min_dist"];
    ROW = settings["image_height"];
    COL = settings["image_width"];
    FREQ = settings["freq"];
    F_THRESHOLD = settings["F_threshold"];
    SHOW_TRACK = settings["show_track"];
    EQUALIZE = settings["equalize"];
    FISHEYE = settings["fisheye"];
    STEREO_TRACK = 0;            // a phone has one camera to the rear
    FOCAL_LENGTH = 460;          // upstream's, used to scale the undistorted plane for RANSAC
    if (FREQ == 0) FREQ = 10;
    if (FISHEYE == 1) {
        // Upstream loads a circular mask that hides the fisheye border. Without the file the front end
        // works on the whole frame, which is what the phone's own lens does in the pinhole model.
        FISHEYE_MASK = "";
        FISHEYE = 0;
    }
    PUB_THIS_FRAME = false;
    CAM_NAMES.push_back(configPath);  // readIntrinsicParameter() reads the camera out of this file

    // The estimator: how hard to solve, and how noisy the sensors are.
    SOLVER_TIME = settings["max_solver_time"];
    NUM_ITERATIONS = settings["max_num_iterations"];
    MIN_PARALLAX = settings["keyframe_parallax"];
    MIN_PARALLAX = MIN_PARALLAX / 460.0;  // upstream divides by its FOCAL_LENGTH constant

    ACC_N = settings["acc_n"];
    ACC_W = settings["acc_w"];
    GYR_N = settings["gyr_n"];
    GYR_W = settings["gyr_w"];
    G.z() = settings["g_norm"];

    ESTIMATE_EXTRINSIC = settings["estimate_extrinsic"];
    if (ESTIMATE_EXTRINSIC == 2) {
        ROS_INFO("calibrating the camera-to-IMU rotation while the headset is put on");
        RIC.push_back(Eigen::Matrix3d::Identity());
        TIC.push_back(Eigen::Vector3d::Zero());
    } else {
        cv::Mat cvR, cvT;
        settings["extrinsicRotation"] >> cvR;
        settings["extrinsicTranslation"] >> cvT;
        Eigen::Matrix3d eigenR = Eigen::Matrix3d::Identity();
        Eigen::Vector3d eigenT = Eigen::Vector3d::Zero();
        if (cvR.rows == 3 && cvR.cols == 3) cv::cv2eigen(cvR, eigenR);
        if (cvT.rows == 3 && cvT.cols == 1) cv::cv2eigen(cvT, eigenT);
        const Eigen::Quaterniond q(eigenR);
        RIC.push_back(q.normalized().toRotationMatrix());
        TIC.push_back(eigenT);
    }

    BIAS_ACC_THRESHOLD = 0.1;
    BIAS_GYR_THRESHOLD = 0.1;
    INIT_DEPTH = 5.0;

    TD = settings["td"];
    ESTIMATE_TD = settings["estimate_td"];
    ROS_INFO_STREAM("image-to-IMU time offset: " << TD << (ESTIMATE_TD ? " (estimated online)" : " (fixed)"));

    ROLLING_SHUTTER = settings["rolling_shutter"];
    if (ROLLING_SHUTTER) {
        TR = settings["rolling_shutter_tr"];
    } else {
        TR = 0;
    }

    settings.release();
    if (ROW <= 0 || COL <= 0) {
        if (error != nullptr) *error = "the config has no image size";
        return false;
    }
    ROS_INFO("VINS-Mono configured for %dx%d, %d features, %d Hz", COL, ROW, MAX_CNT, FREQ);
    return true;
}

}  // namespace vins
