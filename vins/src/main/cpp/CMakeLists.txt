# VINS-Mono for Android: the upstream estimator, the JNI layer of this module, and the two third-party
# libraries the estimator cannot do without.
#
# The third-party pieces are fetched the first time this is configured and cached by Gradle (they
# live under vins/build, so a clean removes them). For an offline build, or a build against libraries
# already on the machine, point them somewhere else:
#
#   -DVINS_EIGEN_DIR=/path/to/eigen
#   -DVINS_CERES_DIR=/path/to/ceres-solver
#
# Ceres is pinned to 1.14.0 and Eigen to 3.3.9 because that is the pair upstream VINS-Mono asks for
# (its README: "additional ROS package … use version 1.14.0"; newer Ceres versions need source
# changes VINS-Mono has not had). Both are then built from source, small, and static, so the APK
# carries one extra .so.
cmake_minimum_required(VERSION 3.22.1)
project(vins_jni CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# -O3 is what the upstream nodes are built with; the estimator is the hottest loop on the phone.
add_compile_options(-O3 -fno-strict-aliasing -DEIGEN_DONT_PARALLELIZE -DNDEBUG -w)

find_library(LOG_LIB log)

# ---------------------------------------------------------------- third-party
include(FetchContent)
set(FETCHCONTENT_QUIET FALSE)

set(VINS_EIGEN_DIR "" CACHE PATH "An Eigen checkout to use instead of fetching one")
if (VINS_EIGEN_DIR)
    set(EIGEN3_INCLUDE_DIR "${VINS_EIGEN_DIR}" CACHE PATH "Eigen includes" FORCE)
else()
    FetchContent_Declare(eigen
        GIT_REPOSITORY https://gitlab.com/libeigen/eigen.git
        GIT_TAG 3.3.9
        GIT_SHALLOW TRUE)
    FetchContent_GetProperties(eigen)
    if (NOT eigen_POPULATED)
        FetchContent_Populate(eigen)
    endif()
    set(EIGEN3_INCLUDE_DIR "${eigen_SOURCE_DIR}" CACHE PATH "Eigen includes" FORCE)
endif()
message(STATUS "VINS-Mono: Eigen at ${EIGEN3_INCLUDE_DIR}")

set(VINS_CERES_DIR "" CACHE PATH "A Ceres Solver checkout to use instead of building one")
if (VINS_CERES_DIR)
    add_subdirectory("${VINS_CERES_DIR}" ceres_build)
else()
    # A Ceres build of its own options, all chosen for one thing: no dependency beyond Eigen and the
    # C++ library. No BLAS/LAPACK, no SuiteSparse, no glog (miniglog instead), no gflags, no tests.
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(BLAS OFF CACHE BOOL "" FORCE)
    set(LAPACK OFF CACHE BOOL "" FORCE)
    set(SUITESPARSE OFF CACHE BOOL "" FORCE)
    set(CXSPARSE OFF CACHE BOOL "" FORCE)
    set(EIGENSPARSE OFF CACHE BOOL "" FORCE)
    set(OPENMP OFF CACHE BOOL "" FORCE)
    set(GFLAGS OFF CACHE BOOL "" FORCE)
    set(MINIGLOG ON CACHE BOOL "" FORCE)
    set(PROVIDE_UNINSTALL_TARGET OFF CACHE BOOL "" FORCE)
    set(SCHUR_SPECIALIZATIONS OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(ceres
        GIT_REPOSITORY https://github.com/ceres-solver/ceres-solver.git
        GIT_TAG 1.14.0
        GIT_SHALLOW TRUE)
    FetchContent_GetProperties(ceres)
    if (NOT ceres_POPULATED)
        FetchContent_Populate(ceres)
        add_subdirectory(${ceres_SOURCE_DIR} ${ceres_BINARY_DIR} EXCLUDE_FROM_ALL)
    endif()
endif()

# OpenCV comes from the Maven AAR the Gradle module depends on: Prefab puts its C++ headers, its
# static libraries and its config file on the search path, and VINS-Mono needs the C++ side (cv::Mat,
# the optical flow, the feature detector, cv::FileStorage) rather than the Java bindings.
find_package(OpenCV REQUIRED)

# ---------------------------------------------------------------- VINS-Mono itself
set(VINS_UPSTREAM ${CMAKE_CURRENT_SOURCE_DIR}/upstream)

add_library(vins_jni SHARED
    # The camera models of upstream camera_model, minus its chessboard calibration tool: a phone's
    # camera is calibrated by the platform, and the YAML that calibration lands in is read here.
    ${VINS_UPSTREAM}/camera_model/src/camera_models/Camera.cc
    ${VINS_UPSTREAM}/camera_model/src/camera_models/CameraFactory.cc
    ${VINS_UPSTREAM}/camera_model/src/camera_models/CostFunctionFactory.cc
    ${VINS_UPSTREAM}/camera_model/src/camera_models/PinholeCamera.cc
    ${VINS_UPSTREAM}/camera_model/src/camera_models/CataCamera.cc
    ${VINS_UPSTREAM}/camera_model/src/camera_models/EquidistantCamera.cc
    ${VINS_UPSTREAM}/camera_model/src/camera_models/ScaramuzzaCamera.cc
    ${VINS_UPSTREAM}/camera_model/src/gpl/gpl.cc
    ${VINS_UPSTREAM}/camera_model/src/gpl/EigenQuaternionParameterization.cc
    ${VINS_UPSTREAM}/camera_model/src/sparse_graph/Transform.cc

    # The front end, upstream as-is; its ROS node becomes runtime/vins_frontend.cpp.
    ${VINS_UPSTREAM}/feature_tracker/src/feature_tracker.cpp

    # The estimator, upstream as-is; its ROS node becomes runtime/vins_backend.cpp, and its ROS
    # parameter reader runtime/vins_config.cpp. The rviz publishers and the pose graph are not built.
    ${VINS_UPSTREAM}/vins_estimator/src/estimator.cpp
    ${VINS_UPSTREAM}/vins_estimator/src/feature_manager.cpp
    ${VINS_UPSTREAM}/vins_estimator/src/factor/marginalization_factor.cpp
    ${VINS_UPSTREAM}/vins_estimator/src/factor/pose_local_parameterization.cpp
    ${VINS_UPSTREAM}/vins_estimator/src/factor/projection_factor.cpp
    ${VINS_UPSTREAM}/vins_estimator/src/factor/projection_td_factor.cpp
    ${VINS_UPSTREAM}/vins_estimator/src/initial/initial_aligment.cpp
    ${VINS_UPSTREAM}/vins_estimator/src/initial/initial_ex_rotation.cpp
    ${VINS_UPSTREAM}/vins_estimator/src/initial/initial_sfm.cpp
    ${VINS_UPSTREAM}/vins_estimator/src/initial/solve_5pts.cpp
    ${VINS_UPSTREAM}/vins_estimator/src/utility/utility.cpp

    # This module's own code: the two nodes without ROS, the configuration, and the JNI bindings.
    ${CMAKE_CURRENT_SOURCE_DIR}/runtime/vins_config.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/runtime/vins_queue.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/runtime/vins_frontend.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/runtime/vins_backend.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/jni/vins_jni.cpp)

target_include_directories(vins_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}          # so "runtime/vins_runtime.h" resolves
    ${CMAKE_CURRENT_SOURCE_DIR}/shim     # ros/, std_msgs/, sensor_msgs/, boost/, eigen3/Eigen
    ${VINS_UPSTREAM}/feature_tracker/src
    ${VINS_UPSTREAM}/vins_estimator/src
    ${VINS_UPSTREAM}/camera_model/include
    ${EIGEN3_INCLUDE_DIR}
    ${OpenCV_INCLUDE_DIRS})

target_link_libraries(vins_jni PRIVATE
    ceres
    ${OpenCV_LIBS}
    ${LOG_LIB})
