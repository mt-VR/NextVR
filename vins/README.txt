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
`vins/build` and compiled there. Point them somewhere else for an offline build:

    ./gradlew -Pvins.cmakeArgs="-DVINS_EIGEN_DIR=/opt/eigen -DVINS_CERES_DIR=/opt/ceres" …

When the module is switched off, `VinsCore.available` is false, the VINS-Mono row in every settings
screen reads "The VINS-Mono core is not in this build", and the phone keeps ARCore or its 3DoF view.
Nothing else changes, and no APK gains a dependency it did not ask for.

Hardware requirements
---------------------
  - A rear camera the app may open while it renders (any phone NextVR runs on has one; BE does not
    bind a camera at all, so the mode is not offered there).
  - An accelerometer and a gyroscope. Both are asked for: the gyroscope alone gives no scale, the
    accelerometer alone drifts at once. `SixDofSupport.hasImu` checks it and the settings row says
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
     size, and NextVR derives a pinhole model from them (and the lens distortion profile where the
     platform has one) — but the crop CameraX chooses is not always the one the characteristics
     describe, so the optical centre is taken as the middle of the frame. A mis-set focal length is
     mostly a wrong *scale*: the room is a bit bigger or smaller than it is. The file it comes from is
     `files/vins/vins_config.yaml`, rewritten from the frame size and the characteristics at every
     start; a phone that is properly calibrated can keep its own numbers there for the tracker to find.
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
