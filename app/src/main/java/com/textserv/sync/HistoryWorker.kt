package com.textserv.sync

import android.content.Context
import android.provider.Telephony
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.textserv.App
import com.textserv.data.HttpException
import com.textserv.data.LogBody
import com.textserv.data.ServerApi
import com.textserv.data.TelephonyStore
import java.io.IOException

/**
 * Walks the provider in _id order and uploads everything past the saved cursors. The first run
 * is the full history import; later runs just catch up on anything a live sync missed.
 * Resumable: cursors are saved after every batch, and the server dedupes by phone_ref.
 */
class HistoryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val ctx get() = applicationContext

    override suspend fun doWork(): Result {
        val prefs = App.prefs
        val api = ServerApi.fromPrefs(prefs) ?: return Result.success()
        return try {
            prefs.syncRemaining = TelephonyStore.countAfter(ctx, prefs.smsCursor, prefs.mmsCursor)
            syncKind(api, isMms = false, batch = 200)
            syncKind(api, isMms = true, batch = 10)
            // Stopped by the system (e.g. the 10-minute worker limit): pick up where we left off.
            if (isStopped) return Result.retry()
            prefs.syncRemaining = 0
            prefs.lastSyncError = null
            Result.success()
        } catch (e: HttpException) {
            Log.w("textserv", "history sync: $e")
            prefs.lastSyncError = e.message
            if (e.retryable) Result.retry() else Result.failure()
        } catch (e: IOException) {
            Log.w("textserv", "history sync: $e")
            prefs.lastSyncError = e.message
            Result.retry()
        }
    }

    private suspend fun syncKind(api: ServerApi, isMms: Boolean, batch: Int) {
        val prefs = App.prefs
        val uri = if (isMms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI
        while (!isStopped) {
            val cursor = if (isMms) prefs.mmsCursor else prefs.smsCursor
            val scannedTo = TelephonyStore.lastIdUpTo(ctx, uri, cursor, batch)
            if (scannedTo == cursor) return
            val ids = (if (isMms) TelephonyStore.mmsIdsAfter(ctx, cursor, batch) else TelephonyStore.smsIdsAfter(ctx, cursor, batch))
                .filter { it <= scannedTo }
            val refs = ids.map { (if (isMms) "mms:" else "sms:") + it }
                // The server already knows sends it asked for, and anything a live sync delivered.
                .filter { prefs.serverIdFor(it) == null }
            val known = if (refs.isEmpty()) emptySet() else api.knownRefs(refs)
            val bodies = ArrayList<LogBody>()
            for (ref in refs) {
                if (ref in known) continue
                val m = TelephonyStore.loadForSync(ctx, ref) ?: continue
                Uploader.toLogBody(ctx, api, m)?.let { bodies += it }
            }
            if (bodies.isNotEmpty()) api.logBatch(bodies)
            if (isMms) prefs.mmsCursor = scannedTo else prefs.smsCursor = scannedTo
            prefs.syncRemaining = TelephonyStore.countAfter(ctx, prefs.smsCursor, prefs.mmsCursor)
        }
    }
}
