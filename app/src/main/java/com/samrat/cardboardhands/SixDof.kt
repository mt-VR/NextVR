package com.samrat.cardboardhands

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.atan2

/**
 * Where the headset is in the room, whoever worked it out.
 *
 * The home asks for [copyPosition] (and, for the hands, a camera frame from [update]) and never
 * learns which backend answered: ARCore through Google Play Services for AR ([ArTracker]),
 * VINS-Mono through the camera and the IMU ([VinsTracker]), or nothing at all. Rotation always comes from the
 * [HeadTracker]; a 6DoF tracker only adds the position, in the head tracker's own world, so windows
 * stay put while the user walks around them.
 *
 * Exactly one tracker runs at a time: [VrHomeActivity] closes the previous one before starting the
 * next, and the two camera users (ARCore, and VINS-Mono through CameraX) never hold the camera at
 * once — see [ownsCamera].
 */
interface SixDof {

    /** True while the room is followed and the position can be trusted. */
    val tracking: Boolean

    /** One sentence for the settings screen: what is tracking, and what it is waiting for. */
    fun statusText(): String

    /** Head position in the head tracker's world, metres, relative to where tracking started. */
    fun copyPosition(out: FloatArray)

    /**
     * One step of the tracker, on the GL thread. [sensorHead] is the head tracker's rotation, used
     * to turn this tracker's world into the head's. Returns a CPU camera frame for the hands when
     * [wantImage] and one is ready — a thunk, so the slow colour conversion happens off the GL thread.
     */
    fun update(sensorHead: FloatArray, wantImage: Boolean): (() -> ArTracker.CameraFrame?)? = null

    /** The camera's projection for one eye's viewport, so virtual things line up with passthrough. */
    val projection: FloatArray? get() = null

    /** Texture coordinates of the passthrough quad (per eye), when this tracker draws the camera. */
    val passthroughUv: FloatBuffer? get() = null

    // --- the room scan, which is ARCore's alone ---------------------------------------------------------------
    //
    // A room scan needs a backend that knows what a *surface* is: ARCore fits planes to the room and
    // says which are floors, tables and walls. VINS-Mono tracks features, not geometry, so it can
    // follow where the head is but never what the walls are — it therefore answers `false` here and
    // the defaults below stand for it. The capability is declared on the shared interface so the home
    // and the settings screen can ask either tracker the same question and branch on the answer.

    /** What the room scan found in the home's world: floor, table, walls, drawn as a grid. */
    val surfaces: List<RoomScan.Surface> get() = emptyList()

    /** Plane counts for the room-scan screen. */
    val horizontalPlanes: Int get() = 0
    val verticalPlanes: Int get() = 0

    /** True when a room scan (planes, a table) is what this backend can see at all. */
    val supportsRoomScan: Boolean get() = false

    /**
     * The frame size a tracker that borrows the camera would like: the size its calibration was
     * written for. Null leaves the home's usual passthrough resolution alone.
     */
    val wantedFrame: Pair<Int, Int>? get() = null

    /**
     * True when opening the camera is this tracker's job. ARCore takes the camera for itself and
     * hands back both passthrough and the frames for the hands; VINS-Mono reads the frames CameraX
     * already provides, so the home keeps its usual 3DoF camera and only adds the position.
     */
    val ownsCamera: Boolean get() = false

    /** The screen came back: the sensors and the camera may have been taken away and given back. */
    fun resume(): Boolean = true

    /** The screen went away: stop taking the sensors and the camera, stay ready to [resume]. */
    fun pause() = Unit

    /** Makes the current spot the centre of the room again (with the head tracker's recenter). */
    fun recenter() = Unit

    /** Releases everything the tracker holds; the tracker is not used afterwards. */
    fun close() = Unit

    /** GL setup for a tracker that draws the camera itself: the external texture and the eye size. */
    fun attachTexture(texture: Int) = Unit

    fun setDisplay(rotation: Int, eyeWidth: Int, height: Int) = Unit
}

