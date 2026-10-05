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

#include "camodocal/camera_models/PinholeCamera.h"
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

namespace {

// The camera model the front end undistorts its features with, built from the config file's own
// numbers the moment they are read (see loadParameters and calibrationCamera below).
camodocal::CameraPtr gCamera;

}  // namespace

namespace vins {

camodocal::CameraPtr calibrationCamera() { return gCamera; }

bool loadParameters(const std::string &configPath, std::string *error) {
    cv::FileStorage settings(configPath, cv::FileStorage::READ);
    if (!settings.isOpened()) {
        if (error != nullptr) *error = "cannot open " + configPath;
        return false;
    }

    // These three are globals and they are appended to below, and a load happens on every start and
    // every restart — so they must start from nothing, here, before anything is pushed. Clearing
    // them later in the function wipes what this very call added, and an empty RIC leaves
    // setParameter() indexing element 0 of an empty vector with no calibration behind it.
    RIC.clear();
    TIC.clear();
    CAM_NAMES.clear();

    // Topics have no meaning without ROS; the names are kept for the log line, as upstream does.
    settings["imu_topic"] >> IMU_TOPIC;
    settings["image_topic"] >> IMAGE_TOPIC;

    // The front end: what to look for in a frame and how often to hand it to the estimator.
    MAX_CNT = settings["max_cnt"];
    MIN_DIST = settings["min_dist"];
    ROW = settings["image_height"];
    COL = settings["image_width"];
    // The lens itself, read here while the file is still open: upstream leaves this to the front
    // end's own second read of the file, and this build hands the front end a model instead.
    const double camK1 = settings["distortion_parameters"]["k1"];
    const double camK2 = settings["distortion_parameters"]["k2"];
    const double camP1 = settings["distortion_parameters"]["p1"];
    const double camP2 = settings["distortion_parameters"]["p2"];
    const double camFx = settings["projection_parameters"]["fx"];
    const double camFy = settings["projection_parameters"]["fy"];
    const double camCx = settings["projection_parameters"]["cx"];
    const double camCy = settings["projection_parameters"]["cy"];
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
    // Upstream's own list of calibration files, kept filled so anything upstream that still reads
    // it sees one entry. The front end no longer opens it: it takes the model built at the end of
    // this function instead, which is what removed the second file read that used to fail.
    CAM_NAMES.push_back(configPath);
    // A key the app does not write reads back as a zero, and a zero there means a front end that
    // never tracks: answer the ordinary figures of upstream's EuRoC config instead.
    if (MAX_CNT <= 0) MAX_CNT = 150;
    if (MIN_DIST <= 0) MIN_DIST = 30;
    if (F_THRESHOLD <= 0) F_THRESHOLD = 1.0;
    // `equalize` is the one key whose zero is a real choice, so it cannot be told apart from a
    // missing line by the value alone: ask FileStorage whether the key was there at all, and fall
    // back to upstream's EuRoC figure (on) rather than to a front end that finds no features in a
    // dim room.
    if (settings["equalize"].empty()) EQUALIZE = 1;
    if (EQUALIZE != 0 && EQUALIZE != 1) EQUALIZE = 1;

    // The estimator: how hard to solve, and how noisy the sensors are. The same rule: whatever the
    // file does not say takes the value upstream's own config ships, so a hand-edited file that
    // loses a line degrades gracefully instead of silently solving with zero noise or no time.
    SOLVER_TIME = settings["max_solver_time"];
    NUM_ITERATIONS = settings["max_num_iterations"];
    MIN_PARALLAX = settings["keyframe_parallax"];
    if (SOLVER_TIME <= 0) SOLVER_TIME = 0.04;
    if (NUM_ITERATIONS <= 0) NUM_ITERATIONS = 8;
    if (MIN_PARALLAX <= 0) MIN_PARALLAX = 10.0;
    MIN_PARALLAX = MIN_PARALLAX / 460.0;  // upstream divides by its FOCAL_LENGTH constant

    ACC_N = settings["acc_n"];
    ACC_W = settings["acc_w"];
    GYR_N = settings["gyr_n"];
    GYR_W = settings["gyr_w"];
    G.z() = settings["g_norm"];
    if (ACC_N <= 0) ACC_N = 0.1;
    if (ACC_W <= 0) ACC_W = 0.001;
    if (GYR_N <= 0) GYR_N = 0.01;
    if (GYR_W <= 0) GYR_W = 1.0e-4;
    if (G.z() <= 0) G.z() = 9.8;

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
    if (!(camFx > 0) || !(camFy > 0)) {
        if (error != nullptr) *error = "the config has no usable focal length";
        return false;
    }
    // The camera the front end projects and undistorts with, assembled from the numbers above.
    //
    // Upstream builds this the long way round: the config's path goes into the global CAM_NAMES,
    // the front end calls generateCameraFromYamlFile() on it, and the file is opened a second
    // time. That second open is what failed on the device this was written for — the path came
    // back as three bytes of rubbish (logcat, 10-05 18:19:22, "reading paramerter of camera" with
    // rubbish where the path should be), the model stayed null, and every single start ended in
    // "the camera calibration in the config file could not be read". With the model never built
    // the estimator never ran, no camera frame and no IMU reading was ever accepted, and the
    // headset sat on 3DoF and the neck model — which is exactly the "everything is trying to
    // escape from us" it was reported as. Building it from the numbers already in hand removes the
    // second open, the global string and the dangling reference all at once.
    gCamera = camodocal::PinholeCameraPtr(new camodocal::PinholeCamera(
        "camera", COL, ROW, camK1, camK2, camP1, camP2, camFx, camFy, camCx, camCy));
    ROS_INFO("VINS-Mono camera: %dx%d, fx=%.1f fy=%.1f cx=%.1f cy=%.1f, k=[%.4f %.4f %.4f %.4f]",
             COL, ROW, camFx, camFy, camCx, camCy, camK1, camK2, camP1, camP2);
    ROS_INFO("VINS-Mono configured for %dx%d, %d features, %d Hz", COL, ROW, MAX_CNT, FREQ);
    return true;
}

}  // namespace vins
