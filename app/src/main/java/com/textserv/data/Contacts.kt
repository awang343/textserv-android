package com.textserv.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import java.util.concurrent.ConcurrentHashMap

data class ContactHit(val name: String, val number: String, val label: String)

object Contacts {
    private val cache = ConcurrentHashMap<String, String>()

    private fun allowed(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Display name for a number, or the number itself. */
    fun name(ctx: Context, address: String): String {
        if (address.isBlank() || !allowed(ctx)) return address
        cache[address]?.let { return it }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: address
        cache[address] = name
        return name
    }

    fun title(ctx: Context, addresses: List<String>): String =
        addresses.joinToString(", ") { name(ctx, it) }.ifEmpty { "(no recipients)" }

    fun search(ctx: Context, query: String, limit: Int = 20): List<ContactHit> {
        if (query.isBlank() || !allowed(ctx)) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        val out = ArrayList<ContactHit>()
        runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                ),
                null, null, null,
            )?.use { c ->
                val seen = HashSet<String>()
                while (c.moveToNext() && out.size < limit) {
                    val number = c.getString(1) ?: continue
                    if (!seen.add(number.filter { it.isDigit() })) continue
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, c.getInt(2), c.getString(3)).toString()
                    out += ContactHit(c.getString(0) ?: number, number, label)
                }
            }
        }
        return out
    }
}
