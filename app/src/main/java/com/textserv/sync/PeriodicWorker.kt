package com.textserv.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class PeriodicWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        SyncScheduler.kick(applicationContext)
        return Result.success()
    }
}
