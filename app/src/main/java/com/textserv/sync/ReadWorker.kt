package com.textserv.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.textserv.App
import com.textserv.data.HttpException
import com.textserv.data.ServerApi
import com.textserv.data.TelephonyStore
import com.textserv.notify.Notifier
import java.io.IOException

/**
 * Keeps read state in step with the server both ways: reports messages just read here
 * ([KEY_REFS]), then marks read the messages that were read on a client and updates their
 * notifications.
 */
class ReadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object { const val KEY_REFS = "refs" }

    override suspend fun doWork(): Result {
        val api = ServerApi.fromPrefs(App.prefs) ?: return Result.success()
        val ctx = applicationContext
        val refs = inputData.getStringArray(KEY_REFS).orEmpty().toList()
        return try {
            if (refs.isNotEmpty()) api.reportRead(refs)
            val unread = TelephonyStore.unreadRefs(ctx)
            if (unread.isNotEmpty()) {
                val readElsewhere = api.readRefs(unread.map { it.first })
                if (readElsewhere.isNotEmpty()) {
                    TelephonyStore.markRead(ctx, readElsewhere)
                    for (thread in unread.filter { it.first in readElsewhere }.map { it.second }.distinct()) {
                        Notifier.notifyThread(ctx, thread, quiet = true)
                    }
                }
            }
            Result.success()
        } catch (e: HttpException) {
            Log.w("textserv", "read sync: $e")
            if (e.retryable) Result.retry() else Result.failure()
        } catch (e: IOException) {
            Log.w("textserv", "read sync: $e")
            Result.retry()
        }
    }
}
