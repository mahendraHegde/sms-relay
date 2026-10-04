package dev.smsrelay.ui

import android.app.Activity
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.smsrelay.BuildConfig
import dev.smsrelay.R

/**
 * Settings as anyone picking up the phone sees them: the default-SMS-app choice and the version.
 * The relay's configuration is not shown here at all: tapping the version five times asks for the
 * device credential and then opens HiddenSettingsActivity. No counter or hint gives the gesture away.
 */
class SettingsActivity : Activity() {
    private companion object {
        const val REQ_ROLE = 2
        const val REQ_UNLOCK = 3
        const val TAPS = 5
        const val TAP_WINDOW_MS = 3_000L
    }

    private lateinit var defaultRow: TextView
    private val taps = LongArray(TAPS)
    private var tapCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        defaultRow = Ui.settingsRow(this, getString(R.string.default_sms_app), "") { requestDefault() }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(defaultRow)
            addView(Ui.settingsRow(this@SettingsActivity, getString(R.string.about), getString(R.string.version, BuildConfig.VERSION_NAME)) { onVersionTap() })
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val held = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
        defaultRow.text = getString(R.string.default_sms_app) + "\n" + getString(if (held) R.string.yes else R.string.tap_to_set)
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Five taps within a few seconds: ask for the device credential, then open the hidden settings. */
    private fun onVersionTap() {
        val now = SystemClock.elapsedRealtime()
        taps[tapCount++ % TAPS] = now
        if (tapCount < TAPS || now - taps[tapCount % TAPS] > TAP_WINDOW_MS) return
        tapCount = 0
        val km = getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val confirm = km.createConfirmDeviceCredentialIntent(getString(R.string.advanced), null)
        @Suppress("DEPRECATION")
        if (confirm == null) openHidden() else startActivityForResult(confirm, REQ_UNLOCK) // no screen lock set: nothing to ask
    }

    private fun openHidden() {
        HiddenSettingsActivity.unlocked = true
        startActivity(Intent(this, HiddenSettingsActivity::class.java))
    }

    @Deprecated("Activity result API kept for minSdk without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNLOCK && resultCode == RESULT_OK) openHidden()
    }

    private fun requestDefault() {
        val rm = getSystemService(RoleManager::class.java)
        if (!rm.isRoleHeld(RoleManager.ROLE_SMS)) {
            @Suppress("DEPRECATION")
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), REQ_ROLE)
        }
    }
}
