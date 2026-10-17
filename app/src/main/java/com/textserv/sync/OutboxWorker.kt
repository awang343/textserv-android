package com.textserv.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.textserv.App
import com.textserv.data.HttpException
import com.textserv.data.ServerApi
import com.textserv.send.OutgoingAttachment
import com.textserv.send.Sender
import java.io.IOException

/** Sends every message the server has queued for us. Woken by push, also run on app start. */
class OutboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val api = ServerApi.fromPrefs(App.prefs) ?: return Result.success()
        return try {
            for (m in api.outbox()) {
                // Fetch attachments before claiming, so a network failure here leaves the
                // message in the outbox for the retry instead of stuck in "sending".
                val attachments = m.attachments.map {
                    OutgoingAttachment(api.downloadAttachment(it.id), it.contentType, it.filename)
                }
                // Claim it so a concurrent run (or a duplicate push) can't double-send.
                if (!api.setStatus(m.id, "sending")) continue
                try {
                    val recipients = m.participants.ifEmpty { m.address.split(',') }
                    Sender.send(applicationContext, recipients, m.body, attachments, serverId = m.id)
                } catch (e: Exception) {
                    // Couldn't even hand it to the radio (bad number, attachment too big, ...).
                    Log.w("textserv", "outbox send ${m.id} failed", e)
                    api.setStatus(m.id, "failed")
                }
            }
            Result.success()
        } catch (e: HttpException) {
            Log.w("textserv", "outbox: $e")
            if (e.retryable) Result.retry() else Result.failure()
        } catch (e: IOException) {
            Log.w("textserv", "outbox: $e")
            Result.retry()
        }
    }
}
