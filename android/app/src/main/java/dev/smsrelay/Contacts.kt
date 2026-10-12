package dev.smsrelay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.provider.ContactsContract

/**
 * Saved contact names for phone numbers, as any messaging app shows them. Needs READ_CONTACTS;
 * without it (or for sender IDs such as "EXBANK") callers just show the number. Lookups hit the
 * contacts provider once per address and are cached until the contacts change. Never call it on the
 * main thread.
 */
object Contacts {
    private const val MAX_CACHED = 500

    /** Least recently used addresses are evicted first; a cached null means "no saved contact". */
    private val cache = object : LinkedHashMap<String, String?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>) = size > MAX_CACHED
    }

    /** Bumped on every contacts change, so a lookup that raced an edit does not cache the old name. */
    @Volatile private var generation = 0L
    @Volatile private var observing = false

    fun canRead(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** The saved name for [address], or null (no permission, not saved, or not a phone number). */
    fun nameOf(ctx: Context, address: String): String? {
        if (!looksLikeNumber(address) || !canRead(ctx)) return null
        synchronized(cache) { if (cache.containsKey(address)) return cache[address] }
        watch(ctx.applicationContext)
        val gen = generation
        val name = runCatching { query(ctx, address) }.getOrNull()?.trim()?.ifEmpty { null }
        // Cache only while changes can invalidate it, and only if no change happened meanwhile.
        if (observing) synchronized(cache) { if (gen == generation) cache[address] = name }
        return name
    }

    private fun query(ctx: Context, address: String): String? =
        ctx.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Sender IDs ("EXBANK", "AD-SHOP") have no contact; only look up things that can be numbers. */
    private fun looksLikeNumber(a: String): Boolean =
        a.count { it in '0'..'9' } >= 3 && a.all { it in '0'..'9' || it in "+ ()-." }

    /** Contacts edited or added: forget cached names (registered once, on first use). */
    private fun watch(ctx: Context) {
        if (observing) return
        synchronized(this) {
            if (observing) return
            observing = runCatching {
                ctx.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        synchronized(cache) {
                            generation++
                            cache.clear()
                        }
                    }
                })
            }.isSuccess
        }
    }
}
