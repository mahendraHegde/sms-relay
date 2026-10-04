package dev.smsrelay.core

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Sits between the TLS socket and Paho's MQTT parser and refuses any packet whose declared length
 * exceeds [maxPacket]. Paho allocates a buffer of the declared size before reading the payload, so
 * without this a broker (or anyone controlling it) could make the phone allocate ~256 MiB with a
 * five-byte header. Our own envelopes are at most 64 KiB; an oversized packet drops the connection
 * (normal reconnect and backoff follow) instead of crashing the process.
 *
 * Tracks MQTT framing: one type byte, the remaining length (1 to 4 bytes, 7 bits each), then that
 * many payload bytes.
 */
class MqttLimitStream(input: InputStream, private val maxPacket: Int = DEFAULT_MAX) : FilterInputStream(input) {
    companion object {
        const val DEFAULT_MAX = 256 * 1024
    }

    private enum class State { TYPE, LENGTH, PAYLOAD }

    private var state = State.TYPE
    private var length = 0
    private var shift = 0
    private var lengthBytes = 0
    private var left = 0

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) advance(b)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (state != State.PAYLOAD) {
            // Header bytes one at a time, so the length is known before any payload is read.
            val one = read()
            if (one < 0) return -1
            b[off] = one.toByte()
            return 1
        }
        val n = super.read(b, off, minOf(len, left))
        if (n > 0) {
            left -= n
            if (left == 0) state = State.TYPE
        }
        return n
    }

    override fun skip(n: Long): Long {
        var done = 0L
        while (done < n && read() >= 0) done++
        return done
    }

    override fun markSupported() = false

    private fun advance(b: Int) {
        when (state) {
            State.TYPE -> {
                state = State.LENGTH
                length = 0
                shift = 0
                lengthBytes = 0
            }
            State.LENGTH -> {
                if (++lengthBytes > 4) throw IOException("malformed MQTT length")
                length += (b and 0x7F) shl shift
                shift += 7
                if (length > maxPacket) throw IOException("MQTT packet too large")
                if (b and 0x80 == 0) {
                    left = length
                    state = if (left == 0) State.TYPE else State.PAYLOAD
                }
            }
            State.PAYLOAD -> if (--left == 0) state = State.TYPE
        }
    }
}
