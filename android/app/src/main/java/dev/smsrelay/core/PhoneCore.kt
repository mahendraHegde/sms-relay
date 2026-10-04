package dev.smsrelay.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject

/** One SMS row as it exists in the phone's SMS provider. */
data class ProviderSms(
    val providerId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long,
    /** Provider message type: [TYPE_INBOX], [TYPE_SENT], [TYPE_DRAFT] or [TYPE_FAILED]. */
    val type: Int = TYPE_INBOX,
) {
    companion object {
        // Values of android.provider.Telephony.Sms.TYPE (kept here so the core stays Android-free).
        const val TYPE_INBOX = 1
        const val TYPE_SENT = 2
        const val TYPE_DRAFT = 3
        const val TYPE_FAILED = 5
        /** Types that are relayed; outbox/queued rows wait until they are sent or failed. */
        val RELAYED = intArrayOf(TYPE_INBOX, TYPE_SENT, TYPE_DRAFT, TYPE_FAILED)
    }
}

/** The forwarding record kept on the phone. [env] is the sealed envelope, null once the reader acked it. */
data class MsgRecord(
    val id: String,
    val providerId: Long,
    val address: String,
    val bodyHash: String,
    val created: Long,
    val env: ByteArray?,
    /** Delivery generation: changes on every re-seal so an ack for an older copy cannot clear a newer one. */
    val gen: String = "",
)

interface SmsStore {
    fun query(providerId: Long): ProviderSms?
    fun delete(providerId: Long): Boolean
    /** Rows of the [ProviderSms.RELAYED] types received/written since [sinceMs]; newest [limit], oldest first. */
    fun messagesSince(sinceMs: Long, limit: Int): List<ProviderSms>
}

interface RelayStore {
    /** A message the phone sent because [rule] matched incoming message [srcId] (relay id). */
    fun addForward(providerId: Long, rule: String, srcId: String)
    fun forwardOf(providerId: Long): Pair<String, String>?
    fun insert(rec: MsgRecord)
    fun get(id: String): MsgRecord?
    fun byProviderId(providerId: Long): MsgRecord?
    fun setEnv(id: String, env: ByteArray, gen: String)
    /**
     * Un-acked records to send, oldest first: never-sent ones, plus (unless [onlyNew]) ones last
     * sent at or before [sentBefore]. Send times are stored, so restarts and reconnects never re-send in bulk.
     */
    fun toSend(onlyNew: Boolean, sentBefore: Long, limit: Int): List<MsgRecord>
    /** Record the send time of copy [gen] of [id] (a late ack for an older copy changes nothing). */
    fun markSent(id: String, gen: String, ts: Long)
    fun unackedCount(): Int
    fun markAcked(id: String, gen: String)
    fun remove(id: String)
    fun cmdResult(cmd: String): String?
    fun saveCmdResult(cmd: String, resultJson: String, ts: Long)
    fun pruneCmds(beforeMs: Long)
    /** Ids of records that vanished on the phone (deleted there, draft replaced) and the reader must hear about. */
    fun addGone(id: String)
    fun gonePending(limit: Int): List<String>
    fun clearGone(ids: List<String>)
    fun getMeta(key: String): String?
    fun setMeta(key: String, value: String)
}

/** Sends an SMS from the phone (forwarding); returns the provider id of the queued message. */
fun interface SmsOut {
    fun send(address: String, text: String): Long?
}

interface Transport {
    /**
     * Queue for sending. Returns false when not connected (the outbox will retry later).
     * [onDelivered] runs once the broker has acknowledged it (any thread).
     */
    fun publish(env: ByteArray, onDelivered: (() -> Unit)? = null): Boolean
}

/**
 * State that must outlive a PhoneCore: the core is rebuilt on every reconnect, and a fresh
 * seen-list would let a captured hello from the last hour be replayed after each rebuild.
 * Relay keeps one per process.
 */
class CoreState {
    internal var lastHelloAt = Long.MIN_VALUE / 2
    internal val seenHellos = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > 500
    }
    /** Envelopes and commands rejected since the process started (reported in status). */
    var rejected = 0
        internal set
    /** Forwards skipped by the rate limit since the process started (reported in status). */
    var fwdBlocked = 0
        internal set
}