/**
 * A 6DoF tracker that wants the raw camera frames instead of opening the camera itself: the home's
 * CameraX analysis stream hands over the grayscale plane of each frame before it is recycled.
 * VINS-Mono uses this to see the room without owning the camera.
 */
interface SixDofCameraFeed {
    /**
     * The actual CameraX camera selected for this stream. A visual-inertial tracker must calibrate
     * against this lens, not whichever back camera happens to have the largest sensor in CameraManager.
     */
    fun onCameraSelected(cameraId: String?) = Unit

    /**
     * One frame's Y plane, as the sensor reads it (unrotated), on the analysis thread. The tracker
     * copies what it needs and returns; [timestampNs] is CameraX's own stamp for the exposure.
     */
    fun onGrayFrame(gray: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, timestampNs: Long)
}

/**
 * Which 6DoF backends this phone can run. Kept apart from the trackers themselves so the settings
 * screens (the phone's and the VR one) and the home all agree on what is selectable, without ever
 * starting a tracker to find out.
 */
object SixDofSupport {

    /** What the mode selector answers for one of the three modes. */
    enum class Availability { READY, CHECKING, MISSING }

    /** ARCore; the install prompt is only raised where the caller really starts a session. */
    fun arCore(context: Context, requestInstall: Boolean = false): Availability {
        if (BuildConfig.LITE) return Availability.MISSING
        return when (ArTracker.availability(context, requestInstall)) {
            ArTracker.Availability.READY -> Availability.READY
            ArTracker.Availability.CHECKING -> Availability.CHECKING
            ArTracker.Availability.MISSING -> Availability.MISSING
        }
    }

    /**
     * VINS-Mono: the camera and both halves of an IMU, and the native core built into this APK.
     * ARCore plays no part in it — that is the whole point of the mode, so a phone without Google
     * Play Services for AR (Huawei, stripped ROMs, the Lite editions' usual hardware) still walks.
     */
    fun vinsMono(context: Context): Availability {
        if (!VinsTracker.available) return Availability.MISSING
        if (BuildConfig.BE) return Availability.MISSING
        if (!hasImu(context)) return Availability.MISSING
        if (!hasCamera(context)) return Availability.MISSING
        return Availability.READY
    }

    /** Accelerometer + gyroscope + a camera: what a visual-inertial tracker needs. */
    fun hasImu(context: Context): Boolean {
        val sensors = context.getSystemService(SensorManager::class.java) ?: return false
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        // The raw accelerometer, not the linear one: gravity is what turns the estimator's
        // arbitrary scale into metres.
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
        return gyro != null && accel != null
    }

    /** A camera at all: without one neither backend can see the room. */
    fun hasCamera(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)

    /**
     * The best 6DoF this phone has, used as the default before the user ever chose: ARCore where it
     * is installed, VINS-Mono where the camera and IMU are there to carry it, nothing where neither is.
     */
    fun bestMode(context: Context): Settings.SixDofMode = when {
        arCore(context) == Availability.READY -> Settings.SixDofMode.ARCORE
        vinsMono(context) == Availability.READY -> Settings.SixDofMode.VINS_MONO
        else -> Settings.SixDofMode.NONE
    }

    fun availability(context: Context, mode: Settings.SixDofMode): Availability = when (mode) {
        Settings.SixDofMode.NONE -> Availability.READY
        Settings.SixDofMode.ARCORE -> arCore(context)
        Settings.SixDofMode.VINS_MONO -> vinsMono(context)
    }

    /** Why a mode cannot be picked, in one line for the settings screen; null when it can. */
    fun unavailableReason(context: Context, mode: Settings.SixDofMode): String? = when (mode) {
        Settings.SixDofMode.NONE -> null
        Settings.SixDofMode.ARCORE -> when {
            BuildConfig.LITE -> "Only in NextVR Full"
            availability(context, mode) == Availability.CHECKING -> "Checking Google Play Services for AR…"
            else -> "No Google Play Services for AR on this phone"
        }

        Settings.SixDofMode.VINS_MONO -> when {
            BuildConfig.LITE -> "Only in NextVR Full"
            BuildConfig.BE -> "This edition has no camera"
            !VinsTracker.available -> "The VINS-Mono core is not in this build"
            !hasImu(context) -> "Needs a gyroscope and an accelerometer"
            !hasCamera(context) -> "Needs a camera"
            else -> null
        }
    }

