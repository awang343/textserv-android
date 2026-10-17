package com.textserv.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.textserv.App
import com.textserv.data.HttpException
import com.textserv.data.MsgStatus
import com.textserv.data.ServerApi
import com.textserv.data.TelephonyStore
import java.io.IOException

/** Reports one message to the server: as a status update if the server asked for it, else a log. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object { const val KEY_REF = "ref" }

    override suspend fun doWork(): Result {
        val prefs = App.prefs
        val api = ServerApi.fromPrefs(prefs) ?: return Result.success()
        val ref = inputData.getString(KEY_REF) ?: return Result.failure()
        val m = TelephonyStore.loadForSync(applicationContext, ref) ?: return Result.success() // deleted
        return try {
            val serverId = prefs.serverIdFor(ref)
            if (serverId != null) {
                when (m.msg.status) {
                    MsgStatus.SENT -> api.setStatus(serverId, "sent", ref)
                    MsgStatus.FAILED -> api.setStatus(serverId, "failed", ref)
                    else -> {}
                }
            } else if (ref !in api.knownRefs(listOf(ref))) { // a retry may follow a lost response
                Uploader.toLogBody(applicationContext, api, m)?.let { api.log(it) }
            }
            Result.success()
        } catch (e: HttpException) {
            Log.w("textserv", "sync $ref: $e")
            if (e.retryable) Result.retry() else Result.failure()
        } catch (e: IOException) {
            Log.w("textserv", "sync $ref: $e")
            Result.retry()
        }
    }
}
