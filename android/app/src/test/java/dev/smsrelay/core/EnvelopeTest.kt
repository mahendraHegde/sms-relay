package dev.smsrelay.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.SecureRandom

class EnvelopeTest {
    private val rng = SecureRandom()
    private val phoneSig = Ed25519PrivateKeyParameters(rng)
    private val phoneEnc = X25519PrivateKeyParameters(rng)
    private val readerSig = Ed25519PrivateKeyParameters(rng)
    private val readerEnc = X25519PrivateKeyParameters(rng)
    private val phoneSigPub get() = phoneSig.generatePublicKey().encoded
    private val readerSigPub get() = readerSig.generatePublicKey().encoded

    private fun up(p: JSONObject) = Envelope.seal(Envelope.KIND_UP, phoneSig, readerEnc.generatePublicKey().encoded, p)

    @Test fun roundTrip() {
        val env = up(JSONObject().put("t", "sms").put("body", "OTP 123456 ünïcødé 🙂"))
        val out = Envelope.open(env, Envelope.KIND_UP, phoneSigPub, readerEnc)
        assertEquals("OTP 123456 ünïcødé 🙂", out.getString("body"))
    }

    @Test fun lengthHiddenByBuckets() {
        val a = up(JSONObject().put("b", "x"))
        val b = up(JSONObject().put("b", "x".repeat(400)))
        assertEquals(a.size, b.size)
        val c = up(JSONObject().put("b", "x".repeat(600)))
        assertTrue(c.size > a.size)
    }

    @Test fun everyByteFlipIsRejected() {
        val env = up(JSONObject().put("t", "sms"))
        for (i in env.indices step 7) {
            val bad = env.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            expectInvalid { Envelope.open(bad, Envelope.KIND_UP, phoneSigPub, readerEnc) }
        }
    }

    @Test fun wrongSenderRejected() {
        val env = up(JSONObject().put("t", "sms"))
        expectInvalid { Envelope.open(env, Envelope.KIND_UP, readerSigPub, readerEnc) }
    }

    @Test fun wrongRecipientRejected() {
        val env = up(JSONObject().put("t", "sms"))
        expectInvalid { Envelope.open(env, Envelope.KIND_UP, phoneSigPub, X25519PrivateKeyParameters(rng)) }
    }

    @Test fun kindCannotBeReflected() {
        // A phone->reader envelope must not be accepted as a reader->phone command.
        val env = up(JSONObject().put("t", "hello"))
        expectInvalid { Envelope.open(env, Envelope.KIND_DOWN, phoneSigPub, readerEnc) }
        val relabelled = env.copyOf().also { it[1] = Envelope.KIND_DOWN }
        expectInvalid { Envelope.open(relabelled, Envelope.KIND_DOWN, phoneSigPub, readerEnc) }
    }

    @Test fun pairCarriesItsOwnKey() {
        val p = JSONObject().put("t", "pair").put("sigPub", Envelope.b64e(phoneSigPub))
        val env = Envelope.seal(Envelope.KIND_PAIR, phoneSig, readerEnc.generatePublicKey().encoded, p)
        assertEquals("pair", Envelope.open(env, Envelope.KIND_PAIR, null, readerEnc).getString("t"))
        // Claiming someone else's key fails the signature check.
        val lie = JSONObject().put("t", "pair").put("sigPub", Envelope.b64e(readerSigPub))
        val env2 = Envelope.seal(Envelope.KIND_PAIR, phoneSig, readerEnc.generatePublicKey().encoded, lie)
        expectInvalid { Envelope.open(env2, Envelope.KIND_PAIR, null, readerEnc) }
    }

    @Test fun lowOrderEphemeralRejected() {
        val env = up(JSONObject().put("t", "sms"))
        val zeroEpk = env.copyOf().also { java.util.Arrays.fill(it, 2, 34, 0) }
        expectInvalid { Envelope.open(zeroEpk, Envelope.KIND_UP, phoneSigPub, readerEnc) }
    }

    @Test fun envelopeSizesArePinned() {
        // Same numbers are asserted in reader/test/interop.test.mjs; a bucket change must change both.
        assertEquals(2 + 32 + 12 + 512 + 16 + 64, up(JSONObject().put("b", "x")).size)
        assertEquals(2 + 32 + 12 + 1024 + 16 + 64, up(JSONObject().put("b", "x".repeat(600))).size)
        assertEquals(65536, Envelope.pad(ByteArray(65532)).size)
    }

    @Test fun padRejectsOversize() {
        try {
            Envelope.pad(ByteArray(65533))
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
        }
    }

    /**
     * Cross-language check with reader/crypto.js. Writes vectors for the JS test and, if the JS
     * side has produced vectors, verifies them. Run via scripts/interop.sh.
     */
    @Test fun interopVectors() {
        val dir = File(System.getProperty("user.dir")!!).resolve("../../test-vectors").canonicalFile
        dir.mkdirs()
        val vectors = JSONArray()
        val payload = JSONObject().put("t", "sms").put("id", "0".repeat(32)).put("addr", "EXBANK").put("body", "1,234.00 debited ✓")
        vectors.put(
            JSONObject()
                .put("kind", 1)
                .put("env", Envelope.b64e(Envelope.seal(Envelope.KIND_UP, phoneSig, readerEnc.generatePublicKey().encoded, payload)))
                .put("expect", payload),
        )
        val pair = JSONObject().put("t", "pair").put("sigPub", Envelope.b64e(phoneSigPub)).put("encPub", Envelope.b64e(phoneEnc.generatePublicKey().encoded))
        vectors.put(
            JSONObject()
                .put("kind", 3)
                .put("env", Envelope.b64e(Envelope.seal(Envelope.KIND_PAIR, phoneSig, readerEnc.generatePublicKey().encoded, pair)))
                .put("expect", pair),
        )
        val out = JSONObject()
            .put("phoneSigPub", Envelope.b64e(phoneSigPub))
            .put("phoneEncPub", Envelope.b64e(phoneEnc.generatePublicKey().encoded))
            .put("readerSigPub", Envelope.b64e(readerSigPub))
            .put("readerEncPriv", Envelope.b64e(readerEnc.encoded))
            .put("fingerprint", Envelope.fingerprint(phoneSigPub, phoneEnc.generatePublicKey().encoded, readerSigPub, readerEnc.generatePublicKey().encoded))
            .put("vectors", vectors)
        dir.resolve("kotlin.json").writeText(out.toString(2))

        val js = dir.resolve("js.json")
        if (js.exists()) {
            val j = JSONObject(js.readText())
            val phoneEncPriv = X25519PrivateKeyParameters(Envelope.b64d(j.getString("phoneEncPriv")), 0)
            val rSig = Envelope.b64d(j.getString("readerSigPub"))
            val arr = j.getJSONArray("vectors")
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                val got = Envelope.open(Envelope.b64d(v.getString("env")), Envelope.KIND_DOWN, rSig, phoneEncPriv)
                assertEquals(v.getJSONObject("expect").toString(), got.toString())
            }
            assertEquals(
                j.getString("fingerprint"),
                Envelope.fingerprint(
                    Envelope.b64d(j.getString("phoneSigPub")), Envelope.b64d(j.getString("phoneEncPub")),
                    rSig, Envelope.b64d(j.getString("readerEncPub")),
                ),
            )
        }
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected Envelope.Invalid")
        } catch (e: Envelope.Invalid) {
        }
    }
}
