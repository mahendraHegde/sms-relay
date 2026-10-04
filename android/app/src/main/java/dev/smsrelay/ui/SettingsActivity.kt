package dev.smsrelay.ui

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.smsrelay.BuildConfig
import dev.smsrelay.R
import dev.smsrelay.RelaySetupActivity

/**
 * Settings. The whole screen is behind the device credential (ConversationsActivity asks before
 * opening it) and it closes as soon as it leaves the screen, so every visit asks again.
 */
class SettingsActivity : Activity() {
    private companion object {
        const val REQ_ROLE = 2
    }

    private lateinit var defaultRow: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(R.string.settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        defaultRow = row(getString(R.string.default_sms_app), "") { requestDefault() }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(defaultRow)
            addView(row(getString(R.string.forwarding), getString(R.string.forwarding_hint)) {
                startActivity(Intent(this@SettingsActivity, ForwardingActivity::class.java))
            })
            addView(row(getString(R.string.advanced), getString(R.string.advanced_hint)) {
                startActivity(Intent(this@SettingsActivity, RelaySetupActivity::class.java))
            })
            addView(row(getString(R.string.about), getString(R.string.version, BuildConfig.VERSION_NAME)) {})
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val held = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
        defaultRow.text = getString(R.string.default_sms_app) + "\n" + getString(if (held) R.string.yes else R.string.tap_to_set)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish() // next visit asks for the screen lock again
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun requestDefault() {
        val rm = getSystemService(RoleManager::class.java)
        if (!rm.isRoleHeld(RoleManager.ROLE_SMS)) {
            @Suppress("DEPRECATION")
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), REQ_ROLE)
        }
    }

    private fun row(title: String, subtitle: String, onClick: () -> Unit) = TextView(this).apply {
        text = if (subtitle.isEmpty()) title else "$title\n$subtitle"
        textSize = 16f
        val pad = Ui.dp(context, 20)
        setPadding(pad, pad, pad, pad)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        foreground = context.getDrawable(android.R.drawable.list_selector_background)
    }
}
