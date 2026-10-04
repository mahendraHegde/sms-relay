package dev.smsrelay

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log

/** SMS_DELIVER: only the default SMS app gets this, and it must save the message itself. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val pending = goAsync()
        val app = ctx.applicationContext
        Relay.smsExec.execute {
            try {
                // Parts of a concatenated SMS arrive together in one SMS_DELIVER intent.
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: emptyArray()
                if (parts.isNotEmpty()) {
                    // Android 10 uses the legacy "subscription" extra; the public constant arrived in API 30.
                    val subId = intent.getIntExtra("subscription", intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1))
                    Relay.saveIncoming(
                        app,
                        address = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: "",
                        body = parts.joinToString("") { it.displayMessageBody ?: "" },
                        dateSent = parts[0].timestampMillis,
                        subId = subId,
                    )
                }
                RelayService.keepRunning(app) // saveIncoming already connects if needed
            } catch (e: Exception) {
                Log.w("SmsDeliver", "failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * WAP_PUSH_DELIVER (MMS). Required for the default SMS role. MMS are not supported: they are
 * dropped. Banks and OTP senders typically use plain SMS.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Unit
}

/**
 * The default SMS app changed. Becoming it again: save anything journaled while the role was gone
 * now, not at the next retry. Exported (the sender varies by Android version), so it trusts nothing in
 * the intent: it only acts if this app really is the default, at most once a minute.
 */
class DefaultSmsChangedReceiver : BroadcastReceiver() {
    companion object {
        @Volatile private var last = 0L
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED) return
        val now = android.os.SystemClock.elapsedRealtime()
        // (Telephony.Sms.getDefaultSmsPackage returns null on recent versions even for the holder.)
        val held = ctx.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_SMS)
        if (now - last < 60_000 || !held) return
        last = now
        Relay.check(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Secrets.get(ctx).pairing != null) RelayService.start(ctx)
            }
        }
    }
}

/** RESPOND_VIA_MESSAGE: the quick "reply with a message" when declining a call. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!to.isNullOrBlank() && !text.isNullOrBlank()) {
            val app = applicationContext
            Thread { SmsSender.send(app, Uri.decode(to), text) }.start() // never throws
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
