VINS-Mono in NextVR — what it takes, and where it stops
=======================================================

What it is
----------
The 6DoF mode for phones that have no ARCore. VINS-Mono (HKUST Aerial Robotics Group) fuses one
camera with an IMU and answers where the camera is; NextVR asks it for the head's position in the
same world its own head tracker turns in, so the VR home, its windows and the boundary behave as
they do with ARCore. `UPSTREAM.txt` says which files are VINS-Mono's and what was changed.

In the app, the mode is one of three in Settings → Head tracking (and in the VR settings, under
Room scan): None, ARCore, VINS-Mono. The three are behind one interface (`SixDof`), the home talks
to that only, and one tracker runs at a time: switching closes the one that ran before starting the
next, so the camera and the IMU are never held twice. ARCore stays the preferred option — the mode
defaults to it wherever it is installed.

Building
--------
    ./gradlew :app:assembleFullDebug            # builds libvins_jni.so with the NDK
    ./gradlew -Pvins.enabled=false :app:assembleFullDebug   # skip the native core

The native build needs the NDK and CMake (CMake is fetched by the Android Gradle plugin when it is
missing) and, the first time, the network: Eigen 3.3.9 and Ceres 1.14.0 are cloned into
`vins/.cxx` and compiled there. Point them somewhere else for an offline build:

    ./gradlew -Pvins.cmakeArgs="-DVINS_EIGEN_DIR=/opt/eigen -DVINS_CERES_DIR=/opt/ceres" …

Four things this build is particular about, each of them learned from the build rather than assumed:

  - OpenCV's C++ side comes from the Maven AAR through Prefab (`buildFeatures.prefab` in
    build.gradle.kts). That AAR has one Prefab module, `opencv_java4`, holding every header and the
    whole library — so the CMake target is `OpenCV::opencv_java4` and the SDK-style `OpenCV_LIBS`
    does not exist. An SDK checkout still works: `-DVINS_OPENCV_DIR=<sdk>/native/jni`.
  - The STL is `c++_shared`, which is not AGP's default for a module with native code. OpenCV's
    library is built that way and AGP refuses a static STL beside it (CXX1212).
  - Both then package `libc++_shared.so`, so the app merges them with a `pickFirsts` rule; the
    module's copy wins (a project dependency merges before an external one), and that is the right
    direction: a newer C++ runtime serves a library built against an older one, not the reverse.
  - Ceres 1.14 hands out its include directory only from its *installed* package, and its public
    headers `#include <glog/logging.h>` even when built with MINIGLOG=ON. CMakeLists.txt passes both
    to the consumers explicitly; without that, `camodocal`'s headers do not compile.

Eigen is asked for as `eigen3/Eigen/...` by upstream (the Debian path), so `shim/eigen3/` forwards
those names; `shim/vins_gnu.h` supplies `strdupa`, which bionic has no equivalent of; and
`shim/opencv_highgui_no_window.cpp` defines away the two highgui calls upstream's tracker makes,
since OpenCV for Android has no highgui and a phone has no window for it.

When the module is switched off, `VinsCore.available` is false, the VINS-Mono row in every settings
screen reads "The VINS-Mono core is not in this build", and the phone keeps ARCore or its 3DoF view.
Nothing else changes, and no APK gains a dependency it did not ask for.

Hardware requirements
---------------------
  - A rear camera the app may open while it renders (any phone NextVR runs on has one; BE does not
    bind a camera at all, so the mode is not offered there).  - An accelerometer and a gyroscope. Both are asked for, and both are read at their full rate on a
    thread of the tracker's own: every reading carries the newest of the other sensor, so the
    gyroscope — the one that carries the head's turn — is integrated at everything it gives. The
    gyroscope alone gives no scale, the accelerometer alone drifts at once. `SixDofSupport.hasImu`
    checks it and the settings row says
    "Needs a gyroscope and an accelerometer" when one is missing.
  - No ARCore, no Google Play Services for AR, no depth sensor, no external tracking: that is the
    point of the mode.
  - Only in the Full edition. The front end is OpenCV over every frame; Lite leaves per-frame vision
    out by design (it also leaves out ArUco markers and the depth network for the same reason).

Limits found while putting it in
--------------------------------
  1. No room model. VINS-Mono tracks features, not surfaces: with this mode there is no floor grid, no
     table for the keyboard to lie on, and no walls for room physics. `SixDof.supportsRoomScan` is the
     flag the home and the settings read, and the room-scan page says so instead of showing an empty list.
  2. No loop closure. Upstream's pose graph (`pose_graph/`, with a 58 MB BRIEF vocabulary) is not built,
     so nothing corrects the drift of a long walk: return to where you started after a few minutes and
     "there" is a few centimetres away. Recentering (a tap) restarts the window where the head is.
  3. Intrinsics are estimated, not measured. Android does publish a camera's focal length and sensor
     size, and NextVR derives a pinhole model from them; where a phone carries a factory lens
     calibration it also reports distortion coefficients (LENS_DISTORTION with a calibration
     priority above UNPROCESSED), and those are written into the config too — zeros on the many
     phones that report none. The crop CameraX chooses is not always the one the characteristics
     describe, so the optical centre is taken as the middle of the frame. A mis-set focal length is
     mostly a wrong *scale*: the room is a bit bigger or smaller than it is. The camera-to-IMU
     rotation is derived from the sensor's mount angle (VinsExtrinsics, checked by a unit test):
     the estimator refines it online, but a starting guess half a turn off about the optical axis
     is one its visual-inertial alignment cannot walk back from, so the axes are derived, not
     guessed. The file it all comes from is `files/vins/vins_config.yaml`, rewritten from the frame
     size and the characteristics at every start; a phone that is properly calibrated can keep its
     own numbers there for the tracker to find.
  4. Camera and IMU clocks are not one clock. The frame's exposure stamp is moved onto the clock the
     sensors read are stamped with, and the rest is left to VINS-Mono's online temporal calibration
     (`estimate_td: 1`), which is what it exists for.
  5. The analysis stream is asked for 640×480 while VINS-Mono runs, which is also the picture the
     passthrough shows and the hands are read from: the room is followed better at a modest size than
     the phone is drawn in high definition. On a weak phone the home may render at fewer frames a
     second — the estimator shares those cores with the render and the hand tracker.
  6. It needs to see. Turning off the lights, a blank wall or a fast swing loses the room; the home
     notices within a second (the pose's age), shows its neck model, and takes the position back when
     the tracker finds the walls again.
  7. A game holds the camera. While an OpenXR game runs, NextVR's tracking service has the camera and
     the home gets no frames, so VINS-Mono loses the room after a second and the home falls back to its
     neck model until the game is put down. ARCore is in the same position; nothing here is worse.
  8. GPL-3.0. Read the note at the end of UPSTREAM.txt: an APK with this module in it carries
     copyleft obligations for the whole app. `-Pvins.enabled=false` builds without them.
