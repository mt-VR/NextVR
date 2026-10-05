// Parameters of the two upstream VINS-Mono nodes, read once from the device's own YAML.
//
// Upstream keeps them in `feature_tracker/src/parameters.cpp` and `vins_estimator/src/parameters.cpp`,
// each filled by `readParameters(ros::NodeHandle&)` from a ROS launch file and a config YAML. There is
// no launch file on a phone, so both sets live here, filled from the same YAML file the app writes into
// its private directory. The keys, their meaning and their defaults are upstream's (see
// `upstream/config/euroc_config.reference.yaml`, which this file reads the way `cv::FileStorage` does).
//
// Nothing is written out: the CSV results and the extrinsic-calibration file of the ROS build have no
// consumer here, so the paths exist only for the log line.
#pragma once

#include <string>

#include "camodocal/camera_models/Camera.h"

namespace vins {

/** Reads the YAML at [configPath] into the global parameters of both nodes. False when it cannot. */
bool loadParameters(const std::string &configPath, std::string *error);

/**
 * The camera model [loadParameters] built out of the config file, for the front end to use.
 *
 * Upstream hands this over the other way round: the config's path goes into the global CAM_NAMES
 * and the feature tracker opens that file itself, a second time. That second open is what this
 * build could not do — see the note in vins_config.cpp — so the model travels as a model.
 * Null until a load has succeeded.
 */
camodocal::CameraPtr calibrationCamera();

}  // namespace vins
