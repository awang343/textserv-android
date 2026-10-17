package com.textserv.data

import android.content.Context
import android.telephony.SubscriptionManager
import com.textserv.App

/** Phone-number normalization, so the same person always maps to the same server thread. */
object Phone {
    /**
     * E.164, assuming +1 (US/Canada) when no country code is given; emails, alphanumeric
     * senders and short codes pass through. Same rules as the server's `phone::normalize`
     * and the TUI's, so all three agree on every number.
     */
    @Suppress("UNUSED_PARAMETER")
    fun normalize(ctx: Context, raw: String): String {
        val s = raw.substringBefore("/TYPE=").trim()
        if (s.contains('@')) return s.lowercase()
        if (s.any { it.isLetter() }) return s // alphanumeric sender id ("AMAZON")
        val digits = s.filter { it in '0'..'9' }
        if (digits.length <= 6) return digits // short code (or empty)
        if (s.startsWith('+')) return "+$digits"
        if (digits.startsWith("011")) return "+" + digits.removePrefix("011") // US international prefix
        return when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith('1') -> "+$digits"
            else -> digits // e.g. a 7-digit local number: no way to know its area code
        }
    }

    /** Our own number(s), normalized. Empty if the SIM won't tell us and no override is set. */
    fun ownNumbers(ctx: Context): Set<String> {
        val out = HashSet<String>()
        App.prefs.myNumber.takeIf { it.isNotBlank() }?.let { out += normalize(ctx, it) }
        runCatching {
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            for (info in sm.activeSubscriptionInfoList.orEmpty()) {
                val n = sm.getPhoneNumber(info.subscriptionId)
                if (n.isNotBlank()) out += normalize(ctx, n)
            }
        }
        return out
    }

    fun isOwn(ctx: Context, addr: String, own: Set<String> = ownNumbers(ctx)): Boolean {
        val n = normalize(ctx, addr)
        return n in own
    }
}
