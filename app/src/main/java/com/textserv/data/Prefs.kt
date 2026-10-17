package com.textserv.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/** App settings plus small bits of sync bookkeeping. */
class Prefs(context: Context) {
    private val appContext = context.applicationContext
    private val sp: SharedPreferences = context.getSharedPreferences("textserv", Context.MODE_PRIVATE)

    /** phone_ref -> server message id, for sends the server asked us to make. */
    private val serverRefs: SharedPreferences = context.getSharedPreferences("server_refs", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** Manual override for our own number, used to drop ourselves from group participants. */
    var myNumber: String
        get() = sp.getString("my_number", "") ?: ""
        set(v) = sp.edit().putString("my_number", v.trim()).apply()

    val serverConfigured get() = serverUrl.isNotEmpty() && token.isNotEmpty()

    /** This phone's own number(s). The server requires them: clients pick phones by number. */
    val deviceNumbers: Set<String> get() = Phone.ownNumbers(appContext)

    /** Connected to a server, and has a number to identify itself with. */
    val readyToSync get() = serverConfigured && deviceNumbers.isNotEmpty()

    /** The server's storage key for this phone's messages; never shown to people (they see
     *  the number). A fresh install gets a fresh id, so a reset phone starts a clean history. */
    val deviceId: String
        @Synchronized get() = sp.getString("device_id", null)
            ?: UUID.randomUUID().toString().also { sp.edit().putString("device_id", it).apply() }

    /** Shown to other clients to tell connected phones apart, e.g. "Pixel 9". */
    val deviceName: String
        get() = Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Re-uploads all history, e.g. after switching to a different server or account. */
    fun resetHistorySync() {
        smsCursor = 0
        mmsCursor = 0
        syncRemaining = -1
    }

    var pushEndpoint: String?
        get() = sp.getString("push_endpoint", null)
        set(v) = sp.edit().putString("push_endpoint", v).apply()
    var pushPubKey: String?
        get() = sp.getString("push_p256dh", null)
        set(v) = sp.edit().putString("push_p256dh", v).apply()
    var pushAuth: String?
        get() = sp.getString("push_auth", null)
        set(v) = sp.edit().putString("push_auth", v).apply()
    /** Human-readable push state for the settings screen. */
    var pushStatus: String
        get() = sp.getString("push_status", "Not set up") ?: "Not set up"
        set(v) = sp.edit().putString("push_status", v).apply()

    // History sync: everything with a provider _id <= cursor has been sent to the server.
    var smsCursor: Long
        get() = sp.getLong("sms_cursor", 0)
        set(v) = sp.edit().putLong("sms_cursor", v).apply()
    var mmsCursor: Long
        get() = sp.getLong("mms_cursor", 0)
        set(v) = sp.edit().putLong("mms_cursor", v).apply()
    var syncRemaining: Int
        get() = sp.getInt("sync_remaining", -1)
        set(v) = sp.edit().putInt("sync_remaining", v).apply()
    var lastSyncError: String?
        get() = sp.getString("last_sync_error", null)
        set(v) = sp.edit().putString("last_sync_error", v).apply()

    fun rememberServerRef(phoneRef: String, serverId: String) =
        serverRefs.edit().putString(phoneRef, serverId).apply()
    fun serverIdFor(phoneRef: String): String? = serverRefs.getString(phoneRef, null)

    fun changes(): Flow<Unit> = callbackFlow {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        sp.registerOnSharedPreferenceChangeListener(l)
        trySend(Unit)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
}
