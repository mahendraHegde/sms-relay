package dev.smsrelay

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.smsrelay.core.MsgRecord
import dev.smsrelay.core.RelayStore

/** Outbox, id mapping, executed-command log and small metadata. Lives in app-private storage. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "relay.db", null, 5), RelayStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE msgs (id TEXT PRIMARY KEY, provider_id INTEGER NOT NULL UNIQUE, address TEXT NOT NULL, " +
                "body_hash TEXT NOT NULL, created INTEGER NOT NULL, env BLOB, gen TEXT NOT NULL DEFAULT '', sent_at INTEGER)",
        )
        createInbound(db)
        db.execSQL("CREATE INDEX msgs_unacked ON msgs(created) WHERE env IS NOT NULL")
        db.execSQL("CREATE TABLE cmds (cmd TEXT PRIMARY KEY, result TEXT NOT NULL, ts INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        createGone(db)
        createForwards(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE msgs ADD COLUMN gen TEXT NOT NULL DEFAULT ''")
            createInbound(db)
        }
        if (oldVersion < 3) db.execSQL("ALTER TABLE msgs ADD COLUMN sent_at INTEGER")
        if (oldVersion < 4) createGone(db)
        if (oldVersion < 5) createForwards(db)
    }

    /** Messages sent by a forwarding rule: which rule, and which relayed message they forward. */
    private fun createForwards(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS forwards (provider_id INTEGER PRIMARY KEY, rule TEXT NOT NULL, src TEXT NOT NULL)")
    }

    override fun addForward(providerId: Long, rule: String, srcId: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO forwards (provider_id, rule, src) VALUES (?, ?, ?)", arrayOf<Any>(providerId, rule, srcId))
    }

    override fun forwardOf(providerId: Long): Pair<String, String>? =
        readableDatabase.rawQuery("SELECT rule, src FROM forwards WHERE provider_id = ?", arrayOf(providerId.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        }

    /** Records that vanished on the phone and the reader has not acknowledged yet (see PhoneCore.onLocalDelete). */
    private fun createGone(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS gone (id TEXT PRIMARY KEY)")
    }

    override fun addGone(id: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO gone (id) VALUES (?)", arrayOf(id))
    }

    override fun gonePending(limit: Int): List<String> =
        readableDatabase.rawQuery("SELECT id FROM gone LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    override fun clearGone(ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.delete("gone", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Journal of SMS taken from SMS_DELIVER but not yet in the provider; see Relay.saveIncoming. */
    private fun createInbound(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS inbound (id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT NOT NULL, " +
                "body TEXT NOT NULL, date INTEGER NOT NULL, date_sent INTEGER NOT NULL, sub_id INTEGER NOT NULL)",
        )
    }

    data class Inbound(val id: Long, val address: String, val body: String, val date: Long, val dateSent: Long, val subId: Int)

    fun journal(address: String, body: String, date: Long, dateSent: Long, subId: Int): Long =
        writableDatabase.insertOrThrow("inbound", null, ContentValues().apply {
            put("address", address)
            put("body", body)
            put("date", date)
            put("date_sent", dateSent)
            put("sub_id", subId)
        })

    fun journaled(): List<Inbound> =
        readableDatabase.rawQuery("SELECT id, address, body, date, date_sent, sub_id FROM inbound ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add(Inbound(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getInt(5))) }
        }

    fun unjournal(id: Long) {
        writableDatabase.delete("inbound", "id = ?", arrayOf(id.toString()))
    }

    override fun insert(rec: MsgRecord) {
        val v = ContentValues().apply {
            put("id", rec.id)
            put("provider_id", rec.providerId)
            put("address", rec.address)
            put("body_hash", rec.bodyHash)
            put("created", rec.created)
            put("env", rec.env)
            put("gen", rec.gen)
        }
        writableDatabase.insertOrThrow("msgs", null, v)
    }

    override fun get(id: String): MsgRecord? =
        readableDatabase.rawQuery("SELECT id, provider_id, address, body_hash, created, env, gen FROM msgs WHERE id = ?", arrayOf(id))
            .use { c -> if (c.moveToFirst()) row(c) else null }

    override fun byProviderId(providerId: Long): MsgRecord? =
        readableDatabase.rawQuery(
            "SELECT id, provider_id, address, body_hash, created, env, gen FROM msgs WHERE provider_id = ?", arrayOf(providerId.toString()),
        ).use { c -> if (c.moveToFirst()) row(c) else null }

    override fun setEnv(id: String, env: ByteArray, gen: String) {
        writableDatabase.execSQL("UPDATE msgs SET env = ?, gen = ?, sent_at = NULL WHERE id = ?", arrayOf<Any>(env, gen, id))
    }

    override fun toSend(onlyNew: Boolean, sentBefore: Long, limit: Int): List<MsgRecord> =
        readableDatabase.rawQuery(
            "SELECT id, provider_id, address, body_hash, created, env, gen FROM msgs WHERE env IS NOT NULL AND " +
                (if (onlyNew) "sent_at IS NULL" else "(sent_at IS NULL OR sent_at <= ?)") + " ORDER BY created LIMIT ?",
            if (onlyNew) arrayOf(limit.toString()) else arrayOf(sentBefore.toString(), limit.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

    override fun markSent(id: String, gen: String, ts: Long) {
        writableDatabase.execSQL("UPDATE msgs SET sent_at = ? WHERE id = ? AND gen = ?", arrayOf<Any>(ts, id, gen))
    }

    override fun unackedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM msgs WHERE env IS NOT NULL", null).use { c -> c.moveToFirst(); c.getInt(0) }

    override fun markAcked(id: String, gen: String) {
        writableDatabase.execSQL("UPDATE msgs SET env = NULL WHERE id = ? AND gen = ?", arrayOf(id, gen))
    }

    override fun remove(id: String) {
        writableDatabase.delete("msgs", "id = ?", arrayOf(id))
    }

    override fun cmdResult(cmd: String): String? =
        readableDatabase.rawQuery("SELECT result FROM cmds WHERE cmd = ?", arrayOf(cmd)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    override fun saveCmdResult(cmd: String, resultJson: String, ts: Long) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO cmds (cmd, result, ts) VALUES (?, ?, ?)", arrayOf<Any>(cmd, resultJson, ts))
    }

    override fun pruneCmds(beforeMs: Long) {
        writableDatabase.delete("cmds", "ts < ?", arrayOf(beforeMs.toString()))
    }

    override fun getMeta(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    override fun setMeta(key: String, value: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)", arrayOf(key, value))
    }

    /** Forget everything tied to the current pairing. */
    fun wipe() {
        writableDatabase.apply {
            delete("msgs", null, null)
            delete("cmds", null, null)
            delete("meta", null, null)
            delete("gone", null, null)
            delete("forwards", null, null)
            // "inbound" is kept: those SMS were taken from the system and must still reach the provider.
        }
    }

    private fun row(c: android.database.Cursor) = MsgRecord(
        id = c.getString(0),
        providerId = c.getLong(1),
        address = c.getString(2),
        bodyHash = c.getString(3),
        created = c.getLong(4),
        env = if (c.isNull(5)) null else c.getBlob(5),
        gen = c.getString(6) ?: "",
    )
}
