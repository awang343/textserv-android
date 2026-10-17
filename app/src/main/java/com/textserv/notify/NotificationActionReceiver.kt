package com.textserv.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.textserv.data.TelephonyStore
import com.textserv.mms.MmsDownloader
import com.textserv.send.Sender
import com.textserv.util.goAsyncIO

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REPLY = "com.textserv.REPLY"
        const val ACTION_MARK_READ = "com.textserv.MARK_READ"
        const val ACTION_RETRY_DOWNLOAD = "com.textserv.RETRY_DOWNLOAD"
        const val EXTRA_THREAD_ID = "thread_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1)
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                if (text.isNullOrBlank() || threadId < 0) return
                goAsyncIO {
                    val recipients = TelephonyStore.threadAddresses(context, threadId)
                    runCatching { Sender.send(context, recipients, text, emptyList()) }
                    TelephonyStore.markThreadRead(context, threadId)
                    Notifier.cancelThread(context, threadId)
                }
            }
            ACTION_MARK_READ -> goAsyncIO {
                TelephonyStore.markThreadRead(context, threadId)
                Notifier.cancelThread(context, threadId)
            }
            ACTION_RETRY_DOWNLOAD -> {
                val tid = intent.getStringExtra("tid") ?: return
                NotificationManagerCompat.from(context).cancel(tid.hashCode())
                goAsyncIO {
                    MmsDownloader.download(
                        context, intent.getStringExtra("location") ?: return@goAsyncIO, tid,
                        intent.getStringExtra("from"), intent.getIntExtra("sub", -1),
                    )
                }
            }
        }
    }
}
