package dev.smsrelay.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import dev.smsrelay.R
import dev.smsrelay.SmsSender

/** The launcher screen: an ordinary conversation list. */
class ConversationsActivity : Activity() {
    private data class Conversation(
        val threadId: Long, val address: String, val snippet: String, val date: Long, val unread: Int, val draft: Boolean,
    )

    private val main = Handler(Looper.getMainLooper())
    private val adapter = Adapter()
    private lateinit var empty: TextView
    private var observing = false
    private lateinit var fab: Button
    private val loadNow = Runnable { load() }
    private val observer = object : ContentObserver(main) {
        // Coalesce bursts of changes (a bulk delete, a multipart send) into one reload.
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(loadNow)
            main.postDelayed(loadNow, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        val list = ListView(this).apply {
            adapter = this@ConversationsActivity.adapter
            divider = null
            setOnItemClickListener { _, _, pos, _ ->
                val c = this@ConversationsActivity.adapter.items[pos]
                startActivity(Intent(this@ConversationsActivity, ThreadActivity::class.java)
                    .putExtra(Ui.EXTRA_THREAD, c.threadId).putExtra(Ui.EXTRA_ADDRESS, c.address))
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                val c = this@ConversationsActivity.adapter.items[pos]
                AlertDialog.Builder(this@ConversationsActivity)
                    .setMessage(getString(R.string.delete_conversation, c.address))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        Ui.io.execute { runCatching { SmsSender.deleteRows(this@ConversationsActivity, SmsSender.threadRows(this@ConversationsActivity, c.threadId)) } }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
        empty = TextView(this).apply {
            text = getString(R.string.no_conversations)
            gravity = Gravity.CENTER
            textSize = 16f
        }
        list.emptyView = empty
        fab = Button(this).apply {
            text = getString(R.string.start_chat)
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = Ui.rounded(0xFF1A73E8.toInt(), Ui.dp(context, 28).toFloat())
            setPadding(Ui.dp(context, 24), 0, Ui.dp(context, 24), 0)
            elevation = Ui.dp(context, 6).toFloat()
            setOnClickListener { startActivity(Intent(this@ConversationsActivity, ComposeActivity::class.java)) }
        }
        setContentView(FrameLayout(this).apply {
            addView(list)
            addView(empty)
            addView(fab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(context, 56), Gravity.END or Gravity.BOTTOM).apply {
                setMargins(0, 0, Ui.dp(context, 16), Ui.dp(context, 24))
            })
        })
    }

    override fun onResume() {
        super.onResume()
        // Android 13+ asks before showing notifications (Android 10 does not need this).
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notifications", false) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("asked_notifications", true).apply() // ask once, not on every resume
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Until the app is the default SMS app it cannot read messages (the role grants that), and
        // touching the provider would throw: offer to set it instead.
        if (checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            empty.text = getString(R.string.not_default_hint)
            empty.setOnClickListener {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS), REQ_ROLE)
            }
            adapter.update(emptyList())
            fab.visibility = View.GONE // composing needs the role too
            return
        }
        fab.visibility = View.VISIBLE
        empty.text = getString(R.string.no_conversations)
        empty.setOnClickListener(null)
        empty.isClickable = false
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        observing = true
        load()
    }

    override fun onPause() {
        if (observing) contentResolver.unregisterContentObserver(observer)
        observing = false
        main.removeCallbacks(loadNow)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.settings)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != 1) return super.onOptionsItemSelected(item)
        // Ordinary settings; the relay's configuration is hidden behind a gesture and the screen lock there.
        startActivity(Intent(this, SettingsActivity::class.java))
        return true
    }

    private companion object {
        const val REQ_ROLE = 8
    }

    private fun load() {
        Ui.io.execute {
            val items = runCatching { query() }.getOrDefault(emptyList())
            main.post { adapter.update(items) }
        }
    }

    /** Newest message per thread, plus unread counts, from the newest few thousand messages. */
    private fun query(): List<Conversation> {
        val latest = LinkedHashMap<Long, Conversation>()
        val unread = HashMap<Long, Int>()
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE),
            null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            var n = 0
            while (n++ < 5000 && c.moveToNext()) {
                val t = c.getLong(0)
                if (Ui.isIncoming(c.getInt(5)) && c.getInt(4) == 0) unread[t] = (unread[t] ?: 0) + 1
                if (t !in latest) {
                    latest[t] = Conversation(t, c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), 0,
                        draft = c.getInt(5) == Telephony.Sms.MESSAGE_TYPE_DRAFT)
                }
            }
        }
        return latest.values.map { it.copy(unread = unread[it.threadId] ?: 0) }
    }

    private inner class Adapter : BaseAdapter() {
        var items: List<Conversation> = emptyList()
            private set

        fun update(list: List<Conversation>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].threadId

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val c = items[position]
            val ctx = parent.context
            val pad = Ui.dp(ctx, 16)
            val bold = c.unread > 0
            val name = TextView(ctx).apply {
                text = c.address
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            val time = TextView(ctx).apply {
                text = Ui.shortTime(ctx, c.date)
                textSize = 12f
                setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            val snippet = TextView(ctx).apply {
                text = if (c.draft) getString(R.string.draft_prefix, c.snippet) else c.snippet
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                alpha = if (bold) 1f else 0.7f
                setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(time)
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, 0, 0, 0)
                addView(top)
                addView(snippet)
            }
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, Ui.dp(ctx, 12), pad, Ui.dp(ctx, 12))
                addView(Ui.avatar(ctx, c.address))
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }
}
