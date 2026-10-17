package com.textserv.data

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.textserv.App
import java.util.Locale

/** Phone-number normalization, so the same person always maps to the same server thread. */
object Phone {
    @Volatile private var cachedIso: String? = null

    private fun countryIso(ctx: Context): String = cachedIso ?: run {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val iso = (tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country).uppercase()
        cachedIso = iso
        iso
    }

    /** E.164 where possible; emails and alphanumeric senders pass through. */
    fun normalize(ctx: Context, raw: String): String {
        val s = raw.substringBefore("/TYPE=").trim()
        if (s.isEmpty()) return s
        if (s.contains('@')) return s.lowercase()
        if (s.any { it.isLetter() }) return s // alphanumeric sender id ("AMAZON")
        val digits = s.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } <= 6) return digits // short code
        return PhoneNumberUtils.formatNumberToE164(digits, countryIso(ctx)) ?: digits
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
        return own.any { it == n || PhoneNumberUtils.areSamePhoneNumber(it, n, countryIso(ctx)) }
    }
}
