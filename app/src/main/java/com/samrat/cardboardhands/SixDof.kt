package com.samrat.cardboardhands

import android.app.Activity
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.atan2

/**
 * Where the headset is in the room, whoever worked it out.
 *
 * The home asks for [copyPosition] (and, for the hands, a camera frame from [update]) and never
 * learns which backend answered: ARCore through Google Play Services for AR ([ArTracker]), or no
 * positional tracker. Rotation always comes from [HeadTracker]; a 6DoF tracker only adds position in
 * the head tracker's world, so windows stay put while the user moves around them.
 *
 * [VrHomeActivity] closes the previous tracker before starting another. ARCore owns the camera while
 * active; otherwise CameraX supplies the passthrough and hand-tracking frames — see [ownsCamera].
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

    // --- room geometry -------------------------------------------------------------------------
    //
    // A room scan needs a backend that detects surfaces. ARCore fits planes and identifies floors,
    // tables and walls; the capability lives here so the home and settings can ask the active tracker.

    /** What the room scan found in the home's world: floor, table, walls, drawn as a grid. */
    val surfaces: List<RoomScan.Surface> get() = emptyList()

    /** Plane counts for the room-scan screen. */
    val horizontalPlanes: Int get() = 0
    val verticalPlanes: Int get() = 0

    /** True when a room scan (planes, a table) is what this backend can see at all. */
    val supportsRoomScan: Boolean get() = false

    /** True while the tracker owns the camera instead of using the home's CameraX stream. */
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
 * Which 6DoF backends this phone can run. Kept apart from the tracker itself so the settings screens
 * (the phone's and the VR one) and the home agree on what is selectable without starting it to find out.
 */
object SixDofSupport {

    /** What the mode selector answers for one of the available modes. */
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
     * The best 6DoF this phone has, used as the default before the user ever chose: ARCore when
     * available, otherwise rotation-only tracking.
     */
    fun bestMode(context: Context): Settings.SixDofMode = when (arCore(context)) {
        Availability.READY, Availability.CHECKING -> Settings.SixDofMode.ARCORE
        Availability.MISSING -> Settings.SixDofMode.NONE
    }

    fun availability(context: Context, mode: Settings.SixDofMode): Availability = when (mode) {
        Settings.SixDofMode.NONE -> Availability.READY
        Settings.SixDofMode.ARCORE -> arCore(context)
    }

    /** Why a mode cannot be picked, in one line for the settings screen; null when it can. */
    fun unavailableReason(context: Context, mode: Settings.SixDofMode): String? = when (mode) {
        Settings.SixDofMode.NONE -> null
        Settings.SixDofMode.ARCORE -> when {
            BuildConfig.LITE -> "Only in NextVR Full"
            availability(context, mode) == Availability.CHECKING -> "Checking Google Play Services for AR…"
            else -> "No Google Play Services for AR on this phone"
        }
    }

    /**
     * Starts the tracker for [mode], or returns null when it cannot start (then the home stays 3DoF
     * with its neck model, exactly as before).
     */
    fun create(activity: Activity, mode: Settings.SixDofMode): SixDof? = when (mode) {
        Settings.SixDofMode.NONE -> null
        Settings.SixDofMode.ARCORE -> if (BuildConfig.LITE) null else ArTracker.create(activity)
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
 * The filter's bandwidth is set by what it is fed, not by the hands: ARCore supplies an already-filtered
 * pose, so this only has to take the edge off. At a 1 m/s walk the old settings (1 Hz floor, 2.5 speed
 * gain) left the head **56 mm behind where it had walked**, measured by `walkingForwardDoesNotLagTheHead`;
 * the numbers below leave it 18 mm, which is inside what a headset shows. That gap is the difference
 * between "my movement carried" and "my movement was weakened": the world visibly drags behind the
 * head, and head-bob at 1.5-2 Hz comes out attenuated on top of it.
 *
 * [update] wants the camera-to-world transform in the head tracker's own convention (y up, straight
 * ahead along -z); ARCore's `displayOrientedPose` is already that way.
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

    /** Makes the current spot the centre of the room again (with the head tracker's recenter). */
    fun reset() {
        origin = null
        alignYaw = Float.NaN
        hasLast = false
        smooth.forEach { it.reset() }
        position.fill(0f)
    }

    /**
     * One step. [cameraToWorld] is the tracker's camera pose in the head's convention, [sensorHead]
     * the head tracker's rotation, and [timestampNs] the frame's time (for the filter's rate).
     */
    fun update(cameraToWorld: FloatArray, sensorHead: FloatArray, timestampNs: Long) {
        // Both worlds have gravity along y; only the heading differs. Follow it slowly.
        val yawTracker = atan2(cameraToWorld[8], cameraToWorld[10])
        val yawSensor = atan2(sensorHead[8], sensorHead[10])
        val delta = wrap(yawSensor - yawTracker)
        alignYaw = if (alignYaw.isNaN()) delta else alignYaw + wrap(delta - alignYaw) * .05f
        val start = origin ?: floatArrayOf(cameraToWorld[12], cameraToWorld[13], cameraToWorld[14]).also { origin = it }
        if (hasLast) {
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
        synchronized(position) {
            position[0] = smooth[0].filter(c * dx + s * dz, timestampNs)
            position[1] = smooth[1].filter(dy, timestampNs)
            position[2] = smooth[2].filter(-s * dx + c * dz, timestampNs)
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
    }
}
