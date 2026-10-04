package dev.smsrelay.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import dev.smsrelay.R
import dev.smsrelay.SmsSender

/** One conversation: message bubbles and a reply box. */
class ThreadActivity : Activity() {
    private data class Msg(val id: Long, val body: String, val date: Long, val type: Int)

    private val main = Handler(Looper.getMainLooper())
    private val adapter = Adapter()
    private var threadId = -1L
    private lateinit var address: String
    private lateinit var input: EditText
    private val loadNow = Runnable { load() }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(loadNow)
            main.postDelayed(loadNow, 300)
        }
    }

    private var observing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threadId = intent.getLongExtra(Ui.EXTRA_THREAD, -1)
        address = intent.getStringExtra(Ui.EXTRA_ADDRESS) ?: ""
        if (address.isEmpty()) return finish()
        if (threadId < 0) threadId = SmsSender.threadId(this, address) ?: return finish()
        title = address
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val list = ListView(this).apply {
            adapter = this@ThreadActivity.adapter
            divider = null
            transcriptMode = AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            isStackFromBottom = true
            setOnItemLongClickListener { _, _, pos, _ ->
                val m = this@ThreadActivity.adapter.getItem(pos)
                AlertDialog.Builder(this@ThreadActivity)
                    .setMessage(R.string.delete_message)
                    .setPositiveButton(R.string.delete) { _, _ -> Ui.io.execute { SmsSender.deleteRows(this@ThreadActivity, listOf(m.id)) } }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
        input = EditText(this).apply {
            hint = getString(R.string.text_message)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
        }
        val send = Button(this).apply {
            text = getString(R.string.send)
            isAllCaps = false
            filterTouchesWhenObscured = true // no tap-jacking through overlays
            setOnClickListener {
                val body = input.text.toString()
                if (body.isBlank()) return@setOnClickListener
                input.setText("")
                val t = threadId
                Ui.io.execute {
                    SmsSender.saveDraft(this@ThreadActivity, address, t, "") // sent: the draft goes
                    val ok = SmsSender.send(this@ThreadActivity, address, body)
                    if (!ok) main.post {
                        // Give the text back, in the box and as the draft, so leaving (or having left)
                        // right after tapping Send cannot lose it; never over text typed since.
                        if (isDestroyed || input.text.isEmpty()) {
                            if (!isDestroyed) input.setText(body)
                            Ui.io.execute { SmsSender.saveDraft(applicationContext, address, t, body) }
                        }
                        if (!isDestroyed) Toast.makeText(this@ThreadActivity, R.string.not_sent, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4))
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar)
        })
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        Notifier.clear(this, threadId)
        Notifier.openThread = threadId // no notification for the conversation on screen
        // Throws on some versions once the app is no longer the default SMS app.
        observing = runCatching { contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer) }.isSuccess
        load()
        if (input.text.isEmpty()) {
            val t = threadId
            Ui.io.execute {
                val d = runCatching { SmsSender.draft(this, t) }.getOrNull()
                if (d != null) main.post { if (input.text.isEmpty()) input.setText(d.second) }
            }
        }
    }

    override fun onPause() {
        if (observing) contentResolver.unregisterContentObserver(observer)
        observing = false
        main.removeCallbacks(loadNow)
        if (Notifier.openThread == threadId) Notifier.openThread = -1
        // Unsent text is kept as the conversation's draft, as in any messaging app.
        val text = input.text.toString()
        val t = threadId
        Ui.io.execute { runCatching { SmsSender.saveDraft(this, address, t, text) } }
        super.onPause()
    }

    private fun load() {
        val t = threadId
        Ui.io.execute {
            val items = runCatching {
                // Opening a conversation reads it, as in any messaging app.
                contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) },
                    "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0", arrayOf(t.toString()),
                )
                // The draft lives in the reply box, not in the list.
                contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                    "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ?",
                    arrayOf(t.toString(), Telephony.Sms.MESSAGE_TYPE_DRAFT.toString()), "${Telephony.Sms.DATE} DESC",
                )?.use { c ->
                    buildList {
                        var n = 0
                        while (n++ < 500 && c.moveToNext()) add(Msg(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getInt(3)))
                    }.asReversed()
                } ?: emptyList()
            }.getOrDefault(emptyList())
            main.post { adapter.update(items) }
        }
    }

    private inner class Adapter : BaseAdapter() {
        private var items: List<Msg> = emptyList()

        fun update(list: List<Msg>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val m = items[position]
            val ctx = parent.context
            val incoming = Ui.isIncoming(m.type)
            val dark = Ui.dark(ctx)
            val bubbleColor = when {
                incoming && dark -> 0xFF303134.toInt()
                incoming -> 0xFFE8EAED.toInt()
                else -> 0xFF1A73E8.toInt()
            }
            val textColor = if (incoming && !dark) 0xFF202124.toInt() else 0xFFFFFFFF.toInt()
            val status = when (m.type) {
                Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> getString(R.string.sending)
                Telephony.Sms.MESSAGE_TYPE_FAILED -> getString(R.string.not_sent)
                else -> Ui.shortTime(ctx, m.date)
            }
            val bubble = TextView(ctx).apply {
                text = m.body
                textSize = 15f
                setTextColor(textColor)
                setTextIsSelectable(true)
                background = Ui.rounded(bubbleColor, Ui.dp(ctx, 18).toFloat())
                setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 9), Ui.dp(ctx, 14), Ui.dp(ctx, 9))
                maxWidth = (parent.width * 0.78).toInt().coerceAtLeast(Ui.dp(ctx, 200))
            }
            val meta = TextView(ctx).apply {
                text = status
                textSize = 11f
                alpha = 0.6f
                setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 2), Ui.dp(ctx, 6), 0)
            }
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (incoming) Gravity.START else Gravity.END
                setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 4))
                // Wrap-content children, so the container's gravity puts sent messages on the right.
                addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }
}
