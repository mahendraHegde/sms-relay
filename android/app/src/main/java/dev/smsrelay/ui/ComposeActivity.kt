package dev.smsrelay.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import dev.smsrelay.R
import dev.smsrelay.SmsSender
import java.lang.ref.WeakReference

/**
 * New message. Also what other apps open with SENDTO/SEND (sms:, smsto:), e.g. a bank app's
 * "send this SMS to register" button, so recipient and text can arrive prefilled.
 */
class ComposeActivity : Activity() {
    /** A send is in flight or done: the text is no longer a draft. Identified by [sendKey]. */
    private var sent = false
    private var sendKey: String? = null
    /** Only text the person typed in the message box becomes a draft, never what another app prefilled. */
    private var edited = false
    /**
     * The draft this screen saved (address, text). Only that draft is ever removed again (text
     * cleared, recipient changed, or sent), and only while it still reads the same: drafts the person
     * left in a conversation earlier, or rewrote since, are never touched. Read and written only on
     * the single [Ui.io] thread (in order with the saves and sends), except the bundle snapshot.
     */
    @Volatile private var ownDraft: Pair<String, String>? = null
    private var resumed = false
    private lateinit var toField: EditText
    private lateinit var bodyField: EditText
    private lateinit var sendButton: Button

    private companion object {
        const val STATE_TO = "to"
        const val STATE_BODY = "body"
        const val STATE_EDITED = "edited"
        const val STATE_SEND_KEY = "sendKey"
        const val STATE_DRAFT_TO = "draftTo"
        const val STATE_DRAFT_TEXT = "draftText"

        val main = Handler(Looper.getMainLooper())
        // All main-thread only.
        /** Live compose screens: a send reports to the one waiting for it (after a rotation, the new one). */
        val screens = ArrayList<WeakReference<ComposeActivity>>()
        /** Results no screen was waiting for yet, by send key: (ok, thread id), claimed in onCreate/onResume. */
        val unclaimed = HashMap<String, Pair<Boolean, Long?>>()
        /** Sends started in this process and not yet reported: key -> (address, text, typed by the person). */
        val inFlight = HashMap<String, Triple<String, String, Boolean>>()

        fun deliver(app: android.content.Context, key: String, ok: Boolean, threadId: Long?) {
            val (address, text, typed) = inFlight.remove(key) ?: return
            screens.removeAll { it.get() == null }
            val target = screens.firstNotNullOfOrNull { ref -> ref.get()?.takeIf { !it.isFinishing && !it.isDestroyed && it.sendKey == key } }
            when {
                target != null -> target.onSent(ok, threadId)
                // The person left before a failed send reported: keep their text as the conversation's draft.
                !ok -> if (typed) Ui.io.execute { SmsSender.saveDraft(app, address, text) }
                else -> unclaimed[key] = true to threadId // a restored screen may still claim it
            }
        }
    }

