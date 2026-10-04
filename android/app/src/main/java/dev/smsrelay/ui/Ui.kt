package dev.smsrelay.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Telephony
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView
import java.util.concurrent.Executors

/** Small shared pieces for the plain-View messaging UI (no AndroidX on purpose: fewer dependencies). */
internal object Ui {
    /** Provider queries never run on the main thread. */
    val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-io") }

    const val EXTRA_THREAD = "thread_id"
    const val EXTRA_ADDRESS = "address"

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun dark(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

    private val avatarColors = intArrayOf(
        0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt(), 0xFF8E24AA.toInt(),
        0xFFFB8C00.toInt(), 0xFF00897B.toInt(), 0xFF3949AB.toInt(), 0xFF6D4C41.toInt(),
    )

    /** Round letter avatar, colour stable per sender. */
    fun avatar(ctx: Context, address: String): View = TextView(ctx).apply {
        val size = dp(ctx, 44)
        layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 18f
        text = address.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(avatarColors[(address.hashCode() and Int.MAX_VALUE) % avatarColors.size])
        }
    }

    fun shortTime(ctx: Context, ms: Long): String =
        if (DateUtils.isToday(ms)) DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_TIME)
        else DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    fun isIncoming(type: Int) = type == Telephony.Sms.MESSAGE_TYPE_INBOX

    /** A tappable two-line row, as on a settings screen. */
    fun settingsRow(ctx: Context, title: String, subtitle: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = if (subtitle.isEmpty()) title else "$title\n$subtitle"
        textSize = 16f
        val pad = dp(ctx, 20)
        setPadding(pad, pad, pad, pad)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        foreground = ctx.getDrawable(android.R.drawable.list_selector_background)
    }
}
