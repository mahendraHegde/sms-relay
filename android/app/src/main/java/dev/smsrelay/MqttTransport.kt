package dev.smsrelay

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.smsrelay.core.MqttLimitStream
import dev.smsrelay.core.Pairing
import dev.smsrelay.core.Transport
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.MqttPingSender
import org.eclipse.paho.client.mqttv3.internal.HighResolutionTimer
import org.eclipse.paho.client.mqttv3.internal.NetworkModule
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.InputStream
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory

/**
 * One MQTT/TLS connection to the broker. Callbacks are plain events; Relay decides what they mean.
 * Every piece of network work holds a wake lock only until it completes (with a timeout as a cap).
 */
const val STUCK_CONNECT_MS = 90_000L
private const val STALL_MS = 20_000L

class MqttTransport(
    context: Context,
    private val p: Pairing,
    clientId: String,
    keepAliveSec: Int,
    private val exec: ScheduledExecutorService,
) : Transport {
    private val ctx = context.applicationContext

    /** Subscribed and ready; [done] must be released once the follow-up work has run. */
    var onConnected: (done: PowerManager.WakeLock) -> Unit = { Power.release(it) }
    var onMessage: (ByteArray) -> Unit = {}
    var onPing: (Boolean) -> Unit = {}
    var onLost: () -> Unit = {}
    var onConnectFailed: () -> Unit = {}
    var onStalled: () -> Unit = {}
    var onError: (String) -> Unit = {}

    private val connecting = AtomicBoolean(false)
    @Volatile private var connectingSince = 0L
    @Volatile private var connectedAt = 0L
    @Volatile private var subscribed = false
    private val stallCheckPending = AtomicBoolean(false)
    @Volatile private var lastPublish: IMqttDeliveryToken? = null
    @Volatile private var lastPublishAt = 0L

    private val client = LimitedClient(
        "ssl://${p.host}:${p.port}", clientId,
        AlarmPingSender(ctx, exec) { onPing(it) },
        // Paho's default clock (System.nanoTime) stops while the CPU sleeps, so on a sleeping phone it
        // would never think a ping is due and the broker would drop us. This one keeps counting.
        HighResolutionTimer { SystemClock.elapsedRealtimeNanos() },
    )

    /** Paho client whose incoming stream refuses oversized packets before Paho allocates them (see MqttLimitStream). */
    private class LimitedClient(uri: String, id: String, ping: MqttPingSender, timer: HighResolutionTimer) :
        MqttAsyncClient(uri, id, MemoryPersistence(), ping, null, timer) {
        override fun createNetworkModules(address: String, options: MqttConnectOptions): Array<NetworkModule> =
            super.createNetworkModules(address, options).map { m ->
                object : NetworkModule by m {
                    override fun getInputStream(): InputStream = MqttLimitStream(m.inputStream)
                }
            }.toTypedArray()
    }

    private val opts = MqttConnectOptions().apply {
        userName = p.username
        password = p.password.toCharArray()
        isCleanSession = true
        // Reconnects are driven by alarms (Relay): Paho's own timer stops while asleep.
        isAutomaticReconnect = false
        keepAliveInterval = keepAliveSec // adaptive, see Heartbeat
        connectionTimeout = 30
        maxInflight = 500
        socketFactory = SSLSocketFactory.getDefault()
        isHttpsHostnameVerificationEnabled = true
    }

    init {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                connecting.set(false)
                connectedAt = SystemClock.elapsedRealtime()
                subscribed = false
                val w = Power.awake(ctx, 15_000, "connected")
                // Never block inside a Paho callback; subscribe asynchronously.
                try {
                    client.subscribe(p.downTopic, 1, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            // A SUBACK can carry 0x80 (refused, e.g. the broker's ACL): that is a failure.
                            if (asyncActionToken?.grantedQos?.any { it == 0x80 } == true) return onFailure(asyncActionToken, null)
                            subscribed = true
                            onConnected(w)
                        }
                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            Power.release(w)
                            onError("subscribe: ${exception?.javaClass?.simpleName ?: "refused by broker"}")
                            runCatching { client.disconnect() }
                            onConnectFailed()
                        }
                    })
                } catch (e: Exception) {
                    Power.release(w)
                    onError("subscribe: ${e.javaClass.simpleName}")
                    onConnectFailed()
                }
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w("MqttTransport", "connection lost: ${cause?.javaClass?.simpleName}")
                onLost()
            }

            override fun messageArrived(topic: String?, message: MqttMessage) {
                if (topic == p.downTopic) onMessage(message.payload)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
    }

    fun isConnected() = client.isConnected

    /**
     * Paho 1.2.5 has no deadline for CONNACK or SUBACK: a broker that completes TLS and then goes
     * silent would leave us "connecting" (or connected but unsubscribed) forever. Relay rebuilds
     * the transport when this says the attempt is hopeless.
     */
    fun stuck(): Boolean {
        val now = SystemClock.elapsedRealtime()
        // Paho allows connectionTimeout (30 s) for TCP and again for TLS, plus DNS: a slow attempt on a
        // poor link can legitimately take ~70 s, so only call it hopeless after 90 s.
        return (connecting.get() && now - connectingSince > STUCK_CONNECT_MS) ||
            (client.isConnected && !subscribed && now - connectedAt > 30_000)
    }

    /** Connected, subscribe not confirmed yet (and not yet given up on). */
    fun awaitingSubscribe(): Boolean = client.isConnected && !subscribed && !stuck()

    /** A connect is in flight and still within its deadline. */
    fun connectingNow(): Boolean = connecting.get() && !stuck()

    /**
     * Start connecting unless connected or already connecting. Takes ownership of [lock] and releases
     * it when the attempt finishes; returns false (lock released) if nothing was started.
     */
    fun connectIfNeeded(lock: PowerManager.WakeLock): Boolean {
        if (client.isConnected || !connecting.compareAndSet(false, true)) {
            Power.release(lock)
            return false
        }
        connectingSince = SystemClock.elapsedRealtime()
        return try {
            client.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Power.release(lock)
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    connecting.set(false)
                    Power.release(lock)
                    onError("connect: ${exception?.javaClass?.simpleName}")
                    onConnectFailed() // retry later, backing off, without holding the CPU
                }
            })
            true
        } catch (e: Exception) {
            connecting.set(false) // Paho throws if a connect is already in flight
            Power.release(lock)
            false
        }
    }

    /** Queue an envelope; the CPU stays awake until the broker acknowledges it (or 25 s). */
    override fun publish(env: ByteArray, onDelivered: (() -> Unit)?): Boolean {
        if (!client.isConnected) return false
        val w = Power.awake(ctx, 25_000, "publish")
        return try {
            lastPublishAt = SystemClock.elapsedRealtime()
            lastPublish = client.publish(p.upTopic, env, 1, false, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    runCatching { onDelivered?.invoke() }
                    Power.release(w)
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) = Power.release(w)
            })
            watchForStall()
            true
        } catch (e: Exception) {
            Power.release(w)
            false
        }
    }

    /**
     * If the newest publish is still unacknowledged 20 s after it was sent, the connection is dead.
     * One timer at a time; it always looks at the newest publish (acks arrive in order, so the newest
     * being acknowledged covers the earlier ones) and re-arms while that one is younger than 20 s.
     */
    private fun watchForStall(delayMs: Long = STALL_MS) {
        if (!stallCheckPending.compareAndSet(false, true)) return
        // The publish's own wake lock keeps the CPU (and so this executor's clock) running.
        exec.schedule({
            stallCheckPending.set(false)
            val t = lastPublish ?: return@schedule
            if (t.isComplete || !client.isConnected) return@schedule
            val age = SystemClock.elapsedRealtime() - lastPublishAt
            if (age >= STALL_MS) onStalled() else watchForStall(STALL_MS - age)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun close() {
        runCatching { client.disconnectForcibly(1000, 1000) }
        runCatching { client.close() }
    }
}
