package com.textserv.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.textserv.send.Sender
import com.textserv.util.appScope
import kotlinx.coroutines.launch

/** "Respond via message" (quick reply to an incoming call). Required for the SMS role. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recipients = intent?.data?.let(Sender::recipientsFromUri).orEmpty()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (recipients.isEmpty() || text.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        appScope.launch {
            runCatching { Sender.send(applicationContext, recipients, text, emptyList()) }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
