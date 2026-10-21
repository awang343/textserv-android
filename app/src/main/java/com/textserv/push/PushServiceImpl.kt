package com.textserv.push

import android.util.Log
import com.textserv.App
import com.textserv.data.ServerApi
import com.textserv.sync.SyncScheduler
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush callbacks. The server pushes `{"type":"read"}` when a thread was read on a
 * client, and `{"type":"send", ...}` when it wants us to send something; we treat any other
 * push as "check the outbox", which also covers missed pushes.
 */
class PushServiceImpl : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val prefs = App.prefs
        prefs.pushEndpoint = endpoint.url
        prefs.pushPubKey = endpoint.pubKeySet?.pubKey
        prefs.pushAuth = endpoint.pubKeySet?.auth
        prefs.pushStatus = "Got endpoint, registering…"
        SyncScheduler.registerPush(this)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        Log.i("textserv", "push received (${message.content.size} bytes, decrypted=${message.decrypted})")
        val type = runCatching {
            ServerApi.json.parseToJsonElement(message.content.decodeToString()).jsonObject["type"]?.jsonPrimitive?.content
        }.getOrNull()
        if (type == "read") SyncScheduler.syncReads(this) else SyncScheduler.processOutbox(this)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        App.prefs.pushStatus = "Registration failed: $reason"
    }

    override fun onUnregistered(instance: String) {
        val prefs = App.prefs
        prefs.pushEndpoint = null
        prefs.pushStatus = "Unregistered"
    }
}
