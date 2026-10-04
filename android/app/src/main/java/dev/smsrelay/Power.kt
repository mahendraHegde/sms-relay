package dev.smsrelay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttPingSender
import org.eclipse.paho.client.mqttv3.internal.ClientComms
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Battery: the phone is allowed to sleep. Nothing holds the CPU awake permanently; work that
 * must finish (saving and sending an SMS, handling a command, connecting) takes a short timed
 * wake lock, and MQTT keep-alive pings are driven by an alarm that wakes the CPU briefly.
 */
object Power {
    /** Keep the CPU awake until [release] is called, or at most [capMs] (a safety cap, not the plan). */
    fun awake(ctx: Context, capMs: Long, tag: String): PowerManager.WakeLock =
        ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsrelay:$tag")
            .apply { setReferenceCounted(false); acquire(capMs) }

    fun release(w: PowerManager.WakeLock) {
        if (w.isHeld) runCatching { w.release() }
    }

    /** Wake-up alarm that fires even in Doze (inexact if exact alarms are not allowed, Android 12+). */
    fun alarm(ctx: Context, atElapsed: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, pi)
        }
    }
}

/**
 * Paho's default ping sender uses a Java timer, which does not run while the CPU sleeps, so the
 * broker would drop us. This one schedules each ping with an alarm and holds a wake lock only
 * until the ping round trip completes. [onPing] gets true for an answered ping, false when no
 * answer came within 30 s (the connection is dead even if TCP has not noticed).
 */
class AlarmPingSender(
    context: Context,
    private val exec: ScheduledExecutorService,
    private val onPing: (Boolean) -> Unit,
) : MqttPingSender {
    private val ctx = context.applicationContext
    @Volatile private var comms: ClientComms? = null

    private val pi: PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, PingAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun init(comms: ClientComms) {
        this.comms = comms
    }

    override fun start() {
        current = this
        schedule(comms?.keepAlive ?: return)
    }

    override fun stop() {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi)
        if (current === this) current = null
    }

    override fun schedule(delayInMillis: Long) {
        current = this
        Power.alarm(ctx, SystemClock.elapsedRealtime() + delayInMillis, pi)
    }

    fun fire() {
        val c = comms ?: return
        val wake = Power.awake(ctx, 35_000, "ping")
        val done = AtomicBoolean(false)
        fun finish(ok: Boolean) {
            if (!done.compareAndSet(false, true)) return
            onPing(ok) // takes its own wake lock before ours goes
            Power.release(wake)
        }
        val token = try {
            c.checkForActivity(object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = finish(true)
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) = finish(false)
            })
        } catch (e: Exception) {
            null
        }
        if (token == null) {
            // No ping was due (recent traffic) or the client is gone: Paho has rescheduled already.
            done.set(true)
            Power.release(wake)
            return
        }
        exec.schedule({ finish(false) }, 30, TimeUnit.SECONDS)
    }

    companion object {
        @Volatile var current: AlarmPingSender? = null
    }
}

/** Not exported; only our own alarm reaches it. */
class PingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // In a fresh process (a vendor battery manager killed us) there is no sender: reconnect instead of dropping the chain.
        AlarmPingSender.current?.fire() ?: WakeReceiver.wake(ctx)
    }
}

/**
 * Target of every relay alarm (reconnect retries, connect deadline, broker trial end, pairing,
 * 6-hourly watchdog).
 * A broadcast, not a foreground-service start: Android 12+ may refuse to start a foreground
 * service from an inexact alarm in the background, while a receiver always runs.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = wake(ctx)

    companion object {
        fun wake(ctx: Context) {
            Relay.check(ctx) // takes its own wake lock immediately
            RelayService.keepRunning(ctx)
        }

        fun pendingIntent(ctx: Context, slot: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, slot, Intent(ctx, WakeReceiver::class.java).setAction("dev.smsrelay.WAKE_$slot"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
