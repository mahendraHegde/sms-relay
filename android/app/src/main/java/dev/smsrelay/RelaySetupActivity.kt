package dev.smsrelay

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Relay setup (pairing, default-app and battery settings). Reached only from Settings → Advanced
 * after confirming the device credential; closes as soon as it leaves the screen.
 */
class RelaySetupActivity : Activity() {

    private lateinit var statusView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps the pairing code and broker password out of screenshots and the recents list.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        statusView = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        col.addView(statusView)

        col.addView(button("1. Make this the default SMS app") { requestSmsRole() })
        col.addView(button("2. Battery: allow unrestricted") { requestBatteryExemption() })
        col.addView(button("3. Autostart / background settings") { openAutostart() })

        val codeField = EditText(this).apply {
            hint = "Paste pairing code (SR1....)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            minLines = 2
        }
        col.addView(codeField)
        col.addView(button("4. Pair with reader") {
            val code = codeField.text.toString()
            Relay.repair(this, code) { err ->
                ui.post {
                    if (err != null) {
                        toast("Invalid pairing code")
                    } else {
                        codeField.setText("")
                        clearClipboard()
                        RelayService.start(this)
                        toast("Paired. Compare the fingerprint with the reader, then tap 5.")
                    }
                }
            }
        })
        col.addView(button("5. Fingerprint matches the reader") {
            if (Secrets.get(this).pairing == null) return@button toast("Pair first")
            AlertDialog.Builder(this)
                .setMessage("Only confirm if the fingerprint below is exactly the one the reader shows. Until you do, nothing is relayed.")
                .setPositiveButton("They match") { _, _ ->
                    Relay.confirmPairing(this) { err -> ui.post { toast(if (err == null) "Confirmed. Forwarding starts." else "Not paired") } }
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        col.addView(button("Restart relay") { RelayService.start(this) })
        col.addView(button("Unpair (forget reader and outbox)") {
            Relay.repair(this, null) {
                ui.post {
                    stopService(Intent(this, RelayService::class.java))
                    toast("Unpaired")
                }
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Not kept in the back stack or recents: reopening it asks for the device credential again.
        if (!isChangingConfigurations) finish()
    }

    private fun render() {
        val secrets = Secrets.get(this)
        val role = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val paired = secrets.pairing
        statusView.text = buildString {
            appendLine("Default SMS app: ${yes(role)}")
            appendLine("Battery unrestricted: ${yes(battery)}")
            appendLine("Paired: ${yes(paired != null)}")
            if (paired != null) {
                appendLine("Broker: ${paired.host}")
                secrets.candidate?.let { appendLine("Trying new broker: ${it.host}") }
                appendLine("Connected: ${yes(Relay.connected())}")
                appendLine("Fingerprint confirmed: ${yes(Relay.confirmed())}")
                appendLine("Waiting to send: ${Relay.queueSize()}")
                appendLine()
                appendLine("Fingerprint (must match the reader):")
                appendLine(secrets.fingerprint())
            }
            Relay.lastError?.let { appendLine(); appendLine("Last error: $it") }
        }
    }

    private fun yes(b: Boolean) = if (b) "yes" else "NO"

    /** The pairing code holds the phone's broker password; don't leave it in the clipboard. */
    private fun clearClipboard() {
        runCatching { getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
    }

    private fun requestSmsRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleHeld(RoleManager.ROLE_SMS)) return toast("Already default")
        @Suppress("DEPRECATION")
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), 1)
    }

    private fun requestBatteryExemption() {
        @Suppress("BatteryLife")
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }.onFailure { openAppSettings() }
    }

    private fun openAutostart() {
        // Xiaomi has a dedicated autostart screen; on other phones fall back to the app's settings.
        val vendorAutostart = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        runCatching { startActivity(vendorAutostart) }.onFailure { openAppSettings() }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