    private fun address() = toField.text.toString().split(',', ';').first().trim()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TO, toField.text.toString())
        outState.putString(STATE_BODY, bodyField.text.toString())
        outState.putBoolean(STATE_EDITED, edited)
        outState.putString(STATE_SEND_KEY, sendKey)
        outState.putString(STATE_DRAFT_TO, ownDraft?.first)
        outState.putString(STATE_DRAFT_TEXT, ownDraft?.second)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        sendKey?.let { key -> unclaimed.remove(key)?.let { (ok, tid) -> onSent(ok, tid) } }
    }

    override fun onDestroy() {
        screens.removeAll { it.get() == null || it.get() === this }
        super.onDestroy()
    }

    override fun onPause() {
        resumed = false
        if (!sent) persistDraft()
        super.onPause()
    }

    /**
     * Leaving with unsent text keeps it as the conversation's draft. Only this screen's own draft is
     * ever removed (text cleared, or the recipient changed since it was saved), and a draft only
     * becomes this screen's own when this screen actually wrote it (an identical draft that was
     * already there stays the person's).
     */
    private fun persistDraft() {
        val address = address()
        val text = bodyField.text.toString()
        val next = if (edited && address.isNotEmpty() && text.isNotBlank()) address to text else null
        val app = applicationContext
        Ui.io.execute {
            val cur = ownDraft
            if (next == cur) return@execute
            if (cur != null && cur.first != next?.first) SmsSender.removeDraftIf(app, cur.first, cur.second)
            ownDraft = next?.takeIf { (a, t) -> SmsSender.saveDraft(app, a, t) }
        }
    }

    /** Result of this screen's send (possibly started by the instance before a rotation). */
    private fun onSent(ok: Boolean, threadId: Long?) {
        if (!ok || threadId == null) {
            sent = false
            sendKey = null
            sendButton.isEnabled = true
            Toast.makeText(this, R.string.not_sent, Toast.LENGTH_SHORT).show()
            if (!resumed) persistDraft() // its onPause ran while the send was in flight and saved nothing
            return
        }
        startActivity(Intent(this, ThreadActivity::class.java).putExtra(Ui.EXTRA_ADDRESS, address()).putExtra(Ui.EXTRA_THREAD, threadId))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.new_message)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val data: Uri? = intent.data
        val prefillTo = data?.schemeSpecificPart?.substringBefore('?')?.let { Uri.decode(it) }.orEmpty()
        val prefillBody = intent.getStringExtra("sms_body")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: data?.let { runCatching { Uri.parse("x://h?" + (it.schemeSpecificPart.substringAfter('?', ""))).getQueryParameter("body") }.getOrNull() }
            ?: ""

        val pad = Ui.dp(this, 16)
        toField = EditText(this).apply {
            hint = getString(R.string.to)
            inputType = InputType.TYPE_CLASS_PHONE
            setText(savedInstanceState?.getString(STATE_TO) ?: prefillTo)
        }
        bodyField = EditText(this).apply {
            hint = getString(R.string.text_message)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            setText(savedInstanceState?.getString(STATE_BODY) ?: prefillBody) // rotation keeps what was typed
        }
        sendButton = Button(this).apply {
            text = getString(R.string.send)
            isAllCaps = false
            filterTouchesWhenObscured = true // no tap-jacking of a prefilled message through overlays
            setOnClickListener {
                val address = address()
                val text = bodyField.text.toString()
                if (address.isEmpty() || text.isBlank()) return@setOnClickListener
                isEnabled = false
                sent = true
                val key = java.util.UUID.randomUUID().toString()
                sendKey = key
                inFlight[key] = Triple(address, text, edited)
                val app = applicationContext
                Ui.io.execute {
                    // SmsSender never throws: without the default-SMS role this just reports "not sent".
                    val tid = SmsSender.threadId(app, address)
                    val mine = ownDraft
                    ownDraft = null
                    mine?.let { (a, t) -> SmsSender.removeDraftIf(app, a, t) } // sent: our own draft goes, nobody else's
                    val ok = tid != null && SmsSender.send(app, address, text)
                    main.post { deliver(app, key, ok, tid) }
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(toField)
            addView(bodyField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(sendButton)
        })
        // Armed after the prefill above, so only the person's own typing in the message counts
        // (correcting only the recipient of a prefilled message does not make it a draft).
        bodyField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { edited = true }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        edited = savedInstanceState?.getBoolean(STATE_EDITED) ?: false
        ownDraft = savedInstanceState?.let { b -> b.getString(STATE_DRAFT_TO)?.let { it to (b.getString(STATE_DRAFT_TEXT) ?: "") } }
        screens += WeakReference(this)
        // Rotated while a send was in flight: wait for its result (delivered to this screen).
        // (A key this process never started means the app was killed meanwhile; whether the radio took
        // the message first is unknown, so the text is shown again and the person decides.)
        savedInstanceState?.getString(STATE_SEND_KEY)?.takeIf { it in inFlight || it in unclaimed }?.let {
            sendKey = it
            sent = true
            sendButton.isEnabled = false
        }
        if (prefillTo.isEmpty()) toField.requestFocus() else bodyField.requestFocus()
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }
}