    /**
     * Starts the tracker for [mode], or returns null when it cannot start (then the home stays 3DoF
     * with its neck model, exactly as before). Only ever one tracker at a time.
     */
    fun create(activity: Activity, mode: Settings.SixDofMode): SixDof? = when (mode) {
        Settings.SixDofMode.NONE -> null
        Settings.SixDofMode.ARCORE -> if (BuildConfig.LITE) null else ArTracker.create(activity)
        Settings.SixDofMode.VINS_MONO -> VinsTracker.create(activity)
    }
}

/**
 * The part every 6DoF backend needs and none of them should re-invent: turning "where the camera is
 * in *my* world" into "where the head is in the head tracker's world".
 *
 * Both worlds hang from gravity and differ only by the heading each one started with, so the yaw is
 * followed slowly (a relocalisation must not spin the room), the spot where tracking began becomes the
 * centre of the room, a jump of half a metre in one frame is taken as the tracker correcting itself
 * rather than the user walking, and the rest is smoothed with the same One Euro filter the hands use:
 * calm while the head rests, quick when it moves.
 *
 * The filter's bandwidth is set by what it is fed, not by the hands: a tracker's position arrives
 * already smoothed and already at sensor rate — VINS-Mono hands over its IMU-propagated pose at
 * 200 Hz, ARCore a pose it has filtered itself. So this only has to take the edge off, and it used
 * to be set for a noisier signal than either one is. At a 1 m/s walk the old settings (1 Hz floor,
 * 2.5 speed gain) left the head **56 mm behind where it had walked**, measured by
 * `walkingForwardDoesNotLagTheHead`; the numbers below leave it 18 mm, which is inside what a
 * headset shows. That gap is the whole difference between "my movement carried" and "my movement
 * was weakened": the world visibly drags behind the head, and the head-bob at 1.5-2 Hz comes out
 * attenuated on top of it.
 *
 * [update] wants the camera-to-world transform in the head tracker's own convention (y up, straight
 * ahead along -z); ARCore's `displayOrientedPose` is already that way, and VINS-Mono's z-up world is
 * turned before it gets here.
 */
class SixDofAligner {
    /** Head position in the head tracker's world, metres, relative to where tracking started. */
    val position = FloatArray(3)
    /** Where tracking began, in the tracker's world; null until the first tracked frame. */
    var origin: FloatArray? = null
        private set
    /** The heading of the tracker's world, measured in the head's; NaN before the first fix. */
    var alignYaw = Float.NaN
        private set
    private val smooth = Array(3) {
        HandGestures.OneEuro(minCutoff = SMOOTH_CUTOFF_HZ, beta = SMOOTH_BETA, deadZone = .003f)
    }
    private val lastRaw = FloatArray(3)
    private var hasLast = false
    /** When did the last non-trivial motion happen, for standing-still drift damping. */
    private var lastMoveNs = 0L
    /** Timestamp of the previous filter call, for per-frame dt. */
    private var lastFrameNs = 0L
    /** EWMA of |velocity| per axis, used to tell a drifting head from a standing one. */
    private val ewmaSpeed = FloatArray(3)

    /** Makes the current spot the centre of the room again (with the head tracker's recenter). */
    fun reset() {
        origin = null
        alignYaw = Float.NaN
        hasLast = false
        smooth.forEach { it.reset() }
        position.fill(0f)
        ewmaSpeed.fill(0f)
        lastMoveNs = 0L
        lastFrameNs = 0L
    }

