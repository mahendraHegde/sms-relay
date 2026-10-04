package dev.smsrelay.core

import org.json.JSONObject

/** Everything the phone needs, produced by the reader as `SR1.<base64url JSON>`. */
data class Pairing(
    val host: String,
    val port: Int,
    val topicPrefix: String,
    val username: String,
    val password: String,
    val readerSigPub: ByteArray,
    val readerEncPub: ByteArray,
) {
    val upTopic get() = "$topicPrefix/up"
    val downTopic get() = "$topicPrefix/down"

    /** Same broker account and topics (reader keys aside). */
    fun sameBroker(o: Pairing) =
        host == o.host && port == o.port && topicPrefix == o.topicPrefix && username == o.username && password == o.password

    companion object {
        private const val PREFIX = "SR1."
        private val HOST = Regex("^[A-Za-z0-9.-]{1,253}$")
        private val TOPIC = Regex("^[A-Za-z0-9_-]{1,64}(/[A-Za-z0-9_-]{1,64}){0,3}$")

        fun parse(code: String): Pairing {
            val trimmed = code.trim()
            require(trimmed.startsWith(PREFIX)) { "not a pairing code" }
            val raw = Envelope.b64d(trimmed.removePrefix(PREFIX))
            require(raw.isNotEmpty()) { "pairing code is not valid base64" }
            val j = JSONObject(String(raw, Charsets.UTF_8))
            val p = Pairing(
                host = j.getString("h"),
                port = j.optInt("p", 8883),
                topicPrefix = j.getString("t"),
                username = j.getString("pu"),
                password = j.getString("pp"),
                readerSigPub = Envelope.b64d(j.getString("rs")),
                readerEncPub = Envelope.b64d(j.getString("re")),
            )
            require(HOST.matches(p.host)) { "bad broker host" }
            require(p.port in 1..65535) { "bad port" }
            require(TOPIC.matches(p.topicPrefix)) { "bad topic prefix" }
            require(p.username.isNotEmpty() && p.password.isNotEmpty()) { "missing broker credentials" }
            require(p.readerSigPub.size == 32 && p.readerEncPub.size == 32) { "bad reader keys" }
            return p
        }
    }
}
