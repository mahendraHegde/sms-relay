package dev.smsrelay.core

import org.json.JSONObject

/**
 * Adaptive MQTT keep-alive. Every ping wakes the phone, so the interval should be as long as the
 * network path tolerates; NAT boxes silently drop idle TCP connections after an unknown time.
 * Start short, climb a level after a few good pings, and when a level kills the connection at its
 * first ping, step back and do not try that level again for a week (networks change, so re-probe).
 *
 * Retries after failures back off, so a long outage does not wake the phone every minute.
 */
class Heartbeat(
    private val store: RelayStore,
    /** Network kind ("wifi", "cell", ...): each network path learns its own interval. */
    key: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val meta = "heartbeat_$key"
    /** Home routers usually keep idle connections much longer than carrier NATs. */
    private val ladder = if (key == "wifi") WIFI_LADDER_MIN else LADDER_MIN
    private val top = ladder.size - 1

    companion object {
        val LADDER_MIN = intArrayOf(4, 8, 13, 20, 28)
        val WIFI_LADDER_MIN = intArrayOf(4, 8, 13, 20, 28, 40, 55)
        val RETRY_MIN = intArrayOf(1, 2, 5, 10, 15, 30)
        const val PINGS_TO_CLIMB = 3
        /** Keep-alive on battery: Doze only lets allow-while-idle alarms fire every ~9-15 minutes. */
        const val BATTERY_SEC = 15 * 60
        const val CEILING_MS = 7L * 24 * 3600 * 1000
    }

    private var level = 0
    private var ceiling = top
    /** Set when we climbed and have not connected at the new level yet. */
    private var climbPending = false
    private var ceilingUntil = 0L
    private var failures = 0
    private var connectedAt = 0L
    private var goodPings = 0
    private var proven = false
    /** Drops before the first ping at the current, not yet proven level (a broker clamping keep-alive). */
    private var earlyDrops = 0
    /** The current level has carried a ping or traffic at least once (on any connection). */
    private var levelProven = false

    init {
        store.getMeta(meta)?.let {
            runCatching {
                val j = JSONObject(it)
                // Anything out of range is treated as no state: start again from the bottom.
                level = j.optInt("level", 0).takeIf { it in 0..top } ?: 0
                ceiling = j.optInt("ceiling", top).takeIf { it in 0..top } ?: top
                ceilingUntil = j.optLong("ceilingUntil", 0)
            }
        }
    }

    /** Keep-alive to use for the next connection, in seconds. */
    @Synchronized
    fun keepAliveSec(): Int {
        expireCeiling()
        level = level.coerceAtMost(ceiling)
        return ladder[level] * 60
    }

    @Synchronized
    fun onConnected() {
        climbPending = false
        connectedAt = now()
        goodPings = 0
        proven = false
    }

    /**
     * Inbound traffic (e.g. reader hellos) proves the path works even though Paho then skips pings:
     * a later drop is not the interval's fault, and the retry backoff can reset.
     */
    @Synchronized
    fun onTraffic() {
        proven = true
        failures = 0
        earlyDrops = 0
        levelProven = true
    }

    /**
     * A ping round trip succeeded: the retry backoff resets. With [learn] (not on battery) it also
     * counts toward climbing; returns true when it is time to reconnect one level higher.
     */
    @Synchronized
    fun onPingOk(learn: Boolean = true): Boolean {
        failures = 0
        if (!learn) return false
        goodPings++
        earlyDrops = 0
        levelProven = true
        expireCeiling()
        if (goodPings >= PINGS_TO_CLIMB && level < ceiling) {
            level++
            levelProven = false
            climbPending = true
            save()
            return true
        }
        return false
    }

    /**
     * The connection died unexpectedly (or a ping went unanswered). If it died when the first ping
     * at this level was due, the level is too long for this network: step down and cap it.
     */
    @Synchronized
    fun onLost() {
        val interval = ladder[level] * 60_000L
        val alive = now() - connectedAt
        if (connectedAt > 0 && goodPings == 0 && !proven && level > 0) {
            // Died when the first ping was due, or (at a level never proven) twice in a row before it,
            // as when a broker enforces its own shorter keep-alive: this level does not work here.
            if (alive >= interval || (!levelProven && ++earlyDrops >= 2)) stepDown()
        }
        connectedAt = 0
        goodPings = 0
    }

    private fun stepDown() {
        level--
        levelProven = false
        ceiling = level
        ceilingUntil = now() + CEILING_MS
        earlyDrops = 0
        save()
    }

    /**
     * A connect attempt failed, or a connection dropped before proving itself; returns how long to
     * wait before the next attempt. Resets only once a connection has carried a ping or traffic, so a
     * broker that accepts and immediately drops us is not retried every minute forever.
     */
    @Synchronized
    fun nextRetryMs(): Long {
        val m = RETRY_MIN[failures.coerceAtMost(RETRY_MIN.size - 1)]
        failures++
        return m * 60_000L
    }

    /**
     * A connect attempt failed. Right after a climb that may be the broker refusing the longer
     * keep-alive: step back and cap, so a broker limit can never keep us offline.
     */
    @Synchronized
    fun onConnectFailed() {
        if (!climbPending || level == 0) return
        climbPending = false
        stepDown()
    }

    private fun expireCeiling() {
        if (ceiling < top && now() >= ceilingUntil) {
            ceiling = top
            save()
        }
    }

    private fun save() {
        store.setMeta(meta, JSONObject().put("level", level).put("ceiling", ceiling).put("ceilingUntil", ceilingUntil).toString())
    }
}
