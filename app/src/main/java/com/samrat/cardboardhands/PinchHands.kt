package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.samrat.orangehanding.FusionHands

/** MediaPipe's landmarks for one hand. */
private const val POINTS = 21

/**
 * Hand tracking for the VR home: where the thumb and the index finger meet, and whether the two
 * have closed on something.
 *
 * This is all the home asks of a camera now. The old set — a ray from the hand to aim with, a fist
 * that took hold of a window and dragged it about, a menu that opened on the palm of the hand, a
 * fingertip that pushed a window's surface — is gone; a hand here is a pinch and a place. The place
 * is the point between the thumb tip and the index tip, read straight off the picture: hold those
 * two fingers over a tile of the dock and close them, and whatever is under them opens.
 *
 * The hands themselves still come from MediaPipe through OrangeHanding (YOLO11-pose finds the
 * wrists MediaPipe misses), the detector the rest of PhoneXR uses.
 */
class PinchHands(context: Context, useGpu: Boolean = false) : AutoCloseable {

    /**
     * One hand in a frame. [x] and [y] are where the thumb tip and the index tip meet, on the
     * camera picture (0..1, y growing downwards), already laid on the eye's view.
     */
    class Hand(
        /** True for the user's left hand. */
        val left: Boolean,
        val x: Float,
        val y: Float,
        /** The two fingers are together. */
        val pinching: Boolean,
        /** They came together on this very frame: the pinch begins here. */
        val began: Boolean,
        /** How far the index fingertip is from the eyes (metres); 0 when it cannot be told. */
        val depth: Float,
        /** The whole reading of the hand, for the first-start setup's scan. */
        val shape: HandGestures.Shape,
    )

    /** What one camera frame holds. */
    class Frame(
        /** 21 (x, y, z) triples per hand, as the home draws the hands over the view. */
        val points: List<FloatArray>,
        val hands: List<Hand>,
        /** When the frame was taken (nanoseconds), so the head pose can be looked up for it. */
        val timeNs: Long,
    ) {
        /** The hands in the shape the first-start setup asks for them. */
        val shapes: List<Pair<Boolean, HandGestures.Shape>> get() = hands.map { it.left to it.shape }
    }

    private val fusion = FusionHands(context, useGpu, yoloEveryMs = if (BuildConfig.LITE) 500L else 250L)
    /** Kept as the application context: a latch outlives the screen it was made on. */
    private val app = context.applicationContext
    private val smoothers = HashMap<String, LandmarkSmoother>()
    private val latches = HashMap<String, HandGestures.PinchLatch>()

    /**
     * One frame. [map] (left, top, width, height) lays the landmarks of an ARCore frame, which is
     * upright, on the eye's view; [scaleX] and [scaleY] are the view's tangents, which is what the
     * fingertip's distance is measured against.
     */
    fun detect(
        bitmap: Bitmap, timestampMs: Long, rotationDegrees: Int = 0,
        map: FloatArray? = null, scaleX: Float = 16f / 9f, scaleY: Float = 1f,
    ): Frame {
        val result = fusion.detect(bitmap, timestampMs, rotationDegrees)
        val timeNs = result.timestampMs() * 1_000_000L
        fun label(index: Int) = result.handednesses().getOrNull(index)?.firstOrNull()?.categoryName() ?: "hand$index"
        val raw = result.landmarks().map { points ->
            if (map == null) points
            else points.map { NormalizedLandmark.create(map[0] + it.x() * map[2], map[1] + it.y() * map[3], it.z()) }
        }
        // Every point of every hand through a One Euro filter: steady while the hand rests, no lag
        // when it moves — so the silhouette over the view and the pinch point both stop trembling.
        val landmarks = raw.mapIndexed { index, points ->
            if (points.size < POINTS) return@mapIndexed points
            smoothers.getOrPut(label(index)) { LandmarkSmoother() }.smooth(points, timeNs)
        }
        // A hand that has left the picture starts afresh when it comes back, instead of sliding in
        // from the place it was last seen at.
        val present = raw.indices.map { label(it) }.toSet()
        smoothers.keys.retainAll(present)
        latches.keys.retainAll(present)

        val hands = ArrayList<Hand>()
        for ((index, points) in landmarks.withIndex()) {
            if (points.size < POINTS) continue
            val name = label(index)
            // MediaPipe's handedness assumes a mirrored selfie picture; the caller has undone that
            // already, so a hand it names "Right" is the user's left.
            val left = name.equals("Right", ignoreCase = true)
            val shape = HandGestures.shape(points, left)
            val depth = result.worldLandmarks().getOrNull(index)
                ?.let { HandGestures.tipDepth(points, it, scaleX, scaleY) } ?: 0f
            // Closed and open have their own distances, so a hand hovering near a pinch does not
            // flicker; the distances are this user's own, measured in the setup.
            val latch = latches.getOrPut(name) { HandProfile.latch(app) }
            val before = latch.pinching
            val pinching = latch.update(shape)
            hands += Hand(left, shape.pinchX, shape.pinchY, pinching, pinching && !before, depth, shape)
        }
        val drawn = landmarks.filter { it.size >= POINTS }.map { points ->
            FloatArray(POINTS * 3) { i ->
                when (i % 3) { 0 -> points[i / 3].x(); 1 -> points[i / 3].y(); else -> points[i / 3].z() }
            }
        }
        return Frame(drawn, hands, timeNs)
    }

    override fun close() = fusion.close()

    /** One Euro filters for the 21 points of one hand (x and y on the view, z relative depth). */
    private class LandmarkSmoother {
        private val filters = Array(POINTS * 3) { i ->
            if (i % 3 == 2) HandGestures.OneEuro(minCutoff = .8f, beta = 1.2f)
            else HandGestures.OneEuro(minCutoff = 1.4f, beta = 12f, deadZone = .0008f)
        }

        fun smooth(points: List<NormalizedLandmark>, timeNs: Long): List<NormalizedLandmark> = points.mapIndexed { i, p ->
            if (i >= POINTS) p
            else NormalizedLandmark.create(
                filters[i * 3].filter(p.x(), timeNs),
                filters[i * 3 + 1].filter(p.y(), timeNs),
                filters[i * 3 + 2].filter(p.z(), timeNs),
            )
        }
    }
}
