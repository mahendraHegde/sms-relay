package dev.smsrelay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.smsrelay.core.Envelope
import dev.smsrelay.core.Pairing
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Long-term keys and the pairing code, stored as one blob encrypted with an Android Keystore key.
 * The Keystore key needs no user authentication, so the relay keeps working after a reboot once
 * the phone has been unlocked once.
 */
class Secrets private constructor(private val ctx: Context) {
    companion object {
        private const val ALIAS = "sms-relay-master"
        private const val PREFS = "secrets"
        private const val BLOB = "blob"

        @Volatile private var instance: Secrets? = null
        fun get(ctx: Context): Secrets =
            instance ?: synchronized(this) { instance ?: Secrets(ctx.applicationContext).also { instance = it } }
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var data: JSONObject = load()

    val phoneSig: Ed25519PrivateKeyParameters get() = Ed25519PrivateKeyParameters(Envelope.b64d(data.getString("sig")), 0)
    val phoneEnc: X25519PrivateKeyParameters get() = X25519PrivateKeyParameters(Envelope.b64d(data.getString("enc")), 0)
    val clientId: String get() = data.getString("cid")

    /** The committed pairing. */
    val pairing: Pairing?
        get() = parse(data.optString("pairing"))

    /** A broker the reader asked us to move to; becomes [pairing] once the reader is heard on it. */
    val candidate: Pairing?
        get() = parse(data.optString("candidate"))

    val candidateDeadline: Long get() = data.optLong("candidateDeadline", 0)

    /** Id of the move command; the reader quotes it once it has heard us on the candidate broker. */
    val candidateCmd: String get() = data.optString("candidateCmd")

    /** What to connect with right now: the candidate while its trial window is open. */
    fun activePairing(now: Long): Pairing? =
        candidate?.takeIf { now < candidateDeadline } ?: pairing

    @Synchronized
    fun setPairing(code: String?) = edit {
        remove("candidate")
        remove("candidateDeadline")
        remove("candidateCmd")
        if (code == null) remove("pairing") else put("pairing", code.trim())
    }

    @Synchronized
    fun setCandidate(code: String, deadline: Long, cmd: String) =
        edit { put("candidate", code).put("candidateDeadline", deadline).put("candidateCmd", cmd) }

    @Synchronized
    fun commitCandidate() = edit {
        optString("candidate").takeIf { it.isNotEmpty() }?.let { put("pairing", it) }
        remove("candidate")
        remove("candidateDeadline")
        remove("candidateCmd")
    }

    @Synchronized
    fun dropCandidate() = edit {
        remove("candidate")
        remove("candidateDeadline")
        remove("candidateCmd")
    }

    /** Persist first; only a durable write changes what the app runs with. */
    private fun edit(change: JSONObject.() -> Unit) {
        val next = JSONObject(data.toString()).apply(change)
        save(next)
        data = next
    }

    private fun parse(code: String): Pairing? =
        code.takeIf { it.isNotEmpty() }?.let { runCatching { Pairing.parse(it) }.getOrNull() }

    fun fingerprint(): String? {
        val p = pairing ?: return null
        return Envelope.fingerprint(
            phoneSig.generatePublicKey().encoded, phoneEnc.generatePublicKey().encoded, p.readerSigPub, p.readerEncPub,
        )
    }

    private fun load(): JSONObject {
        val stored = prefs.getString(BLOB, null)
        if (stored != null) return JSONObject(String(decrypt(Envelope.b64d(stored)), Charsets.UTF_8))
        val rng = SecureRandom()
        val fresh = JSONObject()
            .put("sig", Envelope.b64e(Ed25519PrivateKeyParameters(rng).encoded))
            .put("enc", Envelope.b64e(X25519PrivateKeyParameters(rng).encoded))
            .put("cid", "srp-" + Envelope.randomHex(8))
        save(fresh)
        return fresh
    }

    private fun save(j: JSONObject) {
        // commit(), not apply(): a lost write here would orphan the phone's identity.
        val ok = prefs.edit().putString(BLOB, Envelope.b64e(encrypt(j.toString().toByteArray(Charsets.UTF_8)))).commit()
        if (!ok) throw IllegalStateException("could not persist secrets")
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}
