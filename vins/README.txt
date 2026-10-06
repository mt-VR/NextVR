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
     so nothing globally corrects drift on a long walk; the amount depends on the phone, lighting and
     motion, and can become noticeable before a session ends. The startup and weak-vision guards reduce
     bad initial poses and runaway recovery, but cannot replace loop closure. Recentering (a tap)
     restarts the window where the head is.
  3. Intrinsics are estimated, not measured. Android publishes a camera's focal length and sensor
     size, and NextVR derives a pinhole model from them; where the phone carries factory calibration,
     it reports distortion coefficients too. The config now uses the CameraX-selected lens rather than
     assuming the largest back sensor is the one streaming. When camera metadata is unavailable, the
     fallback uses equal fx/fy for square pixels (an earlier aspect-ratio multiplier made fy 25% too
     small at 640×480). The crop CameraX chooses is not always the one the characteristics describe,
     so the optical centre is still taken as the middle of the frame. A mis-set focal length is mostly
     a wrong *scale*: the room is a bit bigger or smaller than it is. The camera-to-IMU rotation is
     derived from the sensor's mount angle (VinsExtrinsics, checked by a unit test) and the estimator
     is told to trust it (`estimate_extrinsic: 0`), which is what a derived-from-first-principles
     rotation deserves. Estimating it online instead puts seven more free
     parameters into a Ceres solve that is already given only `max_solver_time` for an 11-frame
     window, on cores shared with the render loop; the solve is cut short, every pose is biased and
     the estimator does not leave INITIAL. Upstream's own EuRoC config ships `estimate_extrinsic: 0`
     for the same reason it trusts its own measured matrix. The file it all comes from is
     `files/vins/vins_config.yaml`, rewritten from the frame size and the characteristics at every
     start; a phone that is properly calibrated can keep its own numbers there for the tracker to
     find. The numbers that are not this phone's own are pinned by `VinsConfigTest`.
  4. There is no camera-to-IMU clock offset to find. `ImageInfo.timestamp` and `SensorEvent.timestamp`
     are both nanoseconds on `SystemClock.elapsedRealtimeNanos()`, so `estimate_td` is 0 and `td` is
     0.0. Switching the online temporal calibration on would add `para_Td` as a free parameter to
     the same short solve and replace every `ProjectionFactor` with a `ProjectionTdFactor`, which
     can only cost convergence.
  5. The analysis stream is asked for 640×480 while VINS-Mono runs, which is also the picture the
     passthrough shows and the hands are read from: the room is followed better at a modest size than
     the phone is drawn in high definition. On a weak phone the home may render at fewer frames a
     second — the estimator shares those cores with the render and the hand tracker.
     The `keyframe_parallax` written into the config is 4 px of median feature flow per frame, not
     upstream's 10. Upstream's figure assumes a hand-held camera waved in front of the user; below
     that threshold VINS-Mono's own `solveOdometry` throws good frames out of the window on purpose,
     and a camera on a headset sees far less translation than that.
  6. It needs to see. Turning off the lights, a blank wall or a fast swing loses the room; weak visual
     updates are gated until enough distinct frames with stable features return; implausible velocity
     and sudden position discontinuities are rejected even if the reported velocity looks small.
     A short feature dip does not flicker tracking off; when the gate does close, the last trusted
     position is held, and the recovered estimate is re-anchored to it rather than replaying
     unobserved translation as a room jump. If vision remains unavailable, the home notices within
     a second (the pose's age) and says so in Settings. It deliberately does not use the neck model,
     which would swing the whole scene around on every turn of the head.
  7. A game holds the camera. While an OpenXR game runs, NextVR's tracking service has the camera and
     the home gets no frames, so VINS-Mono loses the room after a second and the home holds its last
     pose until the game is put down. ARCore is in the same position; nothing here is worse.
  8. GPL-3.0. Read the note at the end of UPSTREAM.txt: an APK with this module in it carries
     copyleft obligations for the whole app. `-Pvins.enabled=false` builds without them.
