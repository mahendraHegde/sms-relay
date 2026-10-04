package dev.smsrelay

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.smsrelay.core.Allowlist
import dev.smsrelay.core.Heartbeat
import dev.smsrelay.core.Rules
import dev.smsrelay.core.CoreState
import dev.smsrelay.core.Keys
import dev.smsrelay.core.Pairing
import dev.smsrelay.core.PhoneCore
import dev.smsrelay.core.ProviderSms
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Process-wide owner of the core and the MQTT connection. Every core call runs on [exec], one at a
 * time, so receivers, alarms and MQTT callbacks never race each other. Work posted to [exec] holds a
 * wake lock until it has run ([onExec]); nothing keeps the phone awake otherwise.
 */
object Relay {
    private const val TAG = "Relay"
    /** How long a new broker gets to prove the reader can reach us on it before we roll back. */
    private const val TRIAL_MS = 10 * 60_000L

    // One pending alarm per slot (see WakeReceiver.pendingIntent); slot 0 is RelayService's watchdog.
    private const val RETRY_ALARM = 1
    private const val TRIAL_ALARM = 2
    private const val PAIR_ALARM = 3
    private const val CONNECT_ALARM = 4
    private const val JOURNAL_ALARM = 5
    /** Largest envelope plus framing; reader commands are far smaller. */
    private const val MAX_DOWN_BYTES = 70 * 1024
    private const val MAX_QUEUED_CMDS = 32
    private val queuedCmds = java.util.concurrent.atomic.AtomicInteger()

