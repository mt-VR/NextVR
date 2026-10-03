package com.phonexr.sdk

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Reads the PhoneXR stream: hand positions, gestures and Joy-Con buttons.
 *
 * A game gets ordinary input (controller poses, buttons) through OpenXR. This class is for when
 * you want the raw data: to show a palm, for example, or to build your own gesture.
 *
 * The data arrives over UDP on 127.0.0.1:42425 while tracking runs in PhoneXR.
 * One client holds the port: if the game sees no data, another app is already reading it.
 */
class PhoneXRInput(port: Int = 42425) : AutoCloseable {
    data class Hand(
        /** The hand is visible to the camera or a Joy-Con of that side is connected. */
        val present: Boolean = false,
        /** Fist, index finger, thumb. Always false in "hands only" mode. */
        val fist: Boolean = false,
        val index: Boolean = false,
        val thumb: Boolean = false,
        /** The position of the palm in the frame: x and y from 0 to 1, z is the closeness to the camera (1 is the closest). */
        val x: Float = .5f,
        val y: Float = .5f,
        val z: Float = .5f,
        /** Rotation from the Joy-Con when its gyro is available. Otherwise the identity quaternion. */
        val qx: Float = 0f,
        val qy: Float = 0f,
        val qz: Float = 0f,
        val qw: Float = 1f,
        /** A set of Button bits: which Joy-Con buttons are pressed. */
        val buttons: Int = 0,
        /** The Joy-Con stick, from -1 to 1 (x to the right, y up). Always 0 in PH4. */
        val stickX: Float = 0f,
        val stickY: Float = 0f,
        /** The pinch (thumb and index together) and the palm to the face. Always false in PH4. */
        val pinch: Boolean = false,
        val palmToFace: Boolean = false,
        /** Continuous bend of each finger: 0 is straight, 1 is fully curled. */
        val thumbCurl: Float = 0f,
        val indexCurl: Float = 0f,
        val middleCurl: Float = 0f,
        val ringCurl: Float = 0f,
        val pinkyCurl: Float = 0f,
    ) {
        fun isPressed(button: Button) = buttons and button.bit != 0
    }

    enum class Button(val bit: Int) {
        PRIMARY(1), SECONDARY(1 shl 1), TRIGGER(1 shl 2), SQUEEZE(1 shl 3),
        MENU(1 shl 4), STICK_CLICK(1 shl 5), SYSTEM(1 shl 6)
    }

    data class State(
        val left: Hand = Hand(),
        val right: Hand = Hand(),
        /** Whether positional tracking from the camera is enabled in the settings. */
        val sixDof: Boolean = true,
        /** "Hands only" mode: finger gestures press nothing. */
        val handsOnly: Boolean = false
    )

    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        soTimeout = 500
        bind(InetSocketAddress("127.0.0.1", port))
    }
    private val buffer = ByteArray(512)

    /** Waits for the next packet. Returns null when there was no data for half a second. */
    fun read(): State? {
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            parse(String(packet.data, 0, packet.length, Charsets.US_ASCII))
        } catch (_: Throwable) {
            null
        }
    }

    override fun close() = socket.close()

    private fun parse(message: String): State? {
        val parts = message.trim().split(' ')
        return when (parts.firstOrNull()) {
            "PH6" -> if (parts.size >= 44) parse6(parts.drop(1)) else null
            "PH5" -> if (parts.size >= 30) parse5(parts.drop(1)) else null
            "PH4" -> if (parts.size >= 26) parse4(parts.drop(1)) else null
            else -> null
        }
    }

    /** PH6: PH5 plus five continuous finger curls in every hand block. */
    private fun parse6(values: List<String>): State {
        fun flag(index: Int) = values.getOrNull(index)?.toIntOrNull() == 1
        fun hand(offset: Int, extra: Int) = Hand(
            present = values[offset].toInt() != 0,
            fist = values[offset + 1].toInt() != 0,
            index = values[offset + 2].toInt() != 0,
            thumb = values[offset + 3].toInt() != 0,
            x = values[offset + 4].toFloat(), y = values[offset + 5].toFloat(), z = values[offset + 6].toFloat(),
            qx = values[offset + 7].toFloat(), qy = values[offset + 8].toFloat(),
            qz = values[offset + 9].toFloat(), qw = values[offset + 10].toFloat(),
            buttons = values[offset + 11].toInt(), stickX = values[offset + 12].toFloat(), stickY = values[offset + 13].toFloat(),
            thumbCurl = values[offset + 14].toFloat().coerceIn(0f, 1f),
            indexCurl = values[offset + 15].toFloat().coerceIn(0f, 1f),
            middleCurl = values[offset + 16].toFloat().coerceIn(0f, 1f),
            ringCurl = values[offset + 17].toFloat().coerceIn(0f, 1f),
            pinkyCurl = values[offset + 18].toFloat().coerceIn(0f, 1f),
            pinch = flag(extra), palmToFace = flag(extra + 1),
        )
        val flags = values[38].toInt()
        return State(hand(0, 39), hand(19, 41), flags and 1 != 0, flags and 2 != 0)
    }

    /**
     * PH5: each hand also has the Joy-Con stick (14 values per hand), then the flags, then the pinch and
     * the palm to the face for the left and the right hand.
     */
    private fun parse5(values: List<String>): State {
        fun flag(index: Int) = values.getOrNull(index)?.toIntOrNull() == 1
        fun hand(offset: Int, extra: Int) = Hand(
            present = values[offset].toInt() != 0,
            fist = values[offset + 1].toInt() != 0,
            index = values[offset + 2].toInt() != 0,
            thumb = values[offset + 3].toInt() != 0,
            x = values[offset + 4].toFloat(),
            y = values[offset + 5].toFloat(),
            z = values[offset + 6].toFloat(),
            qx = values[offset + 7].toFloat(),
            qy = values[offset + 8].toFloat(),
            qz = values[offset + 9].toFloat(),
            qw = values[offset + 10].toFloat(),
            buttons = values[offset + 11].toInt(),
            stickX = values[offset + 12].toFloat(),
            stickY = values[offset + 13].toFloat(),
            pinch = flag(extra),
            palmToFace = flag(extra + 1)
        )
        val flags = values[28].toInt()
        return State(hand(0, 29), hand(14, 31), flags and 1 != 0, flags and 2 != 0)
    }

    private fun parse4(values: List<String>): State {
        fun hand(offset: Int) = Hand(
            present = values[offset].toInt() != 0,
            fist = values[offset + 1].toInt() != 0,
            index = values[offset + 2].toInt() != 0,
            thumb = values[offset + 3].toInt() != 0,
            x = values[offset + 4].toFloat(),
            y = values[offset + 5].toFloat(),
            z = values[offset + 6].toFloat(),
            qx = values[offset + 7].toFloat(),
            qy = values[offset + 8].toFloat(),
            qz = values[offset + 9].toFloat(),
            qw = values[offset + 10].toFloat(),
            buttons = values[offset + 11].toInt()
        )
        val flags = values[24].toInt()
        return State(hand(0), hand(12), flags and 1 != 0, flags and 2 != 0)
    }
}
