package dev.smsrelay.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.smsrelay.R
import dev.smsrelay.Relay
import dev.smsrelay.core.Allowlist
import dev.smsrelay.core.Rules

/**
 * Forwarding (behind the screen lock, via Settings). Rules are written in the reader and shown
 * here read-only. Destination numbers are proposed here and only take effect once approved in the
 * reader: neither side can add a destination alone.
 */
class ForwardingActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!HiddenSettingsActivity.allowed(this)) return
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(R.string.forwarding)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(context, 16)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        Relay.forwarding(this) { rules, allow -> main.post { render(rules, allow) } }
    }

    private fun render(rules: Rules.RuleSet?, allow: List<Allowlist.Entry>) {
        content.removeAllViews()
        content.addView(heading(getString(R.string.allowlist)))
        content.addView(text(getString(R.string.allowlist_hint), small = true))
        if (allow.isEmpty()) content.addView(text(getString(R.string.allowlist_empty)))
        for (e in allow) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text("${e.number}\n${getString(if (e.active) R.string.approved else R.string.awaiting_approval)}"),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply {
                    text = getString(R.string.remove)
                    isAllCaps = false
                    setOnClickListener {
                        AlertDialog.Builder(this@ForwardingActivity)
                            .setMessage(getString(R.string.remove_number, e.number))
                            .setPositiveButton(R.string.remove) { _, _ -> Relay.removeNumber(this@ForwardingActivity, e.number) { main.post { refresh() } } }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                })
            })
        }
        val input = EditText(this).apply {
            hint = getString(R.string.add_number_hint)
            inputType = InputType.TYPE_CLASS_PHONE
        }
        content.addView(input)
        content.addView(Button(this).apply {
            text = getString(R.string.propose_number)
            isAllCaps = false
            filterTouchesWhenObscured = true
            setOnClickListener {
                Relay.proposeNumber(this@ForwardingActivity, input.text.toString()) { ok ->
                    main.post {
                        Toast.makeText(this@ForwardingActivity, if (ok) R.string.proposed else R.string.bad_number, Toast.LENGTH_LONG).show()
                        if (ok) refresh()
                    }
                }
            }
        })

        content.addView(heading(getString(R.string.rules)))
        content.addView(text(getString(R.string.rules_hint), small = true))
        if (rules == null || rules.rules.isEmpty()) {
            content.addView(text(getString(R.string.no_rules)))
            return
        }
        content.addView(text(getString(R.string.rules_version, rules.version), small = true))
        val active = Allowlist.active(allow)
        for (r in rules.rules) {
            val dests = r.forwardTo.joinToString { if (it in active) it else getString(R.string.not_approved, it) }
            content.addView(text(
                "${r.name}${if (r.enabled) "" else " " + getString(R.string.disabled)}\n" +
                    getString(R.string.rule_when, Rules.describe(r.`when`)) + "\n" +
                    getString(R.string.rule_to, dests),
            ))
        }
    }

    private fun heading(s: String) = TextView(this).apply {
        text = s
        textSize = 18f
        setPadding(0, Ui.dp(context, 16), 0, Ui.dp(context, 4))
    }

    private fun text(s: String, small: Boolean = false) = TextView(this).apply {
        text = s
        textSize = if (small) 13f else 15f
        alpha = if (small) 0.7f else 1f
        setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6))
    }
}
