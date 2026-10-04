package dev.smsrelay

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager

/**
 * Sending SMS typed on the phone itself (the local messaging UI, apps opening the composer, call
 * quick replies). As the default SMS app we record outgoing messages in the provider ourselves:
 * queued in the outbox, then marked sent or failed when the radio reports back. The relay never
 * sends SMS on a remote command; forwarding rules only copy incoming SMS to approved numbers.
 */
object SmsSender {
    // The public calls never throw: without the default-SMS role (e.g. just switched to another app)
    // the provider refuses, and an exception on a worker thread would kill the process, relay included.

    /** Send; true if it was handed to the radio. */
    fun send(ctx: Context, address: String, text: String): Boolean =
        runCatching { sendRow(ctx, address, text)?.second == true }.getOrDefault(false)

    /** Send, returning the provider id of the recorded row (queued, or already marked failed); null if nothing was recorded. */
    fun sendForId(ctx: Context, address: String, text: String): Long? = runCatching { sendRow(ctx, address, text)?.first }.getOrNull()

    /** The conversation id for [address], or null if the provider refuses. */
    fun threadId(ctx: Context, address: String): Long? = runCatching { Telephony.Threads.getOrCreateThreadId(ctx, address) }.getOrNull()

    /** (provider id, handed to the radio) */
    private fun sendRow(ctx: Context, address: String, text: String): Pair<Long, Boolean>? {
        val to = address.trim()
        if (to.isEmpty() || text.isEmpty()) return null
        val app = ctx.applicationContext
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, to)
            put(Telephony.Sms.BODY, text)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.THREAD_ID, Telephony.Threads.getOrCreateThreadId(app, to))
        }
        val uri = app.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values) ?: return null
        return try {
            @Suppress("DEPRECATION")
            val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(text)
            // Every part reports: any failure marks the message failed; the last part's success marks it sent.
            val id = ContentUris.parseId(uri).toInt()
            val sent = ArrayList<PendingIntent?>(parts.indices.map { i ->
                PendingIntent.getBroadcast(
                    app, id * 64 + (i and 63),
                    Intent(app, SmsSentReceiver::class.java).setData(uri).putExtra(SmsSentReceiver.EXTRA_LAST, i == parts.size - 1),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            })
            if (parts.size == 1) sms.sendTextMessage(to, null, text, sent[0], null)
            else sms.sendMultipartTextMessage(to, null, parts, sent, null)
            ContentUris.parseId(uri) to true
        } catch (e: Exception) {
            markType(app, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
            ContentUris.parseId(uri) to false
        }
    }

    /**
     * Set the final type of a message we sent, and let the relay pick it up. With [unlessType], the
     * check and the write are one provider update, so a concurrent update cannot slip in between.
     */
    fun markType(ctx: Context, uri: Uri, type: Int, unlessType: Int? = null) {
        runCatching {
            val values = ContentValues().apply { put(Telephony.Sms.TYPE, type) }
            val n = if (unlessType == null) ctx.contentResolver.update(uri, values, null, null)
            else ctx.contentResolver.update(uri, values, "${Telephony.Sms.TYPE} != ?", arrayOf(unlessType.toString()))
            if (n > 0) Relay.rowChanged(ctx, ContentUris.parseId(uri))
        }
    }

    /** The draft of a conversation (one per thread), or null. */
    fun draft(ctx: Context, threadId: Long): Pair<Long, String>? =
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ?",
            arrayOf(threadId.toString(), Telephony.Sms.MESSAGE_TYPE_DRAFT.toString()),
            "${Telephony.Sms.DATE} DESC",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) to (c.getString(1) ?: "") else null }

    /**
     * Replace the conversation's draft with [text] (or just remove it when blank). A changed draft
     * is a new row, so the reader sees the old one go and the new one arrive.
     */
    /** True only if a new draft row was written (false: unchanged, removed, or refused). */
    fun saveDraft(ctx: Context, address: String, threadId: Long, text: String): Boolean =
        runCatching { replaceDraft(ctx, address, threadId, text) }.getOrDefault(false)

    /** Remove [address]'s draft only if it still reads [text] (a draft someone rewrote since is kept). */
    fun removeDraftIf(ctx: Context, address: String, text: String) {
        runCatching {
            val tid = threadId(ctx, address) ?: return
            draft(ctx, tid)?.takeIf { it.second == text }?.let { deleteRows(ctx, listOf(it.first)) }
        }
    }

    /** [saveDraft] for an address (looks up its conversation). */
    fun saveDraft(ctx: Context, address: String, text: String): Boolean =
        threadId(ctx, address)?.let { saveDraft(ctx, address, it, text) } ?: false

    private fun replaceDraft(ctx: Context, address: String, threadId: Long, text: String): Boolean {
        val old = draft(ctx, threadId)
        if (old != null && old.second == text) return false
        old?.let { deleteRows(ctx, listOf(it.first)) }
        if (text.isBlank() || address.isBlank()) return false
        val uri = ctx.contentResolver.insert(Telephony.Sms.Draft.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, text)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_DRAFT)
            put(Telephony.Sms.THREAD_ID, threadId)
        }) ?: return false
        Relay.rowChanged(ctx, ContentUris.parseId(uri))
        return true
    }

    /**
     * Delete messages on the phone (the local UI) in as few provider calls as possible (each call
     * notifies observers once), then tell the relay.
     */
    fun deleteRows(ctx: Context, providerIds: List<Long>) {
        if (providerIds.isEmpty()) return
        for (chunk in providerIds.chunked(500)) {
            runCatching {
                ctx.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} IN (${chunk.joinToString(",") { "?" }})", chunk.map { it.toString() }.toTypedArray(),
                )
            }
        }
        dev.smsrelay.ui.Notifier.dropDeleted(ctx, providerIds)
        Relay.rowsDeleted(ctx, providerIds) // the relay checks which rows are really gone
    }

    /** All message ids of a conversation (for deleting it). */
    fun threadRows(ctx: Context, threadId: Long): List<Long> =
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), null,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } } ?: emptyList()
}

/** Radio result for a message we sent (not exported; only our PendingIntent reaches it). */
class SmsSentReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_LAST = "last"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val uri = intent.data ?: return
        val ok = resultCode == Activity.RESULT_OK
        val last = intent.getBooleanExtra(EXTRA_LAST, true)
        if (ok && !last) return
        val pending = goAsync() // provider calls off the main thread
        Thread {
            try {
                // Any failed part wins: SENT never overwrites FAILED, whichever callback runs first.
                if (!ok) SmsSender.markType(ctx, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
                else SmsSender.markType(ctx, uri, Telephony.Sms.MESSAGE_TYPE_SENT, unlessType = Telephony.Sms.MESSAGE_TYPE_FAILED)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
