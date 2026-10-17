package com.textserv.util

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide scope for fire-and-forget work that must outlive a screen. */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Runs [block] off the main thread while keeping the broadcast alive until it finishes. */
fun BroadcastReceiver.goAsyncIO(block: suspend () -> Unit) {
    val pending = goAsync()
    appScope.launch {
        try {
            block()
        } catch (t: Throwable) {
            android.util.Log.e("textserv", "receiver failed", t)
        } finally {
            pending.finish()
        }
    }
}