class Keys(
    val phoneSig: Ed25519PrivateKeyParameters,
    val phoneEnc: X25519PrivateKeyParameters,
    val readerSigPub: ByteArray,
    val readerEncPub: ByteArray,
)

/**
 * Everything the phone does, independent of Android so it can be unit tested on the JVM.
 * Android wires real implementations of [SmsStore], [RelayStore] and [Transport].
 */
class PhoneCore(
    private val keys: Keys,
    private val sms: SmsStore,
    private val store: RelayStore,
    private val transport: Transport,
    private val status: () -> JSONObject,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Called with a validated pairing code and the command's id after the reader asked to
     * move to a new broker. Also called again when that same move command is replayed, because the
     * first switch may have been lost to a process death; the caller ignores a move to where it
     * already is.
     */
    private val onRebroker: (code: String, cmd: String) -> Unit = { _, _ -> },
    private val state: CoreState = CoreState(),
    /** How forwarded messages leave the phone; null disables forwarding (tests, unpaired). */
    private val smsOut: SmsOut? = null,
) {
    companion object {
        const val META_CONFIRMED = "pair_confirmed"
        /** Set on the first valid reader command after confirmation: the reader has our keys. */
        const val META_READER_SEEN = "reader_seen"
        const val META_PAIRED_AT = "paired_at"
        const val META_LAST_REBROKER = "last_rebroker"
        const val META_RECONCILED = "reconciled_at"
        const val META_MAX_CMD_TS = "max_cmd_ts"
        const val META_RULES = "rules"
        const val META_ALLOW = "allowlist"
        const val META_FWD_LOG = "fwd_log"
        /** Forwarding limits: they cap SMS charges and the damage of an over-broad rule. */
        const val FWD_PER_HOUR = 20
        const val FWD_PER_DAY = 100
        /** Forwarded text is cut to this many characters (the limits count messages, not segments). */
        const val FWD_MAX_BODY = 300
        /** Only messages this recent are forwarded (once, when first recorded): never history or an old backlog. */
        const val FWD_MAX_AGE_MS = 60 * 60_000L
        const val FWD_MAX_SKEW_MS = 5 * 60_000L
        /** A hello only counts if its ts is this close to our clock: replays of older ones do nothing. */
        const val HELLO_WINDOW_MS = 60 * 60_000L
        /** Never send the same unacked envelope more often than this. */
        const val RESEND_GAP_MS = 10 * 60_000L
        /**
         * The broker keeps nothing for absent subscribers, so publishing while no reader listens is
         * wasted radio time. A reader counts as listening for this long after its last hello.
         */
        const val READER_ACTIVE_MS = 3 * 60_000L
        const val MAX_IDS = 100
        const val FLUSH_LIMIT = 200
        const val BACKFILL_LIMIT = 5000
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
        const val MAX_SKEW_MS = 24L * 3600 * 1000
        /** Must exceed MAX_AGE_MS + MAX_SKEW_MS, or a pruned command could be replayed while still in its window. */
        const val CMD_RETENTION_MS = 9L * 24 * 3600 * 1000

        /**
         * Whether an accepted command makes a trial broker permanent: only a hello in which the
         * reader quotes the move, which it does after hearing the phone on the new broker.
         */
        fun confirmsMove(msg: JSONObject?, candidateCmd: String): Boolean =
            msg != null && candidateCmd.isNotEmpty() && msg.optString("t") == "hello" && msg.optString("moved") == candidateCmd
        /** Largest sms JSON before padding; leaves room under Envelope's 64 KiB ceiling. */
        const val MAX_SMS_JSON = 60_000
    }

    val rejected: Int get() = state.rejected


    private fun confirmed() = store.getMeta(META_CONFIRMED) == "1"

    private fun readerListening() = confirmed() && now() - state.lastHelloAt < READER_ACTIVE_MS

    /** Publish an outbox record; its send time is recorded only once the broker acknowledges it. */
    private fun send(rec: MsgRecord): Boolean {
        val env = rec.env ?: return false
        return transport.publish(env) { store.markSent(rec.id, rec.gen, now()) }
    }

    /**
     * The user compared the fingerprint on this phone with the reader's and they match. Until then
     * the phone relays nothing and obeys no command: a swapped pairing code (clipboard, keyboard)
     * must not be able to pull the SMS history before the user notices a mismatch.
     */
    @Synchronized
    fun confirmPairing() {
        store.setMeta(META_CONFIRMED, "1") // the reader's next hello then gets status and messages
    }

    /** Identity of a provider row: sender, text and receive time (ids can be reused after a provider reset). */
    fun bodyHash(s: ProviderSms) = Envelope.sha256Hex(s.address + "\u0000" + s.body + "\u0000" + s.date)

    /** A provider row was written (incoming SMS, a sent or failed message, a saved draft). */
    @Synchronized
    fun onRow(s: ProviderSms) {
        val rec = track(s) ?: return
        // Reader closed: just keep it; the reader's first hello pulls everything waiting.
        if (readerListening()) send(rec)
    }

    // ---- forwarding ----

    /** Parsed once per stored version, not on every incoming SMS. */
    private var rulesCache: Pair<String, Rules.RuleSet?>? = null

    fun rules(): Rules.RuleSet? {
        val raw = store.getMeta(META_RULES) ?: return null
        rulesCache?.let { (s, set) -> if (s == raw) return set }
        return runCatching { Rules.parse(JSONObject(raw)) }.getOrNull().also { rulesCache = raw to it }
    }

    fun allowlist(): List<Allowlist.Entry> = Allowlist.parse(store.getMeta(META_ALLOW))

    /** Phone side, behind the screen lock: propose a destination. It stays inactive until the reader approves it. */
    @Synchronized
    fun proposeNumber(raw: String): Boolean {
        val n = Allowlist.normalize(raw) ?: return false
        val list = allowlist()
        if (list.any { it.number == n } || list.size >= Allowlist.MAX_ENTRIES) return false
        // A fresh proposal id: only an approval naming it activates the number.
        store.setMeta(META_ALLOW, Allowlist.toJson(list + Allowlist.Entry(n, active = false, proposal = Envelope.randomHex(8))))
        return true // the reader sees it in the status answering its next hello
    }

    /** Phone side: remove a destination (always allowed: it can only reduce what is forwarded). */
    @Synchronized
    fun removeNumber(n: String) {
        store.setMeta(META_ALLOW, Allowlist.toJson(allowlist().filter { it.number != n }))
    }

    /** Apply the rules to a live incoming message: forward to every matching rule's approved numbers. */
    private fun forward(s: ProviderSms, srcId: String) {
        val out = smsOut ?: return
        // Age gate both ways: a row dated far ahead (clock jumps) is not "live" either.
        if (!confirmed() || now() - s.date > FWD_MAX_AGE_MS || s.date - now() > FWD_MAX_SKEW_MS) return
        val set = rules() ?: return
        val allowed = Allowlist.active(allowlist())
        // Loop guard: never forward what a destination sent (two phones forwarding to each other).
        if (allowed.any { sameNumber(it, s.address) }) return
        val done = HashSet<String>()
        for (rule in set.rules) {
            if (!rule.enabled || !Rules.matches(rule.`when`, s.address, s.body)) continue
            for (dest in rule.forwardTo) {
                if (dest !in allowed || !done.add(dest)) continue
                if (!takeForwardSlot()) {
                    state.fwdBlocked++ // counts copies, not messages
                    continue
                }
                val pid = runCatching { out.send(dest, forwardText(s)) }.getOrNull() ?: continue
                store.addForward(pid, rule.name, srcId)
            }
        }
    }

    private fun forwardText(s: ProviderSms): String {
        val body = if (s.body.length <= FWD_MAX_BODY) s.body
        else s.body.take(FWD_MAX_BODY).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it } + "… (cut)"
        return "From ${s.address}: $body"
    }

    /** Rate limit, persisted so restarts do not reset it. */
    private fun takeForwardSlot(): Boolean {
        val t = now()
        val log = runCatching { JSONArray(store.getMeta(META_FWD_LOG) ?: "[]") }.getOrDefault(JSONArray())
        val recent = (0 until log.length()).map { log.optLong(it) }.filter { it > t - 24 * 3_600_000L }
        if (recent.size >= FWD_PER_DAY || recent.count { it > t - 3_600_000L } >= FWD_PER_HOUR) return false
        store.setMeta(META_FWD_LOG, JSONArray(recent + t).toString())
        return true
    }

    private fun sameNumber(a: String, b: String): Boolean {
        val da = a.filter { it in '0'..'9' }
        val db = b.filter { it in '0'..'9' }
        if (da.isNotEmpty() && da == db) return true // short codes too (an allowlisted 12345 replying)
        if (da.length < 6 || db.length < 6) return false
        val n = minOf(da.length, db.length, 10)
        return da.takeLast(n) == db.takeLast(n)
    }

    private fun setRules(cmd: String, msg: JSONObject): JSONObject {
        val result = JSONObject().put("t", "result").put("op", "rules").put("cmd", cmd).put("ts", now())
        val set = try {
            Rules.parse(msg.optJSONObject("set") ?: throw Rules.Invalid("set missing"))
        } catch (e: Exception) {
            return result.put("ok", false).put("error", e.message ?: "invalid rules")
        }
        val old = rules()
        val current = old?.version ?: 0
        if (set.version <= current) return result.put("ok", false).put("error", "older than the rules on the phone (version $current)")
        // Only destinations new to the rules need approval now. Numbers already named may have been
        // removed since: they stay harmless (forward() checks the approved list on every message), and
        // refusing them would block every later edit, including switching an unrelated rule off.
        val active = Allowlist.active(allowlist())
        // Keyed by rule id: a removed number can stay where it was, but not move into another rule.
        val known = old?.rules.orEmpty().associate { it.id to it.forwardTo.toSet() }
        val unknown = set.rules.flatMap { r -> r.forwardTo.filter { it !in active && it !in known[r.id].orEmpty() } }.distinct()
        if (unknown.isNotEmpty()) return result.put("ok", false).put("error", "not approved on the phone's allowlist: ${unknown.joinToString()}")
        store.setMeta(META_RULES, Rules.toJson(set).toString())
        return result.put("ok", true).put("version", set.version)
    }

    /**
     * Reader side of dual control. `approve` activates proposals the phone made, each named by number
     * and proposal id (`nums` and `p`, same order), so it cannot add numbers and an old approval cannot
     * activate a later proposal of the same number. `revoke` removes numbers.
     */
    private fun approve(cmd: String, msg: JSONObject, activate: Boolean): JSONObject {
        val nums = msg.optJSONArray("nums") ?: JSONArray()
        val ps = msg.optJSONArray("p") ?: JSONArray()
        val list = allowlist()
        val next = if (activate) {
            val named = (0 until nums.length()).mapNotNull { i -> Allowlist.normalize(nums.optString(i))?.let { it to ps.optString(i) } }.toSet()
            list.map { if (it.proposal.isNotEmpty() && (it.number to it.proposal) in named) it.copy(active = true) else it }
        } else {
            val gone = (0 until nums.length()).mapNotNull { Allowlist.normalize(nums.optString(it)) }.toSet()
            list.filter { it.number !in gone }
        }
        store.setMeta(META_ALLOW, Allowlist.toJson(next))
        return JSONObject().put("t", "result").put("op", if (activate) "approve" else "revoke").put("cmd", cmd).put("ts", now())
.put("ok", true) // the reader reads the list from the status answering its next hello
    }

    /**
     * Start relaying [s] unless it is already tracked. A tracked record whose hash differs means
     * the provider reused the id for a different message (the old row is gone): replace it.
     */
    private fun track(s: ProviderSms, existing: MsgRecord? = store.byProviderId(s.providerId)): MsgRecord? {
        if (s.type !in ProviderSms.RELAYED) return null // outbox/queued: wait until sent or failed
        if (existing != null) {
            if (existing.bodyHash == bodyHash(s)) return null
            forget(existing.id) // the provider reused the id (or a draft was rewritten): the reader's copy is gone
        }
        return record(s).also {
            store.insert(it)
            // Forwarding happens here, once per message, whichever path records it first (a live
            // delivery, or the reconcile scan catching a row it missed); the age gate keeps history out.
            if (s.type == ProviderSms.TYPE_INBOX) forward(s, it.id)
        }
    }

    /**
     * Relay provider rows received since pairing that never made it into the outbox (crash between
     * insert and record). Scans from the last successful pass (minus a day of margin), so a long
     * stretch of failures is still caught up.
     */
    @Synchronized
    fun reconcile() {
        val pairedAt = store.getMeta(META_PAIRED_AT)?.toLongOrNull() ?: return
        // Clamped: a cursor saved while the clock ran ahead must not hide rows until real time catches up.
        val last = minOf(store.getMeta(META_RECONCILED)?.toLongOrNull() ?: pairedAt, now())
        val started = now()
        queueInbox(maxOf(pairedAt, last - 24L * 3600 * 1000))
        store.setMeta(META_RECONCILED, started.toString())
    }

    /**
     * Rows deleted on the phone itself (the local messaging UI, or a draft replaced or sent). Their
     * records go, and the reader is told (re-sent with every hello until it acknowledges).
     */
    @Synchronized
    fun onLocalDelete(providerIds: List<Long>) {
        for (pid in providerIds) {
            val rec = store.byProviderId(pid) ?: continue
            // The id may already belong to a newer message (ids are reused): only forget the record
            // if its row is really gone or is no longer the message we relayed.
            val row = sms.query(pid)
            if (row == null || bodyHash(row) != rec.bodyHash) forget(rec.id)
        }
        if (readerListening()) sendGone()
    }

    private fun forget(id: String) {
        store.remove(id)
        store.addGone(id)
    }

    private fun sendGone() {
        val ids = store.gonePending(MAX_IDS)
        if (ids.isNotEmpty()) send(JSONObject().put("t", "gone").put("ts", now()).put("ids", JSONArray(ids)))
    }

    /** True until the reader has spoken to us after confirmation (it may have missed our keys). */
    fun needsAnnounce(): Boolean = !(confirmed() && store.getMeta(META_READER_SEEN) == "1")

    /** Announce our keys if [needsAnnounce]; returns whether it did. */
    @Synchronized
    fun announceIfNeeded(): Boolean {
        if (!needsAnnounce()) return false
        publishPair()
        return true
    }

    /** Connected: announce if needed, and send what was never sent if a reader is listening. */
    @Synchronized
    fun onConnected() {
        if (!confirmed()) {
            publishPair()
            return
        }
        if (store.getMeta(META_READER_SEEN) != "1") publishPair()
        // Prune by the newest command time seen, not only our clock: a clock pushed forward and back
        // again must not drop log entries for commands that are still replayable.
        val newest = store.getMeta(META_MAX_CMD_TS)?.toLongOrNull() ?: now()
        store.pruneCmds(minOf(now(), newest) - CMD_RETENTION_MS)
        // No unsolicited status: the reader only trusts answers to its own hellos.
        if (readerListening()) flush(onlyNew = true)
    }

    /** Returns the decrypted command when the envelope was a valid command from the paired reader, else null. */
    @Synchronized
    fun onDown(env: ByteArray): JSONObject? {
        val msg = try {
            Envelope.open(env, Envelope.KIND_DOWN, keys.readerSigPub, keys.phoneEnc)
        } catch (e: Envelope.Invalid) {
            state.rejected++
            return null
        }
        val cmd = msg.optString("cmd")
        val ts = msg.optLong("ts", -1)
        val t = now()
        if (cmd.length != 32 || ts < t - MAX_AGE_MS || ts > t + MAX_SKEW_MS) {
            state.rejected++
            return null
        }
        if (!confirmed()) {
            state.rejected++
            return null
        }
        if (store.getMeta(META_READER_SEEN) != "1") store.setMeta(META_READER_SEEN, "1")
        if (ts > (store.getMeta(META_MAX_CMD_TS)?.toLongOrNull() ?: 0)) store.setMeta(META_MAX_CMD_TS, ts.toString())

        when (msg.optString("t")) {
            "hello" -> {
                // Hellos trigger traffic, so a replayed one must do nothing: only recent, unseen ones count.
                if (kotlin.math.abs(ts - t) > HELLO_WINDOW_MS || state.seenHellos.put(cmd, t) != null) {
                    state.rejected++
                    return null
                }
                state.lastHelloAt = t
                // The reader treats only answers to its own hellos as proof of life. The rule set rides
                // along only when the reader's copy (its "rv") is out of date.
                publishStatus(replyTo = cmd, readerRv = msg.optLong("rv", -1))
                flush() // never-sent go out now; re-sends are limited per envelope (RESEND_GAP_MS)
            }
            "ack" -> {
                val ids = ids(msg)
                val gens = msg.optJSONArray("gens")
                if (gens != null && gens.length() == msg.optJSONArray("ids")?.length()) {
                    val all = msg.optJSONArray("ids")!!
                    for (i in 0 until all.length()) {
                        val id = all.optString(i)
                        if (id in ids) store.markAcked(id, gens.optString(i))
                    }
                }
                // The reader has recorded these deletions.
                ids(msg, "gone").takeIf { it.isNotEmpty() }?.let { store.clearGone(it) }
            }
            "delete" -> idempotent(cmd) { delete(cmd, ids(msg)) }
            "backfill" -> idempotent(cmd) { backfill(cmd, msg.optInt("days", 0), msg.optBoolean("resend", false)) }
            "rules" -> idempotent(cmd) { setRules(cmd, msg) }
            "approve" -> idempotent(cmd) { approve(cmd, msg, activate = true) }
            "revoke" -> idempotent(cmd) { approve(cmd, msg, activate = false) }
            "rebroker" -> {
                val replay = store.cmdResult(cmd) != null
                if (replay && store.getMeta(META_LAST_REBROKER) != cmd) {
                    // A newer move replaced it, or it was rolled back: tell the reader it is not in
                    // effect, so a reader that missed the first answer does not switch alone.
                    send(JSONObject().put("t", "result").put("op", "rebroker").put("cmd", cmd).put("ts", now()).put("ok", false).put("stale", true))
                    return msg
                }
                var code: String? = null
                idempotent(cmd) { rebroker(cmd, msg).also { code = it.second }.first }
                if (!replay) {
                    if (code != null) store.setMeta(META_LAST_REBROKER, cmd)
                } else {
                    // The most recent move: re-offer it (the first switch may have been lost).
                    code = rebroker(cmd, msg).second
                }
                code?.let { onRebroker(it, cmd) } // after the result went out on the current broker
            }
            else -> {
                state.rejected++
                return null
            }
        }
        return msg
    }

    /**
     * Send un-acked envelopes to a listening reader, each at most once per [RESEND_GAP_MS]; with
     * [onlyNew], only never-sent ones (reconnects and the watchdog use that).
     */
    @Synchronized
    fun flush(onlyNew: Boolean = false) {
        if (!readerListening()) return
        val t = now()
        for (rec in store.toSend(onlyNew, t - RESEND_GAP_MS, FLUSH_LIMIT)) {
            if (!send(rec)) return
        }
        if (!onlyNew) sendGone() // until the reader acknowledges them
    }

    private fun idempotent(cmd: String, run: () -> JSONObject?) {
        val prior = store.cmdResult(cmd)
        if (prior != null) {
            send(JSONObject(prior).put("ts", now()))
            return
        }
        val result = run() ?: return
        store.saveCmdResult(cmd, result.toString(), now())
        send(result)
        flush(onlyNew = true) // e.g. what a backfill just queued
    }

    private fun delete(cmd: String, ids: List<String>): JSONObject {
        val res = JSONObject()
        for (id in ids) res.put(id, deleteOne(id))
        return JSONObject().put("t", "result").put("op", "delete").put("cmd", cmd).put("ts", now()).put("res", res)
    }

    private fun deleteOne(id: String): String {
        val rec = store.get(id) ?: return "absent"
        val row = sms.query(rec.providerId)
        if (row == null) {
            store.remove(id)
            return "absent"
        }
        if (bodyHash(row) != rec.bodyHash) return "mismatch"
        return if (sms.delete(rec.providerId)) {
            store.remove(id)
            "deleted"
        } else {
            "error"
        }
    }

    private fun backfill(cmd: String, days: Int, resend: Boolean): JSONObject? {
        if (days !in 1..3650) {
            state.rejected++
            return null
        }
        val queued = queueInbox(now() - days * 24L * 3600 * 1000, resend)
        return JSONObject().put("t", "result").put("op", "backfill").put("cmd", cmd).put("ts", now()).put("queued", queued)
    }

    /** Queue provider rows not yet relayed; with [resend], also re-send already acked ones (same id). */
    private fun queueInbox(sinceMs: Long, resend: Boolean = false): Int {
        var n = 0
        for (s in sms.messagesSince(sinceMs, BACKFILL_LIMIT)) {
            // One bad row (e.g. an oversized or malformed SMS) must not stop the rest.
            try {
                val existing = store.byProviderId(s.providerId)
                // An explicit resend always issues a new generation, even for a copy still waiting for
                // an ack: a late ack meant for the previous reader must not cancel this one.
                if (resend && existing != null && existing.bodyHash == bodyHash(s)) {
                    val gen = Envelope.randomHex(8)
                    store.setEnv(existing.id, seal(Envelope.KIND_UP, smsPayload(existing.id, gen, s)), gen) // clears the send time
                    n++
                } else if (track(s, existing) != null) {
                    n++
                }
            } catch (e: Exception) {
                state.rejected++
            }
        }
        return n
    }

    /** Validate new broker settings. Returns the result to send and, when valid, the new pairing code. */
    private fun rebroker(cmd: String, msg: JSONObject): Pair<JSONObject, String?> {
        val j = JSONObject()
            .put("h", msg.optString("host")).put("p", msg.optInt("port", 8883)).put("t", msg.optString("topic"))
            .put("pu", msg.optString("user")).put("pp", msg.optString("pass"))
            .put("rs", Envelope.b64e(keys.readerSigPub)).put("re", Envelope.b64e(keys.readerEncPub))
        val code = "SR1." + Envelope.b64e(j.toString().toByteArray(Charsets.UTF_8))
        val ok = runCatching { Pairing.parse(code) }.isSuccess
        val result = JSONObject().put("t", "result").put("op", "rebroker").put("cmd", cmd).put("ts", now()).put("ok", ok)
        return result to (if (ok) code else null)
    }

    private fun record(s: ProviderSms): MsgRecord {
        val id = Envelope.randomHex(16)
        val gen = Envelope.randomHex(8)
        return MsgRecord(id, s.providerId, s.address, bodyHash(s), now(), seal(Envelope.KIND_UP, smsPayload(id, gen, s)), gen)
    }

    /** The sms payload, with the body cut (and `cut` = chars dropped) if it would not fit an envelope. */
    private fun smsPayload(id: String, gen: String, s: ProviderSms): JSONObject {
        var keep = s.body.length
        while (true) {
            val p = JSONObject()
                .put("t", "sms").put("id", id).put("gen", gen).put("kind", kindOf(s.type))
                .put("addr", s.address.take(256)).put("body", s.body.substring(0, keep))
                .put("sent", s.dateSent).put("rcvd", s.date).put("ts", now())
            if (keep < s.body.length) p.put("cut", s.body.length - keep)
            store.forwardOf(s.providerId)?.let { (rule, src) -> p.put("rule", rule).put("fwdOf", src) }
            if (p.toString().toByteArray(Charsets.UTF_8).size <= MAX_SMS_JSON) return p
            keep /= 2
            if (keep > 0 && Character.isHighSurrogate(s.body[keep - 1])) keep--
        }
    }

    private fun kindOf(type: Int) = when (type) {
        ProviderSms.TYPE_SENT -> "sent"
        ProviderSms.TYPE_DRAFT -> "draft"
        ProviderSms.TYPE_FAILED -> "failed"
        else -> "in"
    }

    private fun ids(msg: JSONObject, field: String = "ids"): List<String> {
        val arr: JSONArray = msg.optJSONArray(field) ?: return emptyList()
        if (arr.length() > MAX_IDS) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.length == 32 }
    }

    /** Statuses only ever answer a hello (the reader ignores any other); [readerRv] is that hello's `rv`. */
    private fun publishStatus(replyTo: String, readerRv: Long) {
        val set = rules()
        val rv = set?.version ?: 0
        val s = status().put("t", "status").put("ts", now()).put("queue", store.unackedCount()).put("rej", rejected)
            .put("rv", rv).put("allow", Allowlist.toJsonArray(allowlist())).put("fwdBlocked", state.fwdBlocked)
        s.put("re", replyTo)
        if (set != null && readerRv != rv) s.put("rules", Rules.toJson(set))
        send(s)
    }

    private fun publishPair() {
        val p = JSONObject()
            .put("t", "pair").put("ts", now())
            .put("sigPub", Envelope.b64e(keys.phoneSig.generatePublicKey().encoded))
            .put("encPub", Envelope.b64e(keys.phoneEnc.generatePublicKey().encoded))
        transport.publish(seal(Envelope.KIND_PAIR, p))
    }

    private fun send(payload: JSONObject) {
        transport.publish(seal(Envelope.KIND_UP, payload))
    }

    private fun seal(kind: Byte, payload: JSONObject) = Envelope.seal(kind, keys.phoneSig, keys.readerEncPub, payload)
}