    /**
     * One step. [cameraToWorld] is the tracker's camera pose in the head's convention, [sensorHead]
     * the head tracker's rotation, and [timestampNs] the frame's time (for the filter's rate).
     */
    fun update(
        cameraToWorld: FloatArray,
        sensorHead: FloatArray,
        timestampNs: Long,
        preservePosition: Boolean = false,
    ) {
        // Both worlds have gravity along y; only the heading differs. Follow it slowly — the
        // head tracker's yaw (from the gyro) is the authority on short timescales and the VIO
        // heading drifts slowly (monocular VINS cannot observe global yaw). A faster pull here
        // would let that slow drift swing the room on every fix, which reads as drift even when
        // translation is correct. The 3% factor gives a ~30 s time constant, fast enough to
        // correct an initial heading error and slow enough not to chase estimator noise.
        val yawTracker = atan2(cameraToWorld[8], cameraToWorld[10])
        val yawSensor = atan2(sensorHead[8], sensorHead[10])
        val delta = wrap(yawSensor - yawTracker)
        val yawAlpha = if (alignYaw.isNaN()) 1.0f else YAW_ALIGN_ALPHA
        alignYaw = if (alignYaw.isNaN()) delta else alignYaw + wrap(delta - alignYaw) * yawAlpha
        val hadOrigin = origin != null
        val start = origin ?: floatArrayOf(cameraToWorld[12], cameraToWorld[13], cameraToWorld[14]).also { origin = it }
        if (preservePosition && hadOrigin) {
            // VINS can integrate translation poorly while visual features are missing. On reacquisition,
            // keep the last trusted head position and make the recovered estimate its new origin; this
            // drops the unobserved excursion without snapping the room or moving the room's origin.
            val c = kotlin.math.cos(alignYaw)
            val s = kotlin.math.sin(alignYaw)
            synchronized(position) {
                val dx = c * position[0] - s * position[2]
                val dz = s * position[0] + c * position[2]
                start[0] = cameraToWorld[12] - dx
                start[1] = cameraToWorld[13] - position[1]
                start[2] = cameraToWorld[14] - dz
            }
        } else if (hasLast) {
            // A tracker that snaps to a corrected map moves the origin with it, so the room does not lurch.
            val jx = cameraToWorld[12] - lastRaw[0]
            val jy = cameraToWorld[13] - lastRaw[1]
            val jz = cameraToWorld[14] - lastRaw[2]
            if (jx * jx + jy * jy + jz * jz > .25f) { start[0] += jx; start[1] += jy; start[2] += jz }
        }
        lastRaw[0] = cameraToWorld[12]; lastRaw[1] = cameraToWorld[13]; lastRaw[2] = cameraToWorld[14]
        hasLast = true
        val dx = cameraToWorld[12] - start[0]
        val dy = cameraToWorld[13] - start[1]
        val dz = cameraToWorld[14] - start[2]
        val c = kotlin.math.cos(alignYaw)
        val s = kotlin.math.sin(alignYaw)

        // Transform into head-tracker coordinates.
        val hx0 = c * dx + s * dz
        val hy0 = dy
        val hz0 = -s * dx + c * dz

        // Apply One-Euro smoothing.
        val fx = smooth[0].filter(hx0, timestampNs)
        val fy = smooth[1].filter(hy0, timestampNs)
        val fz = smooth[2].filter(hz0, timestampNs)

        // Track how fast the smoothed position is moving per axis. When the head has been
        // essentially still for several seconds, gently leak the reported position back toward
        // the origin with a long time constant. This slowly dissolves any accumulated bias
        // drift that shows up as a sub-cm/s walk while standing — the same idea as
        // SensorSixDof returning its body home. It does NOT fight real motion: as soon as the
        // smoothed speed rises above STILL_SPEED the leak turns off and lastMoveNs is refreshed,
        // so walking a metre and stopping leaves the head a metre from origin.
        val dtFilter = if (lastFrameNs == 0L) 0f else ((timestampNs - lastFrameNs) * 1e-9f).coerceIn(0f, 0.1f)
        if (lastFrameNs == 0L) lastMoveNs = timestampNs
        lastFrameNs = timestampNs
        var moving = false
        val px = position[0]; val py = position[1]; val pz = position[2]
        for (i in 0..2) {
            val prev = when (i) { 0 -> px; 1 -> py; else -> pz }
            val curr = when (i) { 0 -> fx; 1 -> fy; else -> fz }
            val instSpeed = if (dtFilter > 0f) kotlin.math.abs(curr - prev) / dtFilter else 0f
            ewmaSpeed[i] = ewmaSpeed[i] * 0.95f + instSpeed * 0.05f
            if (ewmaSpeed[i] > STILL_SPEED) moving = true
        }
        if (moving) lastMoveNs = timestampNs
        val stillMs = (timestampNs - lastMoveNs) / 1_000_000L
        // After the warm-up, leak toward zero with a ~5 s time constant (alpha ≈ dt/5). That is
        // slow enough to be invisible while the user stands, and fast enough that any bias-drift
        // offset built up over a minute decays back to zero within a few seconds of stillness.
        val leak = if (stillMs > STILL_WARMUP_MS && dtFilter > 0f) {
            val ramp = ((stillMs - STILL_WARMUP_MS).toFloat() / (STILL_DAMP_MS - STILL_WARMUP_MS)).coerceIn(0f, 1f)
            ramp * (dtFilter / STILL_LEAK_TIME_S)
        } else 0f

        synchronized(position) {
            position[0] = fx * (1f - leak)
            position[1] = fy * (1f - leak)
            position[2] = fz * (1f - leak)
        }
    }

