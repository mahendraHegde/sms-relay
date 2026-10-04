package dev.smsrelay.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.smsrelay.R
import dev.smsrelay.RelaySetupActivity

/**
 * Relay configuration (forwarding, pairing, battery). Not listed anywhere: reached by tapping the
 * version in Settings five times, then the device credential (SettingsActivity). Not exported, so
 * nothing outside the app can open it. It cannot be screenshotted and closes as soon as it leaves the
 * screen, so every visit asks again.
 */
class HiddenSettingsActivity : Activity() {
    companion object {
        /**
         * Set when the device credential was confirmed in this process. Android can recreate a screen
         * after killing the process (e.g. on return from the background); the hidden screens then
         * close instead of showing without a fresh unlock.
         */
        @Volatile var unlocked = false

        /** For the hidden screens' onCreate: true if the screen may show; otherwise it finishes itself. */
        fun allowed(a: Activity): Boolean = unlocked.also { if (!it) a.finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!allowed(this)) return
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(R.string.advanced)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.settingsRow(this@HiddenSettingsActivity, getString(R.string.forwarding), getString(R.string.forwarding_hint)) {
                startActivity(Intent(this@HiddenSettingsActivity, ForwardingActivity::class.java))
            })
            addView(Ui.settingsRow(this@HiddenSettingsActivity, getString(R.string.relay_setup), getString(R.string.advanced_hint)) {
                startActivity(Intent(this@HiddenSettingsActivity, RelaySetupActivity::class.java))
            })
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }
}
