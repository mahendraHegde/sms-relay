package dev.smsrelay.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PairingTest {
    private val key = Envelope.b64e(ByteArray(32) { 7 })
    private fun code(change: JSONObject.() -> Unit = {}): String {
        val j = JSONObject().put("h", "x.emqxsl.com").put("p", 8883).put("t", "smsrelay").put("pu", "phone").put("pp", "pw")
            .put("rs", key).put("re", key).apply(change)
        return "SR1." + Envelope.b64e(j.toString().toByteArray())
    }

    @Test fun parsesValidCode() {
        val p = Pairing.parse(code())
        assertEquals("smsrelay/up", p.upTopic)
        assertEquals("smsrelay/down", p.downTopic)
    }

    @Test fun rejectsBadFields() {
        val bad = listOf<JSONObject.() -> Unit>(
            { put("h", "evil host") },
            { put("p", 0) },
            { put("p", 70000) },
            { put("t", "a/#") },
            { put("t", "a/+/b") },
            { put("pu", "") },
            { put("pp", "") },
            { put("rs", Envelope.b64e(ByteArray(31))) },
            { put("re", "") },
        )
        for (b in bad) expectReject(code(b))
        expectReject(code().removePrefix("SR1."))
        expectReject("SR1.!!!")
    }

    private fun expectReject(c: String) {
        try {
            Pairing.parse(c)
            fail("accepted $c")
        } catch (e: Exception) {
        }
    }
}
