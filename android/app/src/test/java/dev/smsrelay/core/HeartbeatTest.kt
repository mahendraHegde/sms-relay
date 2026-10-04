package dev.smsrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatTest {
    private var clock = 1_800_000_000_000L
    private val store = PhoneCoreTest.FakeStore()
    private fun hb(key: String = "wifi") = Heartbeat(store, key) { clock }
    private val min = 60_000L

    /** One connection that survives [pings] pings at the current level. */
    private fun survive(h: Heartbeat, pings: Int): Boolean {
        h.onConnected()
        var climb = false
        repeat(pings) {
            clock += h.keepAliveSec() * 1000L
            climb = h.onPingOk()
        }
        return climb
    }

    @Test fun startsShortAndClimbsAfterGoodPings() {
        val h = hb()
        assertEquals(4 * 60, h.keepAliveSec())
        h.onConnected()
        clock += 4 * min
        assertFalse(h.onPingOk())
        assertFalse(h.onPingOk())
        assertTrue(h.onPingOk()) // third good ping: reconnect one level up
        assertEquals(8 * 60, h.keepAliveSec())
        for (expected in listOf(13, 20, 28, 40, 55)) {
            assertTrue(survive(h, Heartbeat.PINGS_TO_CLIMB))
            assertEquals(expected * 60, h.keepAliveSec())
        }
        assertFalse(survive(h, 10)) // top of the Wi-Fi ladder
        assertEquals(55 * 60, h.keepAliveSec())
    }

    @Test fun onBatteryPingsResetBackoffButNeverClimb() {
        val h = hb()
        repeat(4) { h.nextRetryMs() }
        h.onConnected()
        repeat(10) { assertFalse(h.onPingOk(learn = false)) }
        assertEquals(4 * 60, h.keepAliveSec()) // no climbing on battery
        assertEquals(1L, h.nextRetryMs() / min) // but the backoff reset
    }

    @Test fun twoEarlyDropsAtAnUnprovenLevelStepDown() {
        val h = hb()
        survive(h, 3) // -> 8, not yet proven
        repeat(2) {
            h.onConnected()
            clock += 3 * min // closed long before the first 8-minute ping (broker enforcing its own limit)
            h.onLost()
        }
        assertEquals(4 * 60, h.keepAliveSec())
    }

    @Test fun aLevelProvenOnceIsNotDroppedByTwoBlips() {
        val h = hb()
        survive(h, 3) // -> 8
        survive(h, 1) // a good ping at 8: proven
        repeat(2) {
            h.onConnected()
            clock += 3 * min // ISP blips long before the next ping
            h.onLost()
        }
        assertEquals(8 * 60, h.keepAliveSec())
    }

    @Test fun earlyDropCounterResetsOnProofAndAfterAStep() {
        val h = hb()
        survive(h, 3) // -> 8, unproven
        h.onConnected(); clock += 3 * min; h.onLost() // one early drop
        h.onConnected(); h.onTraffic() // proof resets the counter (and proves the level)
        clock += 3 * min; h.onLost()
        assertEquals(8 * 60, h.keepAliveSec())
        // After a step down the counter starts from zero: one more early drop is not enough.
        val g = hb("other")
        survive(g, 3); survive(g, 3) // -> 13, unproven
        repeat(2) { g.onConnected(); clock += 3 * min; g.onLost() } // steps to 8
        assertEquals(8 * 60, g.keepAliveSec())
        g.onConnected(); clock += 3 * min; g.onLost() // a single early drop at 8 (fresh level)
        assertEquals(8 * 60, g.keepAliveSec())
    }

    @Test fun mobileDataStopsAt28Minutes() {
        val h = hb("cell")
        repeat(10) { survive(h, Heartbeat.PINGS_TO_CLIMB) }
        assertEquals(28 * 60, h.keepAliveSec())
    }

    @Test fun refusedConnectRightAfterAClimbStepsBack() {
        val h = hb()
        assertTrue(survive(h, 3)) // climbed to 8, reconnect pending
        h.onConnectFailed() // e.g. the broker refuses the longer keep-alive
        assertEquals(4 * 60, h.keepAliveSec())
        assertFalse(survive(h, 10)) // capped
        // A failure that is not right after a climb changes nothing.
        clock += Heartbeat.CEILING_MS
        assertTrue(survive(h, 3))
        h.onConnected()
        h.onConnectFailed()
        assertEquals(8 * 60, h.keepAliveSec())
    }

    @Test fun levelThatKillsTheConnectionIsCappedForAWeek() {
        val h = hb()
        survive(h, 3) // -> 8
        survive(h, 3) // -> 13
        h.onConnected()
        clock += 13 * min + 30_000 // died when the first 13-minute ping was due
        h.onLost()
        assertEquals(8 * 60, h.keepAliveSec())
        assertFalse(survive(h, 20)) // capped at 8: no climbing
        assertEquals(8 * 60, h.keepAliveSec())
        clock += Heartbeat.CEILING_MS
        assertTrue(survive(h, 3)) // cap expired: probe again
        assertEquals(13 * 60, h.keepAliveSec())
    }

    @Test fun randomDropsDoNotLowerTheLevel() {
        val h = hb()
        survive(h, 3) // -> 8
        h.onConnected()
        clock += 2 * min // a single Wi-Fi blip long before any ping was due
        h.onLost()
        assertEquals(8 * 60, h.keepAliveSec())
        h.onConnected()
        clock += 8 * min
        h.onPingOk() // proves the level; the blip counter resets
        h.onConnected()
        clock += 8 * min
        h.onPingOk()
        clock += 5 * min // dropped after a good ping: not the interval's fault
        h.onLost()
        assertEquals(8 * 60, h.keepAliveSec())
    }

    @Test fun lowestLevelNeverDropsFurther() {
        val h = hb()
        h.onConnected()
        clock += 5 * min
        h.onLost()
        assertEquals(4 * 60, h.keepAliveSec())
    }

    @Test fun retriesBackOffAndResetOnlyOnceAConnectionProvesItself() {
        val h = hb()
        val waits = List(8) { h.nextRetryMs() / min }
        assertEquals(listOf(1L, 2, 5, 10, 15, 30, 30, 30), waits)
        h.onConnected() // a broker that accepts and drops us at once does not reset the backoff
        assertEquals(30L, h.nextRetryMs() / min)
        h.onConnected()
        h.onTraffic()
        assertEquals(1L, h.nextRetryMs() / min)
        h.nextRetryMs()
        h.onPingOk()
        assertEquals(1L, h.nextRetryMs() / min)
    }

    @Test fun trafficProvesTheIntervalSoADropDoesNotLowerIt() {
        val h = hb()
        survive(h, 3) // -> 8
        h.onConnected()
        h.onTraffic() // reader active: Paho skipped pings, but the path demonstrably worked
        clock += 9 * min
        h.onLost()
        assertEquals(8 * 60, h.keepAliveSec())
    }

    @Test fun stateSurvivesRestart() {
        val h = hb()
        survive(h, 3)
        survive(h, 3) // level 13
        h.onConnected()
        clock += 13 * min
        h.onLost() // cap at 8
        val again = hb()
        assertEquals(8 * 60, again.keepAliveSec())
        assertFalse(survive(again, 10))
    }

    @Test fun corruptStateFallsBackToDefaults() {
        store.setMeta("heartbeat_wifi", "{not json")
        assertEquals(4 * 60, hb().keepAliveSec())
        store.setMeta("heartbeat_wifi", """{"level":99,"ceiling":-5}""")
        assertEquals(4 * 60, hb().keepAliveSec())
    }

    @Test fun networksLearnSeparately() {
        val wifi = hb("wifi")
        survive(wifi, 3)
        survive(wifi, 3) // wifi at 13 minutes
        val cell = hb("cell")
        cell.onConnected()
        clock += 4 * min
        cell.onLost() // carrier trouble at the bottom level
        assertEquals(4 * 60, cell.keepAliveSec())
        assertEquals(13 * 60, hb("wifi").keepAliveSec())
    }
}
