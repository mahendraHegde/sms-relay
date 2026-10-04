package dev.smsrelay

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import dev.smsrelay.core.ProviderSms
import dev.smsrelay.core.SmsStore

/** The phone's real SMS database. Writes and deletes only work while we hold the default SMS role. */
class AndroidSmsStore(private val ctx: Context) : SmsStore {
    private val cr = ctx.contentResolver

    private companion object {
        val COLUMNS = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE,
        )
        val RELAYED_SQL = ProviderSms.RELAYED.joinToString(",", "${Telephony.Sms.TYPE} IN (", ")")
    }

    /**
     * As the default SMS app we, not the system, must save incoming messages. If an identical row
     * (same sender, text and receive time) exists, a previous attempt got as far as the provider:
     * return it instead of inserting a duplicate.
     */
    fun insertIncoming(address: String, body: String, date: Long, dateSent: Long, subId: Int): ProviderSms? {
        find(address, body, date)?.let { return it }
        val v = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.DATE_SENT, dateSent)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        }
        val uri = cr.insert(Telephony.Sms.Inbox.CONTENT_URI, v) ?: return null
        // Without the default-SMS role the provider "succeeds" with a fake row id 0: not saved, so the
        // message must stay in the journal.
        val id = ContentUris.parseId(uri)
        if (id <= 0) return null
        return ProviderSms(id, address, body, date, dateSent, ProviderSms.TYPE_INBOX)
    }

    private fun find(address: String, body: String, date: Long): ProviderSms? =
        cr.query(
            Telephony.Sms.Inbox.CONTENT_URI, COLUMNS,
            "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} = ?",
            arrayOf(address, body, date.toString()), null,
        )?.use { c -> if (c.moveToFirst()) row(c) else null }

    override fun query(providerId: Long): ProviderSms? =
        cr.query(
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId),
            COLUMNS, null, null, null,
        )?.use { c -> if (c.moveToFirst()) row(c) else null }

    override fun delete(providerId: Long): Boolean =
        (cr.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId), null, null) == 1).also {
            // A deleted message must not stay readable in the notification shade.
            if (it) dev.smsrelay.ui.Notifier.dropDeleted(ctx, listOf(providerId))
        }

    override fun messagesSince(sinceMs: Long, limit: Int): List<ProviderSms> =
        cr.query(
            Telephony.Sms.CONTENT_URI, COLUMNS,
            "${Telephony.Sms.DATE} >= ? AND $RELAYED_SQL", arrayOf(sinceMs.toString()),
            // Newest first so a capped scan keeps the recent messages, then oldest-first for sending.
            // The cap is applied while reading: some providers reject LIMIT inside the sort order.
            "${Telephony.Sms.DATE} DESC",
        )?.use { c -> buildList { while (size < limit && c.moveToNext()) add(row(c)) } }?.asReversed() ?: emptyList()

    private fun row(c: android.database.Cursor) = ProviderSms(
        providerId = c.getLong(0),
        address = c.getString(1) ?: "",
        body = c.getString(2) ?: "",
        date = c.getLong(3),
        dateSent = c.getLong(4),
        type = c.getInt(5),
    )
}
