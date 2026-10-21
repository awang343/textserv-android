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
 * Deletes the messages of threads deleted on a client (the server lists them until we say
 * they're gone), and updates the notifications of the threads they were in.
 */
class DeleteWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val api = ServerApi.fromPrefs(App.prefs) ?: return Result.success()
        val ctx = applicationContext
        return try {
            val refs = api.pendingDeletes()
            if (refs.isEmpty()) return Result.success()
            for (thread in TelephonyStore.deleteByRefs(ctx, refs)) Notifier.notifyThread(ctx, thread, quiet = true)
            api.deletesDone(refs)
            Log.i("textserv", "deleted ${refs.size} messages of threads deleted elsewhere")
            Result.success()
        } catch (e: HttpException) {
            Log.w("textserv", "deletes: $e")
            if (e.retryable) Result.retry() else Result.failure()
        } catch (e: IOException) {
            Log.w("textserv", "deletes: $e")
            Result.retry()
        }
    }
}