    val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "relay") }
    /** Saving incoming SMS to the provider never waits behind relay work (broadcasts have a deadline). */
    val smsExec = Executors.newSingleThreadExecutor { r -> Thread(r, "sms-save") }

    @Volatile private var db: Db? = null
    private val heartbeats = HashMap<String, Heartbeat>()
    /** Survives core rebuilds so hello replays stay rejected (see CoreState). */
    private val coreState = CoreState()
    /** After a failed attempt or a drop, don't reconnect before this (elapsedRealtime), see backoff. */
    private var retryNotBefore = 0L
    /** When the last connect attempt started (elapsedRealtime). */
    private var lastAttemptAt = Long.MIN_VALUE / 2
    /** An incoming SMS may cut a backoff short, but at most once per max(this, half the backoff). */
    private const val SMS_RETRY_GAP_MS = 2 * 60_000L
    private var lastBackoffMs = 0L
    /** Pairing announcements so far; the re-announce chain slows down after half an hour. */
    private var announces = 0
    private val journalLock = Any()
    private var core: PhoneCore? = null
    private var mqtt: MqttTransport? = null
    /** Network kind ("wifi"/"cell"/"other") the current connection was made on. */
    private var connectedOn: String? = null
    private val startedAt = SystemClock.elapsedRealtime()

    @Volatile var lastError: String? = null
        private set

    fun connected(): Boolean = mqtt?.isConnected() == true

    fun queueSize(): Int = runCatching { db?.unackedCount() ?: 0 }.getOrDefault(0)

    fun confirmed(): Boolean = db?.getMeta(PhoneCore.META_CONFIRMED) == "1"

    @Synchronized
    private fun database(ctx: Context): Db = db ?: Db(ctx.applicationContext).also { db = it }

    /** Run [block] on [exec] with the CPU held awake until it has run (at most [capMs]). */
    private fun onExec(ctx: Context, capMs: Long, tag: String, block: () -> Unit) {
        val w = Power.awake(ctx, capMs, tag)
        exec.execute {
            try {
                block()
            } catch (t: Throwable) {
                fail(tag, t)
            } finally {
                Power.release(w)
            }
        }
    }

    // ---- entry points ----

    /**
     * From the SMS_DELIVER receiver: journal the message first (our own database), then put it in the
     * SMS provider, then relay it. If the provider write fails, check() retries the journal entry,
     * so an SMS taken from the system is never lost.
     */
    fun saveIncoming(ctx: Context, address: String, body: String, dateSent: Long, subId: Int) {
        val database = database(ctx)
        val date = System.currentTimeMillis()
        val saved = synchronized(journalLock) {
            val jid = database.journal(address, body, date, dateSent, subId)
            val s = runCatching { AndroidSmsStore(ctx).insertIncoming(address, body, date, dateSent, subId) }
                .onFailure { fail("save", it) }.getOrNull()
                // Kept in the journal; retried by check() (paired or not), starting a minute from now.
                ?: return wakeCheckIn(ctx, JOURNAL_ALARM, 60_000)
            database.unjournal(jid)
            s
        }
        runCatching { dev.smsrelay.ui.Notifier.incoming(ctx, saved) }
        onExec(ctx, 10_000, "sms") {
            ensure(ctx)?.onRow(saved)
            // Offline: an SMS (often an OTP) is worth an attempt even during a long backoff, but SMS
            // senders must not be able to drive more than one handshake per SMS_RETRY_GAP_MS.
            val gap = maxOf(SMS_RETRY_GAP_MS, lastBackoffMs / 2)
            if (!connected() && (backoffOver() || SystemClock.elapsedRealtime() - lastAttemptAt >= gap)) {
                retryNotBefore = 0
                checkNow(ctx)
            }
        }
    }

    /** A row we wrote in the provider (a message sent or failed, a draft saved): relay it. */
    fun rowChanged(ctx: Context, providerId: Long) = onExec(ctx, 10_000, "row") {
        val row = AndroidSmsStore(ctx).query(providerId) ?: return@onExec
        ensure(ctx)?.onRow(row)
    }

    /** Rows deleted through the phone's own messaging UI (or drafts replaced/sent). */
    fun rowsDeleted(ctx: Context, providerIds: List<Long>) = onExec(ctx, 10_000, "deleted") {
        ensure(ctx)?.onLocalDelete(providerIds)
    }

    /** Service start, watchdog and retry alarms: reconnect if needed and re-check the provider. */
    fun check(ctx: Context) = onExec(ctx, 30_000, "check") { checkNow(ctx) }

    /**
     * The default network changed. Moved to Wi-Fi or mobile data while connected over the other one:
     * move the connection over (a Wi-Fi that lost internet can leave a socket that looks connected
     * until the next keep-alive, up to an hour away).
     */
    fun networkChanged(ctx: Context) = onExec(ctx, 30_000, "network") {
        retryNotBefore = 0 // a new network is worth trying at once, whatever the backoff says
        val now = networkKind(ctx)
        if (connected() && connectedOn != now && (now == "wifi" || now == "cell")) rebuild(ctx) else checkNow(ctx)
    }

    /**
     * Internet validated on the same network. Retry now if not connected. With [replaceLive] (an
     * outage-and-back, not the first validation after joining), also replace a connection that looks
     * alive: it may have lived through the outage and carry nothing (NAT state lost). At most once per
     * 5 minutes, so a network whose validation flaps cannot force a reconnect on every flap.
     */
    fun internetBack(ctx: Context, replaceLive: Boolean) = onExec(ctx, 30_000, "internet") {
        retryNotBefore = 0
        val now = SystemClock.elapsedRealtime()
        if (replaceLive && connected() && now - lastInternetRebuild >= INTERNET_REBUILD_GAP_MS) {
            lastInternetRebuild = now
            rebuild(ctx)
        }
        checkNow(ctx)
    }

    private var lastInternetRebuild = -INTERNET_REBUILD_GAP_MS
    private const val INTERNET_REBUILD_GAP_MS = 5 * 60_000L

    /** Forwarding screen (behind the screen lock): current rules and allowlist. */
    fun forwarding(ctx: Context, done: (Rules.RuleSet?, List<Allowlist.Entry>) -> Unit) = exec.execute {
        val c = runCatching { ensure(ctx) }.getOrNull()
        done(c?.rules(), c?.allowlist().orEmpty())
    }

    fun proposeNumber(ctx: Context, number: String, done: (Boolean) -> Unit) = exec.execute {
        done(runCatching { ensure(ctx)?.proposeNumber(number) == true }.getOrDefault(false))
    }

    fun removeNumber(ctx: Context, number: String, done: () -> Unit) = exec.execute {
        runCatching { ensure(ctx)?.removeNumber(number) }
        done()
    }

    /** The user tapped "fingerprint matches" on the phone. */
    fun confirmPairing(ctx: Context, done: (Throwable?) -> Unit) = exec.execute {
        done(runCatching { (ensure(ctx) ?: error("not paired")).confirmPairing() }.exceptionOrNull())
    }

    fun repair(ctx: Context, code: String?, done: (Throwable?) -> Unit) = exec.execute {
        val r = runCatching {
            if (code != null) Pairing.parse(code) // validate before touching anything
            mqtt?.close()
            mqtt = null
            core = null
            val database = database(ctx)
            database.wipe()
            heartbeats.clear() // their state lived in the wiped table
            Secrets.get(ctx).setPairing(code)
            if (code != null) {
                database.setMeta(PhoneCore.META_PAIRED_AT, System.currentTimeMillis().toString())
                announces = 0
                wakeCheckIn(ctx, PAIR_ALARM, 60_000) // start the announce chain now, not at the next watchdog
            }
        }
        done(r.exceptionOrNull())
    }

    // ---- internals (all on exec) ----

    private fun checkNow(ctx: Context) {
        // Connecting comes first and on its own: nothing in reconcile may keep the phone offline,
        // because being reachable is what lets the reader see and fix problems.
        val c = runCatching {
            val secrets = Secrets.get(ctx)
            if (secrets.candidate != null && System.currentTimeMillis() >= secrets.candidateDeadline) {
                // The reader never showed up on the new broker: go back to the one that worked, and
                // forget the move so a replay of it cannot push us there again. Forget it first: if
                // we die in between, a stale marker is harmless, a stale candidate is not.
                database(ctx).setMeta(PhoneCore.META_LAST_REBROKER, "")
                secrets.dropCandidate()
                rebuild(ctx)
            }
            val t = mqtt
            if (t?.stuck() == true) {
                // A hung attempt counts as a failure: drop it and back off like any other.
                t.close()
                mqtt = null
                core = null
                val hb = heartbeat(ctx, connectedOn ?: networkKind(ctx))
                hb.onConnectFailed() // a hang right after a climb may be the broker refusing it
                backOff(ctx, hb.nextRetryMs())
            }
            if (backoffOver()) {
                // Not connected and no attempt in flight: start from a fresh transport, so keep-alive,
                // battery mode and network kind match the network we are on now.
                val cur = mqtt
                if (cur != null && !cur.isConnected() && !cur.connectingNow()) rebuild(ctx)
                ensure(ctx)?.also { connect(ctx) }
            }
            // Connected but the subscribe has not been confirmed: look again shortly.
            if (mqtt?.awaitingSubscribe() == true) wakeCheckIn(ctx, CONNECT_ALARM, 40_000)
            core
        }.onFailure {
            fail("connect", it)
            backOff(ctx, 5 * 60_000L) // keep the alarm chain alive; don't wait for the watchdog
        }.getOrNull()
        // The inbox comes first, paired or not: a journaled SMS must reach the provider either way.
        runCatching { drainJournal(ctx, c) }.onFailure { fail("journal", it) }
        if (c == null) return
        runCatching {
            c.reconcile()
            if (connected()) {
                c.flush(onlyNew = true) // re-sends only on a live reader's hello
                // Setup only: until the reader has spoken, re-announce our keys (every minute for half
                // an hour, then every 15 minutes).
                if (c.announceIfNeeded()) wakeCheckIn(ctx, PAIR_ALARM, if (++announces <= 30) 60_000 else 15 * 60_000L)
            }
        }.onFailure { fail("reconcile", it) }
    }

    /** Retry journaled SMS that did not reach the provider. */
    private fun drainJournal(ctx: Context, c: PhoneCore?) {
        val database = database(ctx)
        val store = AndroidSmsStore(ctx)
        val pending = database.journaled()
        var left = 0
        for (j in pending) {
            // Same lock as saveIncoming: find-then-insert must not run twice for one SMS.
            val saved = runCatching {
                synchronized(journalLock) {
                    store.insertIncoming(j.address, j.body, j.date, j.dateSent, j.subId)?.also { database.unjournal(j.id) }
                }
            }.onFailure { fail("journal", it) }.getOrNull()
            if (saved == null) {
                left++
                continue
            }
            c?.onRow(saved)
            runCatching { dev.smsrelay.ui.Notifier.incoming(ctx, saved) }
        }
        // Provider still refusing (e.g. storage full): try again, less often each time (5, 15, 60 min).
        journalRetries = if (left > 0) journalRetries + 1 else 0
        if (left > 0) wakeCheckIn(ctx, JOURNAL_ALARM, JOURNAL_RETRY_MS[minOf(journalRetries, JOURNAL_RETRY_MS.size) - 1])
    }

    private var journalRetries = 0
    private val JOURNAL_RETRY_MS = longArrayOf(5 * 60_000L, 15 * 60_000L, 60 * 60_000L)

    private fun connect(ctx: Context) {
        // DNS, TCP, TLS, CONNECT. The alarm is only a deadline check for an attempt that hangs: if the
        // attempt fails normally, the backoff (retryNotBefore) stops that check from reconnecting.
        if (mqtt?.connectIfNeeded(Power.awake(ctx, STUCK_CONNECT_MS + 10_000, "connect")) == true) {
            lastAttemptAt = SystemClock.elapsedRealtime()
            wakeCheckIn(ctx, CONNECT_ALARM, STUCK_CONNECT_MS + 10_000)
        }
    }

    private fun backoffOver() = SystemClock.elapsedRealtime() >= retryNotBefore

    /** Wait [ms] before the next attempt; the RETRY alarm fires then. */
    private fun backOff(ctx: Context, ms: Long) {
        lastBackoffMs = ms
        retryNotBefore = SystemClock.elapsedRealtime() + ms
        wakeCheckIn(ctx, RETRY_ALARM, ms)
    }

    /** Drop the current connection and core and build them again from the active pairing. */
    private fun rebuild(ctx: Context) {
        mqtt?.close()
        mqtt = null
        core = null
        ensure(ctx)
        connect(ctx)
    }

    private fun heartbeat(ctx: Context, kind: String): Heartbeat =
        heartbeats.getOrPut(kind) { Heartbeat(database(ctx), kind) }

    /** Build the core and transport for the active pairing (candidate broker while on trial). */
    private fun ensure(ctx: Context): PhoneCore? {
        core?.let { return it }
        val appCtx = ctx.applicationContext
        val secrets = Secrets.get(appCtx)
        val now = System.currentTimeMillis()
        // activePairing() returns the candidate exactly when this is true.
        val onTrial = secrets.candidate != null && now < secrets.candidateDeadline
        val pairing = secrets.activePairing(now) ?: return null
        val database = database(appCtx)
        val kind = networkKind(appCtx)
        // NAT timeouts differ between Wi-Fi and the carrier: each learns its own keep-alive.
        val hb = heartbeat(appCtx, kind)
        // Without the battery-optimisation exemption, Doze (unplugged) holds our alarms for ~9-15
        // minutes, so a short keep-alive would just get us dropped: use a Doze-compatible one and don't
        // let Heartbeat learn from it. With the exemption, alarms and network run normally on battery.
        val onBattery = !charging(appCtx) &&
            !appCtx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(appCtx.packageName)
        val keepAlive = if (onBattery) maxOf(hb.keepAliveSec(), Heartbeat.BATTERY_SEC) else hb.keepAliveSec()
        val transport = MqttTransport(appCtx, pairing, secrets.clientId, keepAlive, exec)
        val keys = Keys(secrets.phoneSig, secrets.phoneEnc, pairing.readerSigPub, pairing.readerEncPub)
        val c = PhoneCore(keys, AndroidSmsStore(appCtx), database, transport, { status(appCtx) }, state = coreState,
            smsOut = { to, text -> SmsSender.sendForId(appCtx, to, text) }, onRebroker = { code, moveCmd ->
            // Give the "ok" result a moment to leave on the current broker, then switch.
            val w = Power.awake(appCtx, 10_000, "rebroker")
            exec.schedule({
                try {
                    startTrial(appCtx, code, moveCmd)
                } catch (t: Throwable) {
                    fail("rebroker", t)
                } finally {
                    Power.release(w)
                }
            }, 3, TimeUnit.SECONDS)
        })
        mqtt = transport
        core = c
        connectedOn = kind
        // Alarms do not survive a reboot: make sure a running broker trial still ends on time.
        if (onTrial) wakeCheckIn(appCtx, TRIAL_ALARM, secrets.candidateDeadline - now + 5_000)
        fun current() = mqtt === transport

        transport.onError = { lastError = it }
        transport.onConnected = { w ->
            exec.execute {
                try {
                    if (current()) {
                        retryNotBefore = 0
                        cancelCheckIn(appCtx, CONNECT_ALARM) // resolved: no deadline check needed
                        hb.onConnected()
                        c.onConnected() // announces once if pairing is unfinished
                        // ...and keep announcing until the reader has spoken (after a reboot mid-setup
                        // nothing else would restart the chain).
                        if (c.needsAnnounce()) wakeCheckIn(appCtx, PAIR_ALARM, 60_000)
                    }
                } catch (t: Throwable) {
                    fail("connected", t)
                } finally {
                    Power.release(w)
                }
            }
        }
        transport.onPing = { ok ->
            onExec(appCtx, 10_000, "ping") {
                if (!current()) return@onExec
                if (!ok) {
                    // Unanswered ping: the path is dead (often a NAT that dropped us). Start over.
                    if (!onBattery) hb.onLost()
                    rebuild(appCtx)
                } else if (hb.onPingOk(learn = !onBattery)) {
                    rebuild(appCtx) // reconnect with the next, longer keep-alive
                }
            }
        }
        transport.onLost = {
            onExec(appCtx, 5_000, "lost") {
                if (current()) {
                    // A drop caused by the network itself changing (router died, Wi-Fi to mobile) says
                    // nothing about this network's NAT timeout: don't learn from it.
                    if (!onBattery && networkKind(appCtx) == kind) hb.onLost()
                    backOff(appCtx, hb.nextRetryMs())
                }
            }
        }
        transport.onConnectFailed = {
            onExec(appCtx, 5_000, "retry") {
                if (current()) {
                    cancelCheckIn(appCtx, CONNECT_ALARM)
                    hb.onConnectFailed()
                    backOff(appCtx, hb.nextRetryMs())
                }
            }
        }
        // A publish was not acknowledged: the connection is dead even though TCP thinks not.
        transport.onStalled = { onExec(appCtx, 10_000, "stalled") { if (current()) rebuild(appCtx) } }
        transport.onMessage = onMessage@{ bytes ->
            // Admission before any wake lock or queueing: anyone who can publish to our topic could
            // otherwise pile up unsigned garbage. Commands are small and few; drop what cannot be one.
            if (bytes.size > MAX_DOWN_BYTES || queuedCmds.get() >= MAX_QUEUED_CMDS) return@onMessage
            queuedCmds.incrementAndGet()
            onExec(appCtx, 10_000, "cmd") {
                queuedCmds.decrementAndGet()
                if (!current()) return@onExec // queued by a connection we have since replaced
                hb.onTraffic()
                // Commit a trial broker only when the reader says it has received our status here and
                // names this move: both directions work, and a command replayed from the old broker
                // cannot carry that (the reader adds it only after hearing us here).
                val msg = c.onDown(bytes)
                val s = Secrets.get(appCtx)
                if (onTrial && core === c && s.candidate != null && PhoneCore.confirmsMove(msg, s.candidateCmd)) {
                    s.commitCandidate()
                }
            }
        }
        return c
    }

    private fun startTrial(ctx: Context, code: String, moveCmd: String) {
        val secrets = Secrets.get(ctx)
        val target = Pairing.parse(code)
        val current = secrets.pairing ?: return // unpaired meanwhile
        // Re-paired meanwhile with a different reader: this move belongs to the old one.
        if (!target.readerSigPub.contentEquals(current.readerSigPub) || !target.readerEncPub.contentEquals(current.readerEncPub)) return
        val cand = secrets.candidate
        if (cand != null && cand.sameBroker(target)) return // already on trial there
        if (cand == null && current.sameBroker(target)) return // already there
        secrets.setCandidate(code, System.currentTimeMillis() + TRIAL_MS, moveCmd)
        rebuild(ctx)
        // An alarm, not an executor timer: executor time stops while the CPU sleeps.
        wakeCheckIn(ctx, TRIAL_ALARM, TRIAL_MS + 5_000)
    }

    /** Run check() after [ms] even if the phone is asleep. Each [slot] is one pending alarm. */
    private fun wakeCheckIn(ctx: Context, slot: Int, ms: Long) {
        Power.alarm(ctx, SystemClock.elapsedRealtime() + ms, WakeReceiver.pendingIntent(ctx, slot))
    }

    private fun cancelCheckIn(ctx: Context, slot: Int) {
        ctx.getSystemService(android.app.AlarmManager::class.java).cancel(WakeReceiver.pendingIntent(ctx, slot))
    }

    private fun networkKind(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "other"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> "other"
        }
    }

    private fun battery(ctx: Context): Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun charging(ctx: Context): Boolean = (battery(ctx)?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private fun fail(where: String, t: Throwable) {
        lastError = "$where: ${t.javaClass.simpleName}"
        Log.w(TAG, "$where failed: ${t.javaClass.simpleName}") // never log message content
    }

    private fun status(ctx: Context): JSONObject {
        val bat = battery(ctx)
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return JSONObject()
            .put("bat", if (level >= 0) level * 100 / scale else -1)
            .put("chg", charging(ctx))
            .put("role", ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true)
            // Without the battery exemption, Doze cuts the relay off during power cuts.
            .put("doze", ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName))
            .put("net", connectedOn)
            .put("up", (SystemClock.elapsedRealtime() - startedAt) / 1000)
            .put("ver", BuildConfig.VERSION_NAME)
    }
}
