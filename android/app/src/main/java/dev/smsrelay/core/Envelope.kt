package dev.smsrelay.core

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Envelope format and key derivation. See docs/PROTOCOL.md; reader/crypto.js must match byte for byte. */
object Envelope {
    const val VERSION: Byte = 0x01
    const val KIND_UP: Byte = 0x01
    const val KIND_DOWN: Byte = 0x02
    const val KIND_PAIR: Byte = 0x03

    private const val HEADER = 2 + 32 + 12
    private const val SIG = 64
    private const val TAG = 16
    private const val MIN_BUCKET = 512
    private const val MAX_BUCKET = 65536
    private val INFO_PREFIX = "sms-relay/v1".toByteArray(Charsets.US_ASCII)

    private val rng = SecureRandom()

    class Invalid(msg: String) : Exception(msg)

    fun seal(
        kind: Byte,
        senderSig: Ed25519PrivateKeyParameters,
        recipientEncPub: ByteArray,
        payload: JSONObject,
    ): ByteArray {
        require(recipientEncPub.size == 32)
        val senderSigPub = senderSig.generatePublicKey().encoded
        val eph = X25519PrivateKeyParameters(rng)
        val epk = eph.generatePublicKey().encoded
        val nonce = ByteArray(12).also { rng.nextBytes(it) }

        val header = ByteArray(HEADER)
        header[0] = VERSION
        header[1] = kind
        System.arraycopy(epk, 0, header, 2, 32)
        System.arraycopy(nonce, 0, header, 34, 12)

        val shared = x25519(eph, recipientEncPub)
        val key = deriveKey(kind, shared, epk, recipientEncPub, senderSigPub)
        val aad = aad(header, kind, senderSigPub)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(pad(payload.toString().toByteArray(Charsets.UTF_8)))

        val signed = header + ct
        val signer = Ed25519Signer()
        signer.init(true, senderSig)
        signer.update(signed, 0, signed.size)
        return signed + signer.generateSignature()
    }

    /**
     * Verify and decrypt. [senderSigPub] is the expected sender; for KIND_PAIR pass null and
     * the key carried inside the plaintext is used (the caller decides whether to trust it).
     */
    fun open(
        env: ByteArray,
        expectedKind: Byte,
        senderSigPub: ByteArray?,
        recipientEnc: X25519PrivateKeyParameters,
    ): JSONObject {
        if (env.size < HEADER + TAG + SIG) throw Invalid("short envelope")
        if (env[0] != VERSION) throw Invalid("bad version")
        if (env[1] != expectedKind) throw Invalid("unexpected kind")
        val kind = env[1]
        if ((kind == KIND_PAIR) != (senderSigPub == null)) throw Invalid("sender key mismatch for kind")

        val signedLen = env.size - SIG
        val sig = env.copyOfRange(signedLen, env.size)
        if (senderSigPub != null && !verify(senderSigPub, env, signedLen, sig)) throw Invalid("bad signature")

        val header = env.copyOfRange(0, HEADER)
        val epk = env.copyOfRange(2, 34)
        val nonce = env.copyOfRange(34, 46)
        val recipientEncPub = recipientEnc.generatePublicKey().encoded
        val shared = x25519(recipientEnc, epk)
        val key = deriveKey(kind, shared, epk, recipientEncPub, senderSigPub)

        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
            cipher.updateAAD(aad(header, kind, senderSigPub))
            cipher.doFinal(env, HEADER, signedLen - HEADER)
        } catch (e: Exception) {
            throw Invalid("decrypt failed")
        }
        val json = try {
            JSONObject(String(unpad(plain), Charsets.UTF_8))
        } catch (e: Exception) {
            throw Invalid("bad payload")
        }

        if (kind == KIND_PAIR) {
            val claimed = b64d(json.optString("sigPub"))
            if (claimed.size != 32 || !verify(claimed, env, signedLen, sig)) throw Invalid("bad pair signature")
        }
        return json
    }

    private fun verify(pub: ByteArray, env: ByteArray, len: Int, sig: ByteArray): Boolean = try {
        val v = Ed25519Signer()
        v.init(false, Ed25519PublicKeyParameters(pub, 0))
        v.update(env, 0, len)
        v.verifySignature(sig)
    } catch (e: Exception) {
        false
    }

    private fun x25519(priv: X25519PrivateKeyParameters, pub: ByteArray): ByteArray {
        if (pub.size != 32) throw Invalid("bad public key")
        val out = ByteArray(32)
        val ok = try {
            X25519Agreement().apply { init(priv) }.calculateAgreement(X25519PublicKeyParameters(pub, 0), out, 0)
            true
        } catch (e: IllegalStateException) {
            false // BC throws on an all-zero (low-order) result
        }
        if (!ok || out.all { it == 0.toByte() }) throw Invalid("low-order key")
        return out
    }

    private fun deriveKey(kind: Byte, shared: ByteArray, epk: ByteArray, recipientEncPub: ByteArray, senderSigPub: ByteArray?): ByteArray {
        val info = INFO_PREFIX + byteArrayOf(kind) + (if (kind == KIND_PAIR) ByteArray(0) else senderSigPub!!)
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(shared, epk + recipientEncPub, info))
        return ByteArray(32).also { gen.generateBytes(it, 0, 32) }
    }

    private fun aad(header: ByteArray, kind: Byte, senderSigPub: ByteArray?): ByteArray =
        header.copyOfRange(0, 34) + (if (kind == KIND_PAIR) ByteArray(0) else senderSigPub!!)

    fun pad(data: ByteArray): ByteArray {
        val need = data.size + 4
        if (need > MAX_BUCKET) throw IllegalArgumentException("payload too large")
        var bucket = MIN_BUCKET
        while (bucket < need) bucket *= 2
        val out = ByteArray(bucket)
        out[0] = (data.size ushr 24).toByte()
        out[1] = (data.size ushr 16).toByte()
        out[2] = (data.size ushr 8).toByte()
        out[3] = data.size.toByte()
        System.arraycopy(data, 0, out, 4, data.size)
        return out
    }

    fun unpad(p: ByteArray): ByteArray {
        if (p.size < 4) throw Invalid("short plaintext")
        val len = ((p[0].toInt() and 0xff) shl 24) or ((p[1].toInt() and 0xff) shl 16) or
            ((p[2].toInt() and 0xff) shl 8) or (p[3].toInt() and 0xff)
        if (len < 0 || len > p.size - 4) throw Invalid("bad length")
        return p.copyOfRange(4, 4 + len)
    }

    fun fingerprint(phoneSig: ByteArray, phoneEnc: ByteArray, readerSig: ByteArray, readerEnc: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(phoneSig + phoneEnc + readerSig + readerEnc)
        return h.copyOfRange(0, 10).joinToString("") { "%02x".format(it) }.chunked(4).joinToString("-")
    }

    fun b64e(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun b64d(s: String): ByteArray = try {
        Base64.getUrlDecoder().decode(s)
    } catch (e: IllegalArgumentException) {
        ByteArray(0)
    }

    fun randomHex(bytes: Int): String = ByteArray(bytes).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
