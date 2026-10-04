package dev.smsrelay.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

class MqttLimitStreamTest {
    private fun packet(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type)
        var n = payload.size
        do {
            var d = n and 0x7F
            n = n shr 7
            if (n > 0) d = d or 0x80
            out.write(d)
        } while (n > 0)
        out.write(payload)
        return out.toByteArray()
    }

    /** Read packets the way Paho's MqttInputStream does: header byte, length bytes, readFully. */
    private fun readAll(s: MqttLimitStream, count: Int): List<ByteArray> {
        val d = DataInputStream(s)
        return List(count) {
            d.readByte()
            var len = 0
            var mul = 1
            do {
                val b = d.readByte().toInt()
                len += (b and 0x7F) * mul
                mul *= 128
            } while (b and 0x80 != 0)
            ByteArray(len).also { d.readFully(it) }
        }
    }

    @Test fun passesNormalPacketsIncludingEmptyAndMultiByteLengths() {
        val payloads = listOf(ByteArray(0), ByteArray(5) { it.toByte() }, ByteArray(70_000) { (it % 251).toByte() }, ByteArray(127), ByteArray(128))
        val wire = payloads.fold(ByteArray(0)) { acc, p -> acc + packet(0x30, p) }
        val got = readAll(MqttLimitStream(ByteArrayInputStream(wire)), payloads.size)
        payloads.zip(got).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test fun refusesAPacketDeclaringMoreThanTheLimitBeforeAnyPayloadIsRead() {
        // Header claiming ~256 MiB, no payload behind it.
        val wire = byteArrayOf(0x30, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        try {
            readAll(MqttLimitStream(ByteArrayInputStream(wire)), 1)
            fail("accepted an oversized packet")
        } catch (e: IOException) {
            assertEquals("MQTT packet too large", e.message)
        }
    }

    @Test fun limitIsInclusiveAndAppliesToEveryPacket() {
        val max = 1000
        val ok = packet(0x30, ByteArray(max))
        assertEquals(max, readAll(MqttLimitStream(ByteArrayInputStream(ok), max), 1).single().size)
        val wire = packet(0x30, ByteArray(10)) + packet(0x30, ByteArray(max + 1))
        try {
            readAll(MqttLimitStream(ByteArrayInputStream(wire), max), 2)
            fail("accepted the second, oversized packet")
        } catch (e: IOException) {
        }
    }

    @Test fun refusesAFiveByteLength() {
        val wire = byteArrayOf(0x30, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01)
        try {
            readAll(MqttLimitStream(ByteArrayInputStream(wire), Int.MAX_VALUE), 1)
            fail("accepted a malformed length")
        } catch (e: IOException) {
            assertEquals("malformed MQTT length", e.message)
        }
    }

    /** Random packets, delivered in random short reads and consumed with mixed read styles: never over-reads, never mis-frames. */
    @Test fun mixedReadsAcrossRandomPackets() {
        val rnd = java.util.Random(7)
        repeat(300) {
            val payloads = List(1 + rnd.nextInt(5)) { ByteArray(rnd.nextInt(3000)).also(rnd::nextBytes) }
            val wire = payloads.fold(ByteArray(0)) { acc, p -> acc + packet(0x30, p) }
            // Underlying stream that returns at most a few bytes per call.
            val choppy = object : java.io.FilterInputStream(ByteArrayInputStream(wire)) {
                override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1 + rnd.nextInt(7)))
            }
            val s = MqttLimitStream(choppy, 2999)
            for (p in payloads) {
                assertEquals(0x30, s.read())
                var len = 0
                var mul = 1
                do {
                    val b = s.read()
                    len += (b and 0x7F) * mul
                    mul *= 128
                } while (b and 0x80 != 0)
                assertEquals(p.size, len)
                // Ask for more than the packet holds: the stream must stop at the packet boundary.
                val buf = ByteArray(len + 50)
                var got = 0
                while (got < len) {
                    val n = s.read(buf, got, buf.size - got)
                    assertTrue(n > 0 && got + n <= len)
                    got += n
                }
                assertArrayEquals(p, buf.copyOf(len))
            }
            assertEquals(-1, s.read())
        }
    }
}
