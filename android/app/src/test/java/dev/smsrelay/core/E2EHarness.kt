package dev.smsrelay.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Manual end-to-end harness: the real PhoneCore over a real MQTT broker (plain TCP, local only)
 * talking to the real reader page. Skipped unless SMSRELAY_E2E points at a work directory.
 * The harness waits for `pairing.txt` there, writes `fingerprint.txt`, injects SMS, and logs
 * deletes to `events.log`. Create `stop` to end it.
 */
class E2EHarness {
    @Test fun run() {
        val dirPath = System.getenv("SMSRELAY_E2E")
        assumeTrue(!dirPath.isNullOrEmpty())
        val dir = File(dirPath!!)
        val log = dir.resolve("events.log")
        fun event(s: String) = log.appendText("${System.currentTimeMillis()} $s\n")

        val codeFile = dir.resolve("pairing.txt")
        while (!codeFile.exists()) Thread.sleep(500)
        val p = Pairing.parse(codeFile.readText())

        val rng = SecureRandom()
        val sig = Ed25519PrivateKeyParameters(rng)
        val enc = X25519PrivateKeyParameters(rng)
        dir.resolve("fingerprint.txt").writeText(
            Envelope.fingerprint(sig.generatePublicKey().encoded, enc.generatePublicKey().encoded, p.readerSigPub, p.readerEncPub),
        )

        val sms = object : PhoneCoreTest.FakeSms() {
            override fun delete(providerId: Long): Boolean = super.delete(providerId).also { event("DELETE provider=$providerId ok=$it") }
        }
        val store = PhoneCoreTest.FakeStore()
        val exec = Executors.newSingleThreadExecutor()
        val client = MqttClient("tcp://${p.host}:${p.port}", "srp-e2e", MemoryPersistence())
        val transport = object : Transport {
            // MqttClient.publish blocks until the broker acknowledges a QoS 1 message.
            override fun publish(env: ByteArray, onDelivered: (() -> Unit)?): Boolean = try {
                client.isConnected && run { client.publish(p.upTopic, env, 1, false); onDelivered?.invoke(); true }
            } catch (e: Exception) {
                false
            }
        }
        val core = PhoneCore(Keys(sig, enc, p.readerSigPub, p.readerEncPub), sms, store, transport, {
            JSONObject().put("bat", 87).put("chg", true).put("role", true).put("up", 1).put("ver", "e2e")
        })
        store.setMeta(PhoneCore.META_PAIRED_AT, System.currentTimeMillis().toString())
        core.confirmPairing() // the harness stands in for the user tapping "fingerprint matches"

        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                exec.execute {
                    client.subscribe(p.downTopic, 1)
                    core.onConnected()
                    event("CONNECTED")
                }
            }
            override fun connectionLost(cause: Throwable?) = event("LOST ${cause?.javaClass?.simpleName}")
            override fun messageArrived(topic: String?, message: MqttMessage) {
                val bytes = message.payload
                exec.execute {
                    val before = core.rejected
                    core.onDown(bytes)
                    event(if (core.rejected > before) "REJECTED" else "CMD ok")
                }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        // Old SMS that only a backfill should bring over.
        sms.add("EXBANK", "Old statement from last week", System.currentTimeMillis() - 5 * 86_400_000L)

        client.connect(MqttConnectOptions().apply {
            userName = p.username
            password = p.password.toCharArray()
            isCleanSession = true
            isAutomaticReconnect = true
        })

        // Fictional senders and texts. The HTML one checks that the reader never renders markup.
        val samples = listOf(
            Triple("EXBANK", "OTP 482913 for a purchase of 25.00 at SHOP. Do not share.", ProviderSms.TYPE_INBOX),
            Triple("CARRIER", "Your plan renews in 3 days.", ProviderSms.TYPE_INBOX),
            Triple("+15555550100", "<img src=x onerror=alert(1)> hi, call me ☎️", ProviderSms.TYPE_INBOX),
            Triple("+15555550100", "Calling you in 5 minutes", ProviderSms.TYPE_SENT),
            Triple("+15555550100", "Also, bring the", ProviderSms.TYPE_DRAFT),
            Triple("EXBANK", "Payment of 15.00 received.", ProviderSms.TYPE_INBOX),
        )
        var i = 0
        val deadline = System.currentTimeMillis() + 20 * 60_000
        while (!dir.resolve("stop").exists() && System.currentTimeMillis() < deadline) {
            if (dir.resolve("inject").exists() && i < samples.size) {
                dir.resolve("inject").delete()
                val (addr, body, type) = samples[i++]
                exec.execute {
                    core.onRow(sms.add(addr, body, System.currentTimeMillis(), type))
                    event("INJECT $addr type=$type")
                }
            }
            if (dir.resolve("localdelete").exists()) {
                // Stand-in for a deletion in the phone's own messaging UI: the newest row goes.
                dir.resolve("localdelete").delete()
                exec.execute {
                    val pid = sms.rows.keys.maxOrNull() ?: return@execute
                    sms.rows.remove(pid)
                    core.onLocalDelete(listOf(pid))
                    event("LOCALDELETE provider=$pid")
                }
            }
            if (dir.resolve("offline").exists()) {
                dir.resolve("offline").delete()
                runCatching { client.disconnect() }
                event("WENT OFFLINE")
            }
            if (dir.resolve("online").exists()) {
                dir.resolve("online").delete()
                runCatching { client.reconnect() }
            }
            Thread.sleep(300)
        }
        event("STOP rows=${sms.rows.size} unacked=${store.unackedCount()} rejected=${core.rejected}")
        runCatching { client.disconnect() }
    }
}
