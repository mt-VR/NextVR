// The JNI surface of VINS-Mono. Everything here is a thin translation layer: arrays in, arrays out,
// the calls of runtime/vins_runtime.h behind them. The tracker's own state lives in the runtime.
#include <jni.h>

#include <cstdint>
#include <string>

#include <opencv2/core.hpp>

#include "runtime/vins_frontend.h"
#include "runtime/vins_runtime.h"

#define VINS_JNI(name) Java_com_samrat_vins_VinsCore_##name

extern "C" {

/** "OK", or why the tracker cannot run. Never throws: a phone should show the reason, not crash. */
jstring VINS_JNI(nativeStart)(JNIEnv *env, jobject /*self*/, jstring configPath) {
    const char *chars = env->GetStringUTFChars(configPath, nullptr);
    const std::string path = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(configPath, chars);

    std::string error;
    if (vins::startTracker(path, &error)) return env->NewStringUTF("OK");
    return env->NewStringUTF(error.c_str());
}

void VINS_JNI(nativeStop)(JNIEnv * /*env*/, jobject /*self*/) { vins::stopTracker(); }

jboolean VINS_JNI(nativeRunning)(JNIEnv * /*env*/, jobject /*self*/) {
    return vins::running() ? JNI_TRUE : JNI_FALSE;
}

/** One accelerometer + gyroscope reading, both stamped on the same monotonic clock. */
void VINS_JNI(nativePushImu)(JNIEnv *env, jobject /*self*/, jlong timeNs, jdoubleArray acceleration, jdoubleArray gyroscope) {
    jdouble *accel = env->GetDoubleArrayElements(acceleration, nullptr);
    jdouble *gyro = env->GetDoubleArrayElements(gyroscope, nullptr);
    if (accel != nullptr && gyro != nullptr) {
        vins::pushImu(static_cast<double>(timeNs) * 1e-9, accel[0], accel[1], accel[2], gyro[0], gyro[1], gyro[2]);
    }
    if (accel != nullptr) env->ReleaseDoubleArrayElements(acceleration, accel, JNI_ABORT);
    if (gyro != nullptr) env->ReleaseDoubleArrayElements(gyroscope, gyro, JNI_ABORT);
}

/**
 * One raw grayscale frame, straight onto the camera's Y plane: [gray] is a direct buffer, and the
 * row stride is the sensor's, so no copying and no undistortion happen here (VINS-Mono undistorts
 * the feature points itself, through the camera model of the config file).
 */
void VINS_JNI(nativePushFrame)(JNIEnv *env, jobject /*self*/, jobject gray, jint width, jint height, jint rowStride, jlong timeNs) {
    if (gray == nullptr || width <= 0 || height <= 0) return;
    void *pixels = env->GetDirectBufferAddress(gray);
    if (pixels == nullptr) return;
    cv::Mat image(height, width, CV_8UC1, pixels, static_cast<size_t>(rowStride));
    vins::trackFrame(image, static_cast<double>(timeNs) * 1e-9);
}

/**
 * The newest pose into [out] (which must hold 21 doubles):
 *   0..2 position, 3..6 rotation as x y z w, 7..9 velocity,
 *   10..12 and 13..16 the same pose propagated to the newest IMU reading,
 *   17 the frame's time in seconds, 18 flags (1 tracking, 2 solving), 19 age in seconds, 20 td.
 * False when nothing has been solved yet.
 */
jboolean VINS_JNI(nativePollPose)(JNIEnv *env, jobject /*self*/, jdoubleArray out) {
    if (out == nullptr || env->GetArrayLength(out) < 21) return JNI_FALSE;
    vins::Pose pose;
    const bool fresh = vins::latestPose(&pose);
    jdouble values[21] = {0};
    for (int i = 0; i < 3; i++) {
        values[i] = pose.p[i];
        values[7 + i] = pose.v[i];
        values[10 + i] = pose.propagatedP[i];
    }
    for (int i = 0; i < 4; i++) {
        values[3 + i] = pose.q[i];
        values[13 + i] = pose.propagatedQ[i];
    }
    values[17] = pose.stamp;
    values[18] = (pose.tracked ? 1.0 : 0.0) + (pose.solving ? 2.0 : 0.0) + (pose.features > 20 ? 4.0 : 0.0);
    values[19] = pose.age;
    values[20] = pose.td;
    env->SetDoubleArrayRegion(out, 0, 21, values);
    return fresh ? JNI_TRUE : JNI_FALSE;
}

void VINS_JNI(nativeReset)(JNIEnv * /*env*/, jobject /*self*/) { vins::restartTracker(); }

}  // extern "C"
