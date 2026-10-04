package dev.smsrelay.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PhoneCoreTest {
    private val rng = SecureRandom()
    private val phoneSig = Ed25519PrivateKeyParameters(rng)
    private val phoneEnc = X25519PrivateKeyParameters(rng)
    private val readerSig = Ed25519PrivateKeyParameters(rng)
    private val readerEnc = X25519PrivateKeyParameters(rng)
    private var clock = 1_800_000_000_000L

    private val sms = FakeSms()
    private val store = FakeStore()
    private val net = FakeTransport()
    private val rebrokered = mutableListOf<String>()
    /** Forwarded SMS leave through here: (to, text). */
    private val outbox = mutableListOf<Pair<String, String>>()
    private val smsOut = SmsOut { to, text ->
        outbox += to to text
        sms.add(to, text, clock, ProviderSms.TYPE_SENT).providerId
    }
    private val core = PhoneCore(
        Keys(phoneSig, phoneEnc, readerSig.generatePublicKey().encoded, readerEnc.generatePublicKey().encoded),
        sms, store, net, { JSONObject().put("role", true) }, { clock }, { code, _ -> rebrokered += code },
        smsOut = smsOut,
    )

    init {
        core.confirmPairing() // most tests run on a confirmed pairing with a listening reader
        core.onDown(cmd("hello").second)
        net.sent.clear()
    }

    private fun unconfirm() = store.setMeta(PhoneCore.META_CONFIRMED, "")

    private fun cmd(t: String, extra: JSONObject.() -> Unit = {}): Pair<String, ByteArray> {
        val id = Envelope.randomHex(16)
        val p = JSONObject().put("t", t).put("cmd", id).put("ts", clock).apply(extra)
        return id to Envelope.seal(Envelope.KIND_DOWN, readerSig, phoneEnc.generatePublicKey().encoded, p)
    }

    private fun upMessages(): List<JSONObject> = net.sent.map {
        if (it[1] == Envelope.KIND_PAIR) Envelope.open(it, Envelope.KIND_PAIR, null, readerEnc)
        else Envelope.open(it, Envelope.KIND_UP, phoneSig.generatePublicKey().encoded, readerEnc)
    }

    /** Ack exactly the copies the reader received. */
    private fun ackFor(vararg msgs: JSONObject) = cmd("ack") {
        put("ids", JSONArray(msgs.map { it.getString("id") }))
        put("gens", JSONArray(msgs.map { it.getString("gen") }))
    }.second

    private fun receive(address: String, body: String): ProviderSms = sms.add(address, body, clock).also { core.onRow(it) }

    @Test fun incomingIsForwardedAndRetriedUntilAcked() {
        net.online = false
        receive("EXBANK", "OTP 111111")
        assertEquals(1, store.unackedCount())
        net.online = true
        core.onConnected()
        val forwarded = upMessages().filter { it.optString("t") == "sms" }
        assertEquals(1, forwarded.size)
        assertEquals("OTP 111111", forwarded[0].getString("body"))
        net.sent.clear()
        core.onDown(cmd("hello").second)
        assertEquals(0, upMessages().count { it.optString("t") == "sms" }) // just sent: not again yet
        clock += PhoneCore.RESEND_GAP_MS
        net.sent.clear()
        core.onDown(cmd("hello").second)
        assertEquals(1, upMessages().count { it.optString("t") == "sms" }) // re-sent: not acked yet

        core.onDown(cmd("ack") { put("ids", JSONArray(listOf(forwarded[0].getString("id")))) }.second) // no gens: ignored
        assertEquals(1, store.unackedCount())
        core.onDown(ackFor(forwarded[0]))
        clock += PhoneCore.RESEND_GAP_MS
        net.sent.clear()
        core.onDown(cmd("hello").second)
        assertEquals(0, upMessages().count { it.optString("t") == "sms" })
        assertEquals(0, store.unackedCount())
    }

    @Test fun helloIsAnsweredWithAStatusNamingIt() {
        val (c, env) = cmd("hello")
        core.onDown(env)
        assertEquals(c, upMessages().first { it.optString("t") == "status" }.getString("re"))
        net.sent.clear()
        core.onConnected()
        assertFalse(upMessages().any { it.optString("t") == "status" }) // never unsolicited
    }

    @Test fun nothingIsPublishedWhileNoReaderListens() {
        clock += PhoneCore.READER_ACTIVE_MS
        receive("EXBANK", "OTP 5")
        core.onConnected()
        core.flush()
        assertTrue(net.sent.isEmpty())
        core.onDown(cmd("hello").second) // reader opens: everything waiting arrives
        assertEquals(listOf("OTP 5"), upMessages().filter { it.optString("t") == "sms" }.map { it.getString("body") })
    }

    @Test fun aPublishTheBrokerNeverAcknowledgedGoesOutAgainOnTheNextHello() {
        net.acks = false
        receive("EXBANK", "OTP 7") // lost in a half-open connection
        net.acks = true
        net.sent.clear()
        core.onDown(cmd("hello").second) // after the reconnect: not marked sent, so it is resent now
        assertEquals(listOf("OTP 7"), upMessages().filter { it.optString("t") == "sms" }.map { it.getString("body") })
    }

    @Test fun explicitResendRenewsTheGenerationEvenWhileAnAckIsOutstanding() {
        receive("A", "one")
        val first = upMessages().first { it.optString("t") == "sms" } // previous reader got it, ack delayed
        core.onDown(cmd("backfill") { put("days", 30); put("resend", true) }.second)
        core.onDown(ackFor(first)) // the late ack for the old copy
        assertEquals(1, store.unackedCount()) // the new copy is still owed to the restored reader
    }

    @Test fun sentDraftAndFailedAreForwardedWithTheirKind() {
        for ((type, body) in listOf(ProviderSms.TYPE_SENT to "on my way", ProviderSms.TYPE_DRAFT to "half writ", ProviderSms.TYPE_FAILED to "oops")) {
            core.onRow(sms.add("+15555550100", body, clock, type))
        }
        core.onRow(sms.add("+15555550100", "still sending", clock, 4)) // outbox: not until final
        val kinds = upMessages().filter { it.optString("t") == "sms" }.associate { it.getString("body") to it.getString("kind") }
        assertEquals(mapOf("on my way" to "sent", "half writ" to "draft", "oops" to "failed"), kinds)
        receive("EXBANK", "OTP 1")
        assertEquals("in", upMessages().last { it.optString("t") == "sms" }.getString("kind"))
    }

    @Test fun localDeleteIsReportedUntilTheReaderAcknowledges() {
        val row = receive("EXBANK", "OTP 2")
        val id = upMessages().first { it.optString("t") == "sms" }.getString("id")
        sms.rows.remove(row.providerId)
        net.sent.clear()
        core.onLocalDelete(listOf(row.providerId))
        assertEquals(listOf(id), upMessages().single { it.optString("t") == "gone" }.getJSONArray("ids").let { a -> List(a.length()) { a.getString(it) } })
        clock += PhoneCore.RESEND_GAP_MS
        net.sent.clear()
        core.onDown(cmd("hello").second) // not acknowledged yet: told again
        assertTrue(upMessages().any { it.optString("t") == "gone" })
        core.onDown(cmd("ack") { put("ids", JSONArray()); put("gens", JSONArray()); put("gone", JSONArray(listOf(id))) }.second)
        net.sent.clear()
        core.onDown(cmd("hello").second)
        assertFalse(upMessages().any { it.optString("t") == "gone" })
        assertNull(store.get(id))
    }

    @Test fun editedDraftReplacesTheOldRecordAndReportsItGone() {
        val d1 = sms.add("+15555550100", "Hel", clock, ProviderSms.TYPE_DRAFT)
        core.onRow(d1)
        val oldId = upMessages().single { it.optString("t") == "sms" }.getString("id")
        // The app rewrites the draft in place under the same provider id.
        val d2 = d1.copy(body = "Hello there")
        sms.rows[d1.providerId] = d2
        core.onRow(d2)
        assertTrue(oldId in store.gone)
        assertEquals("Hello there", upMessages().last { it.optString("t") == "sms" }.getString("body"))
    }

    // ---- forwarding ----

    private val dest = "+15555550100"

    private fun rulesCmd(version: Long, to: List<String> = listOf(dest), body: String = "OTP") = cmd("rules") {
        put("set", JSONObject().put("version", version).put("rules", JSONArray(listOf(
            JSONObject().put("id", "otp").put("name", "OTPs").put("forwardTo", JSONArray(to))
                .put("when", JSONObject().put("field", "body").put("op", "contains").put("value", body)),
        ))))
    }.second

    /** Approve the phone's current proposals of [nums] (number + proposal id), as the reader does. */
    private fun approve(vararg nums: String) = core.onDown(cmd("approve") {
        put("nums", JSONArray(nums.toList()))
        put("p", JSONArray(nums.map { n -> core.allowlist().firstOrNull { it.number == n }?.proposal ?: "" }))
    }.second)
    private fun revoke(vararg nums: String) = core.onDown(cmd("revoke") { put("nums", JSONArray(nums.toList())) }.second)

    private fun rule(id: String, to: List<String>, body: String, enabled: Boolean = true) =
        JSONObject().put("id", id).put("name", id).put("enabled", enabled).put("forwardTo", JSONArray(to))
            .put("when", JSONObject().put("field", "body").put("op", "contains").put("value", body))

    private fun setRules(version: Long, vararg rules: JSONObject): Boolean {
        core.onDown(cmd("rules") { put("set", JSONObject().put("version", version).put("rules", JSONArray(rules.toList()))) }.second)
        return lastResult("rules").getBoolean("ok")
    }
    private fun lastResult(op: String) = upMessages().last { it.optString("t") == "result" && it.optString("op") == op }

    /** Phone proposes, reader approves, reader sets a rule. */
    private fun setUpForwarding() {
        assertTrue(core.proposeNumber(dest))
        approve(dest)
        core.onDown(rulesCmd(1))
        assertTrue(lastResult("rules").getBoolean("ok"))
        outbox.clear()
    }

    @Test fun matchingIncomingSmsIsForwardedWithPrefixAndTagged() {
        setUpForwarding()
        receive("EXBANK", "Your OTP is 4321")
        assertEquals(listOf(dest to "From EXBANK: Your OTP is 4321"), outbox)
        // The forwarded copy (a sent row) is relayed tagged with the rule and the original message.
        val src = upMessages().last { it.optString("t") == "sms" && it.optString("kind") == "in" }.getString("id")
        val fwdRow = sms.rows.values.last { it.type == ProviderSms.TYPE_SENT }
        net.sent.clear()
        core.onRow(fwdRow)
        val tagged = upMessages().single { it.optString("t") == "sms" }
        assertEquals("OTPs", tagged.getString("rule"))
        assertEquals(src, tagged.getString("fwdOf"))
        receive("EXBANK", "no match here")
        assertEquals(1, outbox.size)
    }

    @Test fun removedNumberIsNeverForwardedToEvenIfARuleNamesIt() {
        setUpForwarding()
        core.removeNumber(dest)
        receive("EXBANK", "OTP 1")
        assertTrue(outbox.isEmpty())
        // Adding it back needs approval again before anything is forwarded.
        core.proposeNumber(dest)
        receive("EXBANK", "OTP 2")
        assertTrue(outbox.isEmpty())
        approve(dest)
        receive("EXBANK", "OTP 3")
        assertEquals(1, outbox.size)
    }

    @Test fun readerCannotAddADestinationOnlyApproveProposedOnes() {
        approve(dest) // never proposed on the phone
        assertTrue(core.allowlist().isEmpty())
        core.onDown(rulesCmd(1))
        assertFalse(lastResult("rules").getBoolean("ok"))
        core.proposeNumber(dest) // proposed but not approved
        core.onDown(rulesCmd(2))
        assertFalse(lastResult("rules").getBoolean("ok"))
        receive("EXBANK", "OTP 9")
        assertTrue(outbox.isEmpty())
    }

    @Test fun olderRuleSetsAreRejected() {
        setUpForwarding()
        core.onDown(rulesCmd(1, body = "anything"))
        assertFalse(lastResult("rules").getBoolean("ok"))
        assertEquals(1L, core.rules()!!.version)
    }

    @Test fun rateLimitCapsForwards() {
        setUpForwarding()
        repeat(PhoneCore.FWD_PER_HOUR + 5) { receive("EXBANK", "OTP $it") }
        assertEquals(PhoneCore.FWD_PER_HOUR, outbox.size)
        clock += 3_600_000L
        receive("EXBANK", "OTP later")
        assertEquals(PhoneCore.FWD_PER_HOUR + 1, outbox.size)
    }

    @Test fun neverForwardsWhatADestinationSentOrHistoryOrOldMessages() {
        setUpForwarding()
        receive("+1 555-555-0100", "OTP from the destination itself") // loop guard
        sms.add("EXBANK", "OTP in history", clock - 2 * 86_400_000L)
        core.onDown(cmd("backfill") { put("days", 7) }.second) // backlog: relayed, not forwarded
        val old = sms.add("EXBANK", "OTP delivered late", clock - 2 * PhoneCore.FWD_MAX_AGE_MS)
        core.onRow(old)
        assertTrue(outbox.isEmpty())
    }

    @Test fun statusCarriesTheRulesOnlyWhenTheReadersCopyIsStale() {
        setUpForwarding()
        net.sent.clear()
        core.onDown(cmd("hello") { put("rv", 0) }.second)
        val stale = upMessages().single { it.optString("t") == "status" }
        assertEquals(1L, stale.getLong("rv"))
        assertEquals(1L, stale.getJSONObject("rules").getLong("version"))
        net.sent.clear()
        core.onDown(cmd("hello") { put("rv", 1) }.second)
        assertFalse(upMessages().single { it.optString("t") == "status" }.has("rules"))
    }

    @Test fun removingANumberDoesNotBlockLaterRuleChanges() {
        val other = "+15555550177"
        core.proposeNumber(dest)
        core.proposeNumber(other)
        approve(dest, other)
        assertTrue(setRules(1, rule("a", listOf(dest), "OTP"), rule("b", listOf(other), "code")))
        core.removeNumber(dest)
        // Switching off or editing the unrelated rule still works; rule "a" keeps naming the removed number.
        assertTrue(setRules(2, rule("a", listOf(dest), "OTP"), rule("b", listOf(other), "code", enabled = false)))
        receive("EXBANK", "OTP 1")
        assertTrue(outbox.isEmpty())
        // But a removed number cannot move into another rule, or into a new one.
        assertFalse(setRules(3, rule("a", listOf(dest), "OTP"), rule("b", listOf(other, dest), "code")))
        assertFalse(setRules(3, rule("a", listOf(dest), "OTP"), rule("b", listOf(other), "code"), rule("c", listOf(dest), "e")))
        assertFalse(setRules(3, rule("a", listOf(dest), "OTP"), rule("b", listOf(other), "code"), rule("x", listOf("+15555550188"), "e")))
    }

    @Test fun anOldApprovalCannotActivateANewProposalOfTheSameNumber() {
        core.proposeNumber(dest)
        val first = core.allowlist().single().proposal
        core.removeNumber(dest)
        core.proposeNumber(dest)
        core.onDown(cmd("approve") { put("nums", JSONArray(listOf(dest))); put("p", JSONArray(listOf(first))) }.second)
        assertFalse(core.allowlist().single().active)
        core.onDown(cmd("approve") { put("nums", JSONArray(listOf(dest))) }.second) // no proposal id
        assertFalse(core.allowlist().single().active)
        approve(dest)
        assertTrue(core.allowlist().single().active)
        // An entry without a proposal id (corrupt or hand-made) is never activated, even by an approve without one.
        store.setMeta(PhoneCore.META_ALLOW, Allowlist.toJson(listOf(Allowlist.Entry("+15555550177", false, ""))))
        core.onDown(cmd("approve") { put("nums", JSONArray(listOf("+15555550177"))); put("p", JSONArray(listOf(""))) }.second)
        assertFalse(core.allowlist().single().active)
    }

    @Test fun approveActivatesOnlyTheNamedProposal() {
        core.proposeNumber(dest)
        core.proposeNumber("+15555550177")
        approve(dest)
        assertEquals(listOf(true, false), core.allowlist().map { it.active })
    }

    @Test fun readerRevokeStopsForwarding() {
        setUpForwarding()
        revoke(dest)
        assertTrue(core.allowlist().isEmpty())
        receive("EXBANK", "OTP 1")
        assertTrue(outbox.isEmpty())
    }

    @Test fun proposalsAreCapped() {
        repeat(Allowlist.MAX_ENTRIES) { assertTrue(core.proposeNumber("+1555555${1000 + it}")) }
        assertFalse(core.proposeNumber("+15555559999"))
        assertFalse(core.proposeNumber("+15555551000")) // duplicate
    }

    @Test fun proposingDoesNotPublishAnUnrequestedStatus() {
        net.sent.clear()
        core.proposeNumber(dest)
        core.removeNumber(dest)
        assertTrue(net.sent.isEmpty())
    }

    @Test fun disabledRulesDoNotForwardAndEachDestinationGetsOneCopy() {
        core.proposeNumber(dest)
        approve(dest)
        assertTrue(setRules(1, rule("a", listOf(dest), "OTP"), rule("b", listOf(dest), "OTP"), rule("c", listOf(dest), "zzz", enabled = false)))
        receive("EXBANK", "OTP zzz")
        assertEquals(1, outbox.size)
        outbox.clear()
        assertTrue(setRules(2, rule("c", listOf(dest), "zzz", enabled = false)))
        receive("EXBANK", "zzz")
        assertTrue(outbox.isEmpty())
    }

    @Test fun onlyIncomingMessagesAreForwarded() {
        setUpForwarding()
        core.onRow(sms.add("+15555550199", "my OTP", clock, ProviderSms.TYPE_SENT))
        core.onRow(sms.add("+15555550199", "OTP draft", clock, ProviderSms.TYPE_DRAFT))
        assertTrue(outbox.isEmpty())
    }

    @Test fun dailyLimitAndHourlyWindowHold() {
        setUpForwarding()
        repeat(PhoneCore.FWD_PER_HOUR) { receive("EXBANK", "OTP a$it") }
        clock += 30 * 60_000L
        receive("EXBANK", "OTP still blocked")
        assertEquals(PhoneCore.FWD_PER_HOUR, outbox.size)
        repeat(4) {
            clock += 3_600_000L
            repeat(PhoneCore.FWD_PER_HOUR) { receive("EXBANK", "OTP b$it") }
        }
        assertEquals(PhoneCore.FWD_PER_DAY, outbox.size)
        clock += 3_600_000L
        receive("EXBANK", "OTP over the day")
        assertEquals(PhoneCore.FWD_PER_DAY, outbox.size)
        net.sent.clear()
        core.onDown(cmd("hello").second)
        assertEquals(2, upMessages().single { it.optString("t") == "status" }.getInt("fwdBlocked"))
    }

    @Test fun blockedCopiesAreCountedPerDestination() {
        val other = "+15555550177"
        core.proposeNumber(dest)
        core.proposeNumber(other)
        approve(dest, other)
        assertTrue(setRules(1, rule("a", listOf(dest, other), "OTP")))
        repeat(PhoneCore.FWD_PER_HOUR / 2) { receive("EXBANK", "OTP $it") }
        receive("EXBANK", "OTP over")
        assertEquals(PhoneCore.FWD_PER_HOUR, outbox.size)
        net.sent.clear()
        core.onDown(cmd("hello").second)
        assertEquals(2, upMessages().single { it.optString("t") == "status" }.getInt("fwdBlocked"))
    }

    @Test fun messagesDatedAheadOfTheClockAreNotForwarded() {
        setUpForwarding()
        core.onRow(sms.add("EXBANK", "OTP from the future", clock + 2 * 86_400_000L))
        core.onRow(sms.add("EXBANK", "OTP slight skew", clock + 60_000L))
        assertEquals(1, outbox.size)
    }

    @Test fun cuttingNeverSplitsASurrogatePair() {
        setUpForwarding()
        receive("EXBANK", "OTP" + "x".repeat(PhoneCore.FWD_MAX_BODY - 4) + "😀😀")
        assertFalse(outbox.single().second.substringBefore("…").last().isHighSurrogate())
    }

    @Test fun rateLimitSurvivesACoreRebuild() {
        setUpForwarding()
        repeat(PhoneCore.FWD_PER_HOUR) { receive("EXBANK", "OTP $it") }
        val again = PhoneCore(
            Keys(phoneSig, phoneEnc, readerSig.generatePublicKey().encoded, readerEnc.generatePublicKey().encoded),
            sms, store, net, { JSONObject() }, { clock }, smsOut = smsOut,
        )
        again.onRow(sms.add("EXBANK", "OTP after restart", clock))
        assertEquals(PhoneCore.FWD_PER_HOUR, outbox.size)
    }

    @Test fun loopGuardMatchesTheSameNumberOnly() {
        setUpForwarding()
        receive("5555550100", "OTP national format of the destination") // same number: never forwarded
        assertTrue(outbox.isEmpty())
        receive("50100", "OTP short code that shares the last digits")
        receive("+449995550100", "OTP different number, same last 6 digits")
        assertEquals(2, outbox.size)
    }

    @Test fun loopGuardCoversAShortCodeDestination() {
        core.proposeNumber("12345")
        approve("12345")
        assertTrue(setRules(1, rule("a", listOf("12345"), "OTP")))
        receive("12345", "OTP reply from the short code itself")
        assertTrue(outbox.isEmpty())
        receive("EXBANK", "OTP")
        assertEquals(1, outbox.size)
    }

    @Test fun longMessagesAreCutBeforeForwarding() {
        setUpForwarding()
        receive("EXBANK", "OTP " + "x".repeat(1000))
        val text = outbox.single().second
        assertTrue(text.startsWith("From EXBANK: OTP x"))
        assertTrue(text.endsWith("… (cut)"))
        assertEquals("From EXBANK: ".length + PhoneCore.FWD_MAX_BODY + "… (cut)".length, text.length)
    }

    @Test fun aLiveMessageRecordedFirstByReconcileIsStillForwardedOnce() {
        setUpForwarding()
        store.setMeta(PhoneCore.META_PAIRED_AT, (clock - 86_400_000L).toString())
        val row = sms.add("EXBANK", "OTP raced", clock)
        core.reconcile() // the check task got there before the delivery callback
        core.onRow(row)
        assertEquals(1, outbox.size)
    }

    @Test fun nothingIsForwardedBeforeThePairingIsConfirmed() {
        setUpForwarding()
        unconfirm()
        receive("EXBANK", "OTP 5")
        assertTrue(outbox.isEmpty())
    }

    @Test fun helloReplayStaysRejectedAcrossCoreRebuilds() {
        val guard = CoreState()
        fun newCore() = PhoneCore(
            Keys(phoneSig, phoneEnc, readerSig.generatePublicKey().encoded, readerEnc.generatePublicKey().encoded),
            sms, store, net, { JSONObject() }, { clock }, state = guard,
        )
        val (_, hello) = cmd("hello")
        assertNotNull(newCore().onDown(hello))
        assertNull(newCore().onDown(hello)) // rebuilt core, same process: still a replay
    }

    @Test fun reconcileCursorAheadOfTheClockIsClamped() {
        store.setMeta(PhoneCore.META_PAIRED_AT, (clock - 10 * 86_400_000L).toString())
        store.setMeta(PhoneCore.META_RECONCILED, (clock + 30 * 86_400_000L).toString()) // clock ran ahead once
        sms.add("A", "recent", clock - 3_600_000L)
        core.reconcile()
        assertEquals(1, store.recs.size)
    }

    @Test fun sendTimesAreStoredSoARestartDoesNotResendInBulk() {
        repeat(3) { receive("A", "m$it") }
        assertEquals(3, store.sentAt.size)
        // A new core (rebuild or process restart) on the same store, reader still listening.
        val again = PhoneCore(
            Keys(phoneSig, phoneEnc, readerSig.generatePublicKey().encoded, readerEnc.generatePublicKey().encoded),
            sms, store, net, { JSONObject() }, { clock },
        )
        again.onDown(cmd("hello").second)
        net.sent.clear()
        again.onConnected()
        assertEquals(0, upMessages().count { it.optString("t") == "sms" })
    }

    @Test fun pairReannouncedUntilConfirmed() {
        unconfirm()
        assertTrue(core.announceIfNeeded())
        assertTrue(upMessages().any { it.optString("t") == "pair" })
        core.confirmPairing()
        core.onDown(cmd("hello").second)
        net.sent.clear()
        assertFalse(core.announceIfNeeded())
        assertTrue(net.sent.isEmpty())
    }

    @Test fun sameTextFromSameSenderAtAnotherTimeIsANewMessage() {
        val old = receive("CARRIER", "Recharge now")
        sms.rows.remove(old.providerId)
        val reused = ProviderSms(old.providerId, "CARRIER", "Recharge now", clock + 86_400_000L, clock)
        sms.rows[reused.providerId] = reused
        net.sent.clear()
        core.onRow(reused)
        assertEquals(1, upMessages().count { it.optString("t") == "sms" })
    }

    @Test fun reconcileCatchesUpFromTheLastSuccessfulPass() {
        store.setMeta(PhoneCore.META_PAIRED_AT, (clock - 30 * 86_400_000L).toString())
        store.setMeta(PhoneCore.META_RECONCILED, (clock - 20 * 86_400_000L).toString())
        sms.add("A", "missed 19 days ago", clock - 19 * 86_400_000L)
        sms.add("B", "before last pass", clock - 25 * 86_400_000L)
        core.reconcile()
        assertEquals(listOf("A"), store.recs.values.map { it.address })
        assertEquals(clock.toString(), store.getMeta(PhoneCore.META_RECONCILED))
    }

    @Test fun nothingIsForwardedOrObeyedUntilConfirmedOnThePhone() {
        unconfirm()
        receive("EXBANK", "OTP 1")
        core.onConnected()
        assertTrue(upMessages().any { it.optString("t") == "pair" })
        assertFalse(upMessages().any { it.optString("t") == "sms" || it.optString("t") == "status" })
        // A reader key from a swapped pairing code gets nothing: no backfill, no deletes, no status.
        sms.add("A", "old history", clock - 1000)
        core.onDown(cmd("backfill") { put("days", 30); put("resend", true) }.second)
        core.onDown(cmd("hello").second)
        assertEquals(1, store.recs.size)
        assertFalse(upMessages().any { it.optString("t") == "sms" || it.optString("t") == "result" || it.optString("t") == "status" })
        net.sent.clear()
        core.confirmPairing()
        core.onDown(cmd("hello").second)
        assertEquals(listOf("OTP 1"), upMessages().filter { it.optString("t") == "sms" }.map { it.getString("body") })
    }

    @Test fun keepsAnnouncingUntilTheReaderSpeaksEvenIfConfirmedFirst() {
        // Confirmed on the phone but the reader never got the announcement.
        store.setMeta(PhoneCore.META_READER_SEEN, "")
        assertTrue(core.announceIfNeeded())
        core.onConnected()
        assertTrue(upMessages().any { it.optString("t") == "pair" })
        core.onDown(cmd("hello").second)
        net.sent.clear()
        assertFalse(core.announceIfNeeded())
        core.onConnected()
        assertFalse(upMessages().any { it.optString("t") == "pair" })
    }

    @Test fun replayedOrStaleHellosCauseNoTraffic() {
        repeat(5) { receive("A", "m$it") }
        val (_, hello) = cmd("hello")
        clock += PhoneCore.RESEND_GAP_MS
        net.sent.clear()
        core.onDown(hello)
        assertEquals(5, upMessages().count { it.optString("t") == "sms" })
        net.sent.clear()
        repeat(20) { core.onDown(hello) } // replays of one captured hello
        assertTrue(net.sent.isEmpty())
        val (_, stale) = cmd("hello") { put("ts", clock - PhoneCore.HELLO_WINDOW_MS - 1) }
        core.onDown(stale)
        assertTrue(net.sent.isEmpty())
        // Many distinct fresh hellos: still at most one pass per gap, each envelope once per resend gap.
        repeat(10) { core.onDown(cmd("hello").second) }
        assertEquals(0, upMessages().count { it.optString("t") == "sms" })
    }

    @Test fun helloFromTooFarInTheFutureIsRejected() {
        val (_, future) = cmd("hello") { put("ts", clock + PhoneCore.HELLO_WINDOW_MS + 1) }
        assertNull(core.onDown(future))
    }

    @Test fun eachEnvelopeWaitsTheResendGapEvenAcrossFlushPasses() {
        receive("A", "one")
        clock += 2 * 60_000L // well short of the envelope's resend gap
        net.sent.clear()
        core.onDown(cmd("hello").second)
        assertEquals(0, upMessages().count { it.optString("t") == "sms" })
    }

    @Test fun clockJumpForwardDoesNotPruneStillReplayableCommands() {
        receive("A", "one")
        val id = upMessages().first { it.optString("t") == "sms" }.getString("id")
        val (_, del) = cmd("delete") { put("ids", JSONArray(listOf(id))) }
        core.onDown(del)
        val realNow = clock
        clock += 30L * 86_400_000L // spoofed time
        core.onConnected() // prune runs against min(now, newest command ts)
        clock = realNow + 3_600_000L // time restored
        net.sent.clear()
        core.onDown(del) // replay within its 7 days: answered from the log, not executed again
        assertEquals("deleted", upMessages().first { it.optString("t") == "result" }.getJSONObject("res").getString(id))
    }

    @Test fun watchdogNeverResendsInBulk() {
        repeat(3) { receive("A", "m$it") }
        clock += 10 * PhoneCore.RESEND_GAP_MS
        net.sent.clear()
        core.onConnected()
        core.flush(onlyNew = true)
        assertEquals(0, upMessages().count { it.optString("t") == "sms" })
    }

    @Test fun deleteRemovesOnlyMatchingRow() {
        receive("EXBANK", "OTP 222222")
        val id = upMessages().first { it.optString("t") == "sms" }.getString("id")
        net.sent.clear()
        val (c, env) = cmd("delete") { put("ids", JSONArray(listOf(id, "f".repeat(32)))) }
        core.onDown(env)
        val res = upMessages().first { it.optString("t") == "result" }
        assertEquals(c, res.getString("cmd"))
        assertEquals("deleted", res.getJSONObject("res").getString(id))
        assertEquals("absent", res.getJSONObject("res").getString("f".repeat(32)))
        assertTrue(sms.rows.isEmpty())
    }

    @Test fun deleteRefusesWhenProviderRowChanged() {
        val row = receive("EXBANK", "OTP 333333")
        val id = upMessages().first { it.optString("t") == "sms" }.getString("id")
        // Provider id reused for a different message (e.g. after a DB reset).
        sms.rows[row.providerId] = row.copy(body = "something else")
        net.sent.clear()
        core.onDown(cmd("delete") { put("ids", JSONArray(listOf(id))) }.second)
        assertEquals("mismatch", upMessages().first { it.optString("t") == "result" }.getJSONObject("res").getString(id))
        assertNotNull(sms.rows[row.providerId])
    }

    @Test fun repeatedCommandDoesNotActTwice() {
        receive("A", "one")
        val id = upMessages().first { it.optString("t") == "sms" }.getString("id")
        val (_, env) = cmd("delete") { put("ids", JSONArray(listOf(id))) }
        core.onDown(env)
        assertEquals(1, sms.deletes)
        net.sent.clear()
        core.onDown(env) // broker or attacker replay
        assertEquals(1, sms.deletes)
        assertEquals("deleted", upMessages().first { it.optString("t") == "result" }.getJSONObject("res").getString(id))
    }

    @Test fun staleOrFutureCommandsIgnored() {
        receive("A", "one")
        val id = upMessages().first { it.optString("t") == "sms" }.getString("id")
        val (_, env) = cmd("delete") { put("ids", JSONArray(listOf(id))); put("ts", clock - PhoneCore.MAX_AGE_MS - 1) }
        core.onDown(env)
        val (_, env2) = cmd("delete") { put("ids", JSONArray(listOf(id))); put("ts", clock + PhoneCore.MAX_SKEW_MS + 1) }
        core.onDown(env2)
        assertEquals(0, sms.deletes)
        assertEquals(2, core.rejected)
    }

    @Test fun commandsFromAnyoneElseIgnored() {
        receive("A", "one")
        val id = upMessages().first { it.optString("t") == "sms" }.getString("id")
        val intruder = Ed25519PrivateKeyParameters(rng)
        val p = JSONObject().put("t", "delete").put("cmd", Envelope.randomHex(16)).put("ts", clock).put("ids", JSONArray(listOf(id)))
        core.onDown(Envelope.seal(Envelope.KIND_DOWN, intruder, phoneEnc.generatePublicKey().encoded, p))
        core.onDown(ByteArray(10))
        assertEquals(0, sms.deletes)
        assertEquals(2, core.rejected)
    }

    @Test fun backfillQueuesExistingInboxOnce() {
        sms.add("A", "old one", clock - 3 * 86_400_000L)
        sms.add("B", "very old", clock - 30 * 86_400_000L)
        core.onDown(cmd("backfill") { put("days", 7) }.second)
        val bodies = upMessages().filter { it.optString("t") == "sms" }.map { it.getString("body") }
        assertEquals(listOf("old one"), bodies)
        core.onDown(cmd("backfill") { put("days", 7) }.second)
        assertEquals(1, store.recs.size)
    }

    @Test fun resendBackfillRefillsALostArchiveWithSameIds() {
        receive("A", "one")
        val first = upMessages().first { it.optString("t") == "sms" }
        val id = first.getString("id")
        val oldAck = ackFor(first)
        core.onDown(oldAck)
        net.sent.clear()
        core.onDown(cmd("backfill") { put("days", 30) }.second) // plain backfill: already forwarded
        assertEquals(0, upMessages().count { it.optString("t") == "sms" })
        core.onDown(cmd("backfill") { put("days", 30); put("resend", true) }.second)
        val again = upMessages().filter { it.optString("t") == "sms" }
        assertEquals(listOf(id), again.map { it.getString("id") })
        assertEquals("one", again[0].getString("body"))
        // A replay of the ack for the earlier copy must not cancel this re-send.
        core.onDown(oldAck)
        assertEquals(1, store.unackedCount())
        core.onDown(ackFor(again[0]))
        assertEquals(0, store.unackedCount())
    }

    @Test fun rebrokerValidatesAndReportsBeforeSwitching() {
        val (c, env) = cmd("rebroker") { put("host", "new.example.com"); put("port", 8883); put("topic", "smsrelay"); put("user", "phone2"); put("pass", "secret2") }
        core.onDown(env)
        val res = upMessages().first { it.optString("t") == "result" }
        assertEquals(c, res.getString("cmd"))
        assertTrue(res.getBoolean("ok"))
        val p = Pairing.parse(rebrokered.single())
        assertEquals("new.example.com", p.host)
        assertEquals("phone2", p.username)
        // The reader keys cannot be changed through this command.
        assertTrue(p.readerSigPub.contentEquals(readerSig.generatePublicKey().encoded))
        // A replay answers from the log; the switch is re-offered (Relay ignores a move to where it already is).
        net.sent.clear()
        core.onDown(env)
        assertTrue(upMessages().first { it.optString("t") == "result" }.getBoolean("ok"))
        assertEquals(2, rebrokered.size)
    }

    @Test fun rebrokerRejectsBadSettings() {
        core.onDown(cmd("rebroker") { put("host", "bad host!"); put("topic", "smsrelay"); put("user", "u"); put("pass", "p") }.second)
        assertFalse(upMessages().first { it.optString("t") == "result" }.getBoolean("ok"))
        assertTrue(rebrokered.isEmpty())
    }

    @Test fun oversizedSmsIsCutInsteadOfBlockingEverything() {
        store.setMeta(PhoneCore.META_PAIRED_AT, (clock - 1000).toString())
        // Control characters expand 6x in JSON; this would overflow the 64 KiB envelope.
        val poison = sms.add("+15555550123", "\u0001".repeat(12_000) + "\uD83D\uDE00".repeat(5000), clock)
        sms.add("EXBANK", "OTP 999999", clock)
        core.reconcile()
        val bodies = upMessages().filter { it.optString("t") == "sms" }
        assertEquals(0, bodies.size) // reconcile only queues; flush sends
        core.flush()
        val sent = upMessages().filter { it.optString("t") == "sms" }
        assertEquals(2, sent.size)
        val cut = sent.first { it.getString("addr") == poison.address }
        assertTrue(cut.getInt("cut") > 0)
        assertEquals(poison.body.length, cut.getString("body").length + cut.getInt("cut"))
        assertFalse(Character.isHighSurrogate(cut.getString("body").last()))
        assertEquals("OTP 999999", sent.first { it.getString("addr") == "EXBANK" }.getString("body"))
    }

    @Test fun onIncomingOversizedDoesNotThrow() {
        receive("+15555550124", "\u0001".repeat(12_000))
        assertTrue(upMessages().single { it.optString("t") == "sms" }.getInt("cut") > 0)
    }

    @Test fun providerIdReuseReplacesStaleRecord() {
        val old = receive("A", "old otp")
        sms.rows.remove(old.providerId) // vanished outside our delete path
        val reused = ProviderSms(old.providerId, "B", "NEW OTP", clock, clock)
        sms.rows[reused.providerId] = reused
        net.sent.clear()
        core.onRow(reused)
        assertEquals(listOf("NEW OTP"), upMessages().filter { it.optString("t") == "sms" }.map { it.getString("body") })
        assertEquals(1, store.recs.size)
    }

    @Test fun backfillKeepsNewestWhenCapped() {
        repeat(PhoneCore.BACKFILL_LIMIT + 1) { sms.add("A", "m$it", clock - 1000 + it) }
        core.onDown(cmd("backfill") { put("days", 1) }.second)
        assertTrue(store.recs.values.none { sms.rows[it.providerId]?.body == "m0" })
        assertTrue(store.recs.values.any { sms.rows[it.providerId]?.body == "m${PhoneCore.BACKFILL_LIMIT}" })
    }

    @Test fun commandLogOutlivesTheAcceptanceWindow() {
        assertTrue(PhoneCore.CMD_RETENTION_MS > PhoneCore.MAX_AGE_MS + PhoneCore.MAX_SKEW_MS)
        receive("A", "one")
        val id = upMessages().first { it.optString("t") == "sms" }.getString("id")
        // Reader clock ahead by almost a day: still accepted, and valid for ~8 more days.
        val (_, env) = cmd("delete") { put("ids", JSONArray(listOf(id))); put("ts", clock + PhoneCore.MAX_SKEW_MS - 1) }
        core.onDown(env)
        clock += PhoneCore.MAX_AGE_MS + PhoneCore.MAX_SKEW_MS - 3_600_000
        core.onConnected() // prunes
        net.sent.clear()
        core.onDown(env)
        // Answered from the log ("deleted"), not executed afresh ("absent").
        assertEquals("deleted", upMessages().first { it.optString("t") == "result" }.getJSONObject("res").getString(id))
    }

    @Test fun tooManyIdsDoNothing() {
        receive("A", "one")
        val id = upMessages().first { it.optString("t") == "sms" }.getString("id")
        core.onDown(cmd("delete") { put("ids", JSONArray(List(PhoneCore.MAX_IDS + 1) { id })) }.second)
        assertEquals(0, sms.deletes)
    }

    @Test fun backfillDaysOutOfRangeRejected() {
        sms.add("A", "x", clock)
        core.onDown(cmd("backfill") { put("days", 0) }.second)
        core.onDown(cmd("backfill") { put("days", 3651) }.second)
        assertTrue(store.recs.isEmpty())
    }

    @Test fun onDownReportsTheCommandTimestamp() {
        val (c, env) = cmd("hello") { put("moved", "abc") }
        val got = core.onDown(env)!!
        assertEquals(c, got.getString("cmd"))
        assertEquals("abc", got.getString("moved"))
        assertNull(core.onDown(ByteArray(3)))
    }

    @Test fun rebrokerCannotChangeReaderKeys() {
        val other = Envelope.b64e(Ed25519PrivateKeyParameters(rng).generatePublicKey().encoded)
        core.onDown(cmd("rebroker") { put("host", "n.example.com"); put("topic", "x"); put("user", "u"); put("pass", "p"); put("rs", other); put("re", other) }.second)
        val p = Pairing.parse(rebrokered.single())
        assertTrue(p.readerSigPub.contentEquals(readerSig.generatePublicKey().encoded))
        assertTrue(p.readerEncPub.contentEquals(readerEnc.generatePublicKey().encoded))
    }

    @Test fun forgottenMoveIsNotReapplied() {
        val (_, move) = cmd("rebroker") { put("host", "b.example.com"); put("topic", "x"); put("user", "u"); put("pass", "p") }
        core.onDown(move)
        store.setMeta(PhoneCore.META_LAST_REBROKER, "") // what Relay does on rollback
        net.sent.clear()
        core.onDown(move)
        assertEquals(1, rebrokered.size)
        // The reader is told it is not in effect, so it does not move alone.
        val res = upMessages().single { it.optString("t") == "result" }
        assertFalse(res.getBoolean("ok"))
        assertTrue(res.getBoolean("stale"))
    }

    @Test fun onlyAQuotingHelloConfirmsAMove() {
        val id = "a".repeat(32)
        assertTrue(PhoneCore.confirmsMove(JSONObject().put("t", "hello").put("moved", id), id))
        assertFalse(PhoneCore.confirmsMove(JSONObject().put("t", "hello"), id))
        assertFalse(PhoneCore.confirmsMove(JSONObject().put("t", "hello").put("moved", "b".repeat(32)), id))
        assertFalse(PhoneCore.confirmsMove(JSONObject().put("t", "ack").put("moved", id), id))
        assertFalse(PhoneCore.confirmsMove(JSONObject().put("t", "hello").put("moved", ""), ""))
        assertFalse(PhoneCore.confirmsMove(null, id))
    }

    @Test fun onlyTheLatestMoveIsReappliedOnReplay() {
        val (_, first) = cmd("rebroker") { put("host", "one.example.com"); put("topic", "x"); put("user", "u"); put("pass", "p") }
        core.onDown(first)
        val (_, second) = cmd("rebroker") { put("host", "two.example.com"); put("topic", "x"); put("user", "u"); put("pass", "p") }
        core.onDown(second)
        core.onDown(first) // old replay: must not move us back
        core.onDown(second) // latest replay: re-applied (the first switch may have been lost)
        assertEquals(listOf("one.example.com", "two.example.com", "two.example.com"), rebrokered.map { Pairing.parse(it).host })
    }

    @Test fun reconcileRelaysRowsMissedSincePairing() {
        store.setMeta(PhoneCore.META_PAIRED_AT, (clock - 1000).toString())
        sms.add("A", "before pairing", clock - 5000)
        sms.add("B", "missed", clock)
        core.reconcile()
        assertEquals(1, store.recs.size)
        assertEquals("B", store.recs.values.first().address)
    }

    // --- fakes ---

    open class FakeSms : SmsStore {
        val rows = linkedMapOf<Long, ProviderSms>()
        var next = 1L
        var deletes = 0
        fun add(address: String, body: String, date: Long, type: Int = ProviderSms.TYPE_INBOX) =
            ProviderSms(next++, address, body, date, date, type).also { rows[it.providerId] = it }
        override fun query(providerId: Long) = rows[providerId]
        override fun delete(providerId: Long) = (rows.remove(providerId) != null).also { if (it) deletes++ }
        // Mirrors AndroidSmsStore: newest `limit` rows, returned oldest first.
        override fun messagesSince(sinceMs: Long, limit: Int) =
            rows.values.filter { it.date >= sinceMs && it.type in ProviderSms.RELAYED }
                .sortedByDescending { it.date }.take(limit).reversed()
    }

    class FakeStore : RelayStore {
        val recs = linkedMapOf<String, MsgRecord>()
        private val cmds = mutableMapOf<String, Pair<String, Long>>()
        private val meta = mutableMapOf<String, String>()
        override fun insert(rec: MsgRecord) { recs[rec.id] = rec }
        override fun get(id: String) = recs[id]
        override fun byProviderId(providerId: Long) = recs.values.firstOrNull { it.providerId == providerId }
        override fun setEnv(id: String, env: ByteArray, gen: String) { recs[id]?.let { recs[id] = it.copy(env = env, gen = gen); sentAt.remove(id) } }
        val sentAt = mutableMapOf<String, Long>()
        override fun toSend(onlyNew: Boolean, sentBefore: Long, limit: Int) = recs.values.filter {
            it.env != null && (sentAt[it.id] == null || (!onlyNew && sentAt[it.id]!! <= sentBefore))
        }.take(limit)
        override fun markSent(id: String, gen: String, ts: Long) { if (recs[id]?.gen == gen) sentAt[id] = ts }
        override fun unackedCount() = recs.values.count { it.env != null }
        override fun markAcked(id: String, gen: String) { recs[id]?.let { if (it.gen == gen) recs[id] = it.copy(env = null) } }
        override fun remove(id: String) { recs.remove(id) }
        override fun cmdResult(cmd: String) = cmds[cmd]?.first
        override fun saveCmdResult(cmd: String, resultJson: String, ts: Long) { cmds[cmd] = resultJson to ts }
        override fun pruneCmds(beforeMs: Long) { cmds.entries.removeIf { it.value.second < beforeMs } }
        val forwards = mutableMapOf<Long, Pair<String, String>>()
        override fun addForward(providerId: Long, rule: String, srcId: String) { forwards[providerId] = rule to srcId }
        override fun forwardOf(providerId: Long) = forwards[providerId]
        val gone = linkedSetOf<String>()
        override fun addGone(id: String) { gone += id }
        override fun gonePending(limit: Int) = gone.take(limit)
        override fun clearGone(ids: List<String>) { gone -= ids.toSet() }
        override fun getMeta(key: String) = meta[key]
        override fun setMeta(key: String, value: String) { meta[key] = value }
    }

    class FakeTransport : Transport {
        var online = true
        /** false: the broker never acknowledges (a half-open connection). */
        var acks = true
        val sent = mutableListOf<ByteArray>()
        override fun publish(env: ByteArray, onDelivered: (() -> Unit)?): Boolean {
            if (!online) return false
            sent += env
            if (acks) onDelivered?.invoke()
            return true
        }
    }
}
