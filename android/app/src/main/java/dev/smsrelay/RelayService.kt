package dev.smsrelay

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock

/**
 * Keeps the relay alive without keeping the phone awake: a foreground notification (so the system and vendor
 * battery managers keep the process), a network callback for fast reconnects, and a 6-hourly alarm as a
 * watchdog in case everything else misses a failure. CPU time is taken in short timed wake locks (see Power).
 */
class RelayService : Service() {
    companion object {
        private const val CHANNEL = "relay"
        const val WATCHDOG_ALARM = 0
        private const val WATCHDOG_MS = 6 * AlarmManager.INTERVAL_HOUR

        private const val EXTRA_NO_CHECK = "no_check"

        /** Start (or poke) the service; it runs a full check. */
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RelayService::class.java))
        }

        /** Make sure the service (and so the process) is up, without a check; the caller did that. */
        fun keepRunning(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, RelayService::class.java).putExtra(EXTRA_NO_CHECK, true)) }
        }
    }

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.sync_channel), NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(getString(R.string.app_name))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(1, n)
        }

        val cb = object : ConnectivityManager.NetworkCallback() {
            private var validated = true
            private var everValidated = false
            private var current: Network? = null

            // A network switch is handled here (networkChanged). Below: internet validated on the same
            // network. If not connected, that is always worth an attempt; a live connection is only
            // replaced after a real outage-and-back, never on the first validation after joining.
            override fun onAvailable(network: Network) {
                current = network
                validated = true
                everValidated = false
                Relay.networkChanged(this@RelayService)
            }

            // Same network, internet back (ISP outage over, router still up): reconnect now instead of
            // waiting out the backoff or a keep-alive. Only on the transition, not on every update.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network != current) return
                val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                // (e.g. Wi-Fi up before the router's internet after a power cut: retry when it validates)
                if (now && !validated && (everValidated || !Relay.connected())) {
                    Relay.internetBack(this@RelayService, replaceLive = everValidated)
                }
                if (now) everValidated = true
                validated = now
            }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
        netCallback = cb

        val pi = WakeReceiver.pendingIntent(this, WATCHDOG_ALARM)
        getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            // Safety net only (reconnects, connect deadlines and broker trials have their own alarms),
            // so it can be rare: every 6 hours.
            SystemClock.elapsedRealtime() + WATCHDOG_MS,
            WATCHDOG_MS,
            pi,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Secrets.get(this).pairing == null) {
            // Nothing to relay: do not hold the wake lock and notification for nothing.
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_NO_CHECK, false) != true) Relay.check(this)
        return START_STICKY
    }

    override fun onDestroy() {
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        super.onDestroy()
    }
}
