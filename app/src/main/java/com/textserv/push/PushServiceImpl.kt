package com.textserv.push

import android.util.Log
import com.textserv.App
import com.textserv.sync.SyncScheduler
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush callbacks. The server pushes `{"type":"send", ...}` when it wants us to send
 * something; we treat any push as "check the outbox", which also covers missed pushes.
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
        SyncScheduler.processOutbox(this)
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
