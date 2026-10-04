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
# ...and one header in every translation unit, for the strdupa() that bionic does not have (see the
# file: it is a compiler flag rather than an added include, because the upstream header that uses it
# stays as upstream wrote it).
add_compile_options(-include ${CMAKE_CURRENT_SOURCE_DIR}/shim/vins_gnu.h)

find_library(LOG_LIB log)

# ---------------------------------------------------------------- third-party
include(FetchContent)
set(FETCHCONTENT_QUIET FALSE)

set(VINS_EIGEN_DIR "" CACHE PATH "An Eigen checkout to use instead of fetching one")
if (VINS_EIGEN_DIR)
    set(EIGEN3_INCLUDE_DIR "${VINS_EIGEN_DIR}" CACHE PATH "Eigen includes" FORCE)
    message(STATUS "VINS-Mono: Eigen from VINS_EIGEN_DIR")
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
# Ceres' FindEigen looks for EIGEN_INCLUDE_DIR — singular, and without the 3 — and would otherwise go
# hunting through the system for an Eigen that is not there. Hand it the same checkout, and tell it
# not to prefer an installed/exported configuration over what it is given.
set(EIGEN_INCLUDE_DIR "${EIGEN3_INCLUDE_DIR}" CACHE PATH "Eigen includes, as Ceres asks for them" FORCE)
set(EIGEN_PREFER_EXPORTED_EIGEN_CMAKE_CONFIGURATION FALSE CACHE BOOL "Use the include dir above" FORCE)
message(STATUS "VINS-Mono: Eigen at ${EIGEN3_INCLUDE_DIR}")

set(VINS_CERES_DIR "" CACHE PATH "A Ceres Solver checkout to use instead of building one")
# Ceres 1.14.0 asks for a CMake older than 3.5, which CMake 4 refuses to honour at all; this tells
# the policy machinery to treat that old request as 3.5 instead of failing. Ignored by older CMake.
if (NOT CMAKE_POLICY_VERSION_MINIMUM)
    set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
endif()
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

# VINS-Mono needs OpenCV's C++ side (cv::Mat, the optical flow, the feature detector, cv::FileStorage)
# rather than its Java bindings. Three shapes are accepted, because which one a machine has depends on
# how OpenCV got there:
#
#   * the Maven AAR the Gradle module depends on (org.opencv:opencv), whose Prefab package creates an
#     imported target for every module the AAR ships — that one is "opencv_java4", the whole library;
#   * an OpenCV SDK checkout with an OpenCVConfig.cmake, pointed at by -DVINS_OPENCV_DIR, which is what
#     upstream VINS-Mono builds against (it defines OpenCV_LIBS, the prefab package does not);
#   * nothing, in which case the error says so, and says where it looked.
set(VINS_OPENCV_DIR "" CACHE PATH "An OpenCV SDK's native/jni directory, containing OpenCVConfig.cmake")
set(VINS_OPENCV_LIBS "")
set(VINS_OPENCV_INCLUDES "")
if (VINS_OPENCV_DIR)
    set(OpenCV_DIR "${VINS_OPENCV_DIR}")
endif()
find_package(OpenCV QUIET)
if (OpenCV_FOUND AND OpenCV_LIBS)
    set(VINS_OPENCV_LIBS ${OpenCV_LIBS})
    set(VINS_OPENCV_INCLUDES ${OpenCV_INCLUDE_DIRS})
    message(STATUS "VINS-Mono: OpenCV ${OpenCV_VERSION} from ${OpenCV_INSTALL_PATH}")
endif()
if (NOT VINS_OPENCV_LIBS)
    # Prefab's package name is the dependency's, and the module name is inside the AAR — for the
    # official OpenCV one that is a single "opencv_java4" holding every module. Both spellings and
    # the names of a couple of other OpenCV-for-Android packages are tried; the target that exists
    # is the one to link, and it carries its own include directory.
    foreach (candidate
            OpenCV::opencv_java4 opencv::opencv_java4
            OpenCV::opencv opencv::opencv
            OpenCV::core opencv::core
            opencv::opencv_world OpenCV::opencv_world)
        if (TARGET ${candidate})
            list(APPEND VINS_OPENCV_LIBS ${candidate})
        endif()
    endforeach()
    if (VINS_OPENCV_LIBS)
        list(REMOVE_DUPLICATES VINS_OPENCV_LIBS)
    else()
        # What a hand-rolled or offline build needs to know: what it was told, and where it looked.
        message(FATAL_ERROR
            "VINS-Mono found no OpenCV to link against.\n"
            "  find_package(OpenCV) said: ${OpenCV_FOUND} (OpenCV_LIBS: ${OpenCV_LIBS})\n"
            "  CMAKE_PREFIX_PATH: ${CMAKE_PREFIX_PATH}\n"
            "  Prefab generates the opencv targets when the Gradle module builds with prefab turned "
            "on (vins/build.gradle.kts) and org.opencv:opencv on the classpath; an SDK build instead "
            "takes -DVINS_OPENCV_DIR=<OpenCV>/native/jni. See vins/README.txt.")
    endif()
endif()
message(STATUS "VINS-Mono: linking ${VINS_OPENCV_LIBS}")

# Ceres 1.14 only publishes its include directories through its installed CeresConfig.cmake, which a
# build as a subdirectory never sees; and one of its own headers is generated into the build tree.
# So the paths are named here rather than inherited from the target.
set(VINS_CERES_INCLUDES "")
if (VINS_CERES_DIR)
    list(APPEND VINS_CERES_INCLUDES "${VINS_CERES_DIR}/include")
else()
    list(APPEND VINS_CERES_INCLUDES
        "${ceres_SOURCE_DIR}/include"
        "${ceres_BINARY_DIR}/include"
        "${ceres_BINARY_DIR}/config")
    # This build turns glog off and uses the stand-in Ceres ships (MINIGLOG=ON), but Ceres' own public
    # headers include <glog/logging.h> whatever the configuration — so the stand-in has to be on the
    # include path of everything that reads them, not only of Ceres itself.
    if (EXISTS "${ceres_SOURCE_DIR}/internal/ceres/miniglog")
        list(APPEND VINS_CERES_INCLUDES "${ceres_SOURCE_DIR}/internal/ceres/miniglog")
    endif()
endif()
if (NOT TARGET ceres)
    message(FATAL_ERROR "VINS-Mono: there is no 'ceres' target to link against. The Ceres fetch failed, "
        "or VINS_CERES_DIR points somewhere without a CMake project.")
endif()
message(STATUS "VINS-Mono: Ceres headers from ${VINS_CERES_INCLUDES}")

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

    # OpenCV for Android has no highgui, which is what upstream's debug-preview call sites need; the
    # two entry points are defined away here so that file stays as upstream wrote it.
    ${CMAKE_CURRENT_SOURCE_DIR}/shim/opencv_highgui_no_window.cpp

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
    ${VINS_CERES_INCLUDES}
    ${VINS_OPENCV_INCLUDES})

target_link_libraries(vins_jni PRIVATE
    ceres
    ${VINS_OPENCV_LIBS}
    ${LOG_LIB})
