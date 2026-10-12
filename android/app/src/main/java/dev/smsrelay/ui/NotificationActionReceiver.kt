package dev.smsrelay.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.smsrelay.SmsSender

/** The "Mark as read" button on a new-message notification. Not exported: only our own PendingIntents reach it. */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_READ = "dev.smsrelay.action.MARK_READ"
        const val EXTRA_THREAD = "thread"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_READ) return
        val thread = intent.getLongExtra(EXTRA_THREAD, -1)
        if (thread < 0) return
        val app = ctx.applicationContext
        val pending = goAsync() // provider work off the main thread
        Ui.io.execute {
            try {
                SmsSender.markRead(app, thread)
                Notifier.clear(app, thread)
            } finally {
                pending.finish()
            }
        }
    }
}