    /** A point of the tracker's world (a scanned wall, say) in the head's world, metres. */
    fun transformPoint(x: Float, y: Float, z: Float): FloatArray {
        val start = origin ?: return floatArrayOf(x, y, z)
        val c = kotlin.math.cos(alignYaw); val s = kotlin.math.sin(alignYaw)
        val dx = x - start[0]; val dy = y - start[1]; val dz = z - start[2]
        return floatArrayOf(c * dx + s * dz, dy, -s * dx + c * dz)
    }

    private fun wrap(angle: Float): Float {
        var a = angle
        while (a > Math.PI) a -= (2 * Math.PI).toFloat()
        while (a < -Math.PI) a += (2 * Math.PI).toFloat()
        return a
    }

    private companion object {
        /**
         * The position filter's floor and its speed gain, in One Euro's terms: the cutoff is
         * `SMOOTH_CUTOFF_HZ + SMOOTH_BETA * |d(position)/dt|`, so a standing head is held at 3 Hz and
         * a 1 m/s walk opens it to 13 Hz. `walkingForwardDoesNotLagTheHead` measures what that costs:
         * 18 mm of lag at 1 m/s, against the 56 mm the previous 1 Hz / 2.5 gave.
         */
        const val SMOOTH_CUTOFF_HZ = 3.0f
        const val SMOOTH_BETA = 10.0f
        /**
         * How quickly the tracker's heading is pulled toward the head tracker's. The head tracker's
         * yaw (gyro) is short-term-perfect; the VIO drifts slowly in yaw because global heading is
         * unobservable for a monocular camera-IMU system. 3% per new visual frame (≈15 Hz) gives a
         * ~30 s time constant — fast enough to erase a startup heading error within a few seconds,
         * slow enough that estimator noise is rejected and the visual yaw drift is dissolved
         * instead of rotating the room.
         */
        const val YAW_ALIGN_ALPHA = 0.03f
        /** Below this smoothed speed (m/s), the head counts as standing still for drift damping. */
        const val STILL_SPEED = 0.02f
        /** After this many ms of standing still, the standing-drift return starts ramping in. */
        const val STILL_WARMUP_MS = 3_000L
        /** After this many ms, full damping is applied. */
        const val STILL_DAMP_MS = 8_000L
        /**
         * Time constant (seconds) for the leak back to the origin once the head is judged still.
         * 5 s means any position error decays by ~1 - e^(-t/5): a 10 cm drift is 3.7 cm after 5 s,
         * invisible while the user stands and fast enough to dissolve accumulated bias walk.
         */
        const val STILL_LEAK_TIME_S = 5.0f
    }
}
