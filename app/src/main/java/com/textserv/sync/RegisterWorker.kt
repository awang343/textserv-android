package com.textserv.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.textserv.App
import com.textserv.data.HttpException
import com.textserv.data.ServerApi
import java.io.IOException

/** Tells the server our UnifiedPush endpoint + Web Push keys. */
class RegisterWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = App.prefs
        val api = ServerApi.fromPrefs(prefs) ?: return Result.success()
        val endpoint = prefs.pushEndpoint ?: return Result.success()
        return try {
            api.register(endpoint, prefs.pushPubKey, prefs.pushAuth)
            prefs.pushStatus = "Registered with server"
            SyncScheduler.processOutbox(applicationContext)
            Result.success()
        } catch (e: HttpException) {
            prefs.pushStatus = "Server rejected registration: ${e.code}"
            if (e.retryable) Result.retry() else Result.failure()
        } catch (e: IOException) {
            prefs.pushStatus = "Waiting to register: ${e.message}"
            Result.retry()
        }
    }
}
