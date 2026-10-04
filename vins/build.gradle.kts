plugins {
    id("com.android.library")
}

// VINS-Mono on a phone: the upstream visual-inertial estimator (src/main/cpp/upstream, see
// UPSTREAM.txt) behind a thin JNI layer, so the VR home can get a 6DoF position on devices ARCore
// does not run on.
//
// The CMake build is the slow part of this module (Ceres and Eigen are compiled the first time), so
// `-Pvins.enabled=false` leaves the native core out entirely: the module stays, its Java facade stays
// too, and VinsCore.available answers false — which is what NextVR's settings show as the reason the
// VINS-Mono mode cannot be picked, and the home goes on with ARCore or its own neck-model 3DoF.
val nativeCore = (findProperty("vins.enabled") as? String)?.toBoolean() != false
// Extra CMake arguments, for a build against libraries already on the machine:
//     -Pvins.cmakeArgs="-DVINS_EIGEN_DIR=/opt/eigen -DVINS_CERES_DIR=/opt/ceres"
val cmakeArguments = ((findProperty("vins.cmakeArgs") as? String) ?: "").split(' ').filter { it.isNotBlank() }

android {
    namespace = "com.samrat.vins"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        // The app's release build minifies; the JNI names must survive it.
        consumerProguardFiles += file("consumer-rules.pro")
        // The estimator runs on the phone's own arm64 core; the other ABIs are not worth the build time.
        ndk { abiFilters += listOf("arm64-v8a") }
        if (nativeCore) externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                arguments += cmakeArguments
                // Ceres 1.14 (the version VINS-Mono asks for) does not build as C++17.
                cppFlags += "-std=c++14"
            }
        }
    }

    if (nativeCore) externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // OpenCV's C++ headers and libraries come from its AAR, through Prefab.
    buildFeatures { prefab = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The same OpenCV the Full edition carries for the Joy-Con markers; VINS-Mono's front end needs
    // goodFeaturesToTrack, the LK optical flow, cv::FileStorage and the solver's matrix types from it.
    implementation("org.opencv:opencv:4.14.0")
}
