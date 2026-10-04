package dev.smsrelay.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.smsrelay.R
import dev.smsrelay.core.ProviderSms

/**
 * New-message notifications, as any messaging app shows. Content is private: the lock screen only
 * says "New message" (the full version needs the phone unlocked). One notification per conversation,
 * cleared when that conversation is opened.
 */
object Notifier {
    private const val CHANNEL = "messages"
    /** Own tag, so a conversation's id can never collide with the relay's foreground notification (id 1). */
    private const val TAG = "sms"
    private const val EXTRA_PID = "dev.smsrelay.pid"

    /** Thread currently on screen (no notification for it), or -1. */
    @Volatile var openThread = -1L

    fun incoming(ctx: Context, sms: ProviderSms) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.messages_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val threadId = threadOf(ctx, sms.providerId) ?: return
        if (threadId == openThread) return
        val open = PendingIntent.getActivity(
            ctx, threadId.toInt(),
            Intent(ctx, ThreadActivity::class.java)
                .putExtra(Ui.EXTRA_THREAD, threadId).putExtra(Ui.EXTRA_ADDRESS, sms.address)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(ctx.getString(R.string.new_message_public))
            .build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(sms.address)
            .setContentText(sms.body)
            .setStyle(Notification.BigTextStyle().bigText(sms.body))
            .setWhen(sms.date)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setExtras(android.os.Bundle().apply { putLong(EXTRA_PID, sms.providerId) })
            .build()
        runCatching { nm.notify(TAG, threadId.toInt(), n) } // no permission (Android 13+ denied): just no notification
    }

    fun clear(ctx: Context, threadId: Long) {
        ctx.getSystemService(NotificationManager::class.java).cancel(TAG, threadId.toInt())
    }

    /** Messages were deleted (here or from the reader): take down notifications that still show one. */
    fun dropDeleted(ctx: Context, providerIds: Collection<Long>) {
        if (providerIds.isEmpty()) return
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val ids = providerIds.toHashSet()
            for (n in nm.activeNotifications) {
                if (n.tag == TAG && n.notification.extras.getLong(EXTRA_PID, -1) in ids) nm.cancel(TAG, n.id)
            }
        }
    }

    private fun threadOf(ctx: Context, providerId: Long): Long? =
        ctx.contentResolver.query(
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId), arrayOf(Telephony.Sms.THREAD_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
}
