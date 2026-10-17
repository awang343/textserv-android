package com.textserv.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Push one provider message ("sms:12" / "mms:34") to the server, retrying until it lands. */
    fun syncMessage(ctx: Context, ref: String) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_REF to ref))
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync-$ref", ExistingWorkPolicy.REPLACE, req)
    }

    /** Upload anything in the provider the server hasn't seen (initial history import + catch-up). */
    fun syncHistory(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<HistoryWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("history", ExistingWorkPolicy.KEEP, req)
    }

    /** Fetch and send whatever the server wants sent. Expedited: a person is waiting on it. */
    fun processOutbox(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(online)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("outbox", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun registerPush(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RegisterWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("register", ExistingWorkPolicy.REPLACE, req)
    }

    /** Safety net in case a push or a live sync got lost. */
    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<PeriodicWorker>(6, TimeUnit.HOURS)
            .setConstraints(online)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Everything worth doing when the app comes to the foreground or settings change. */
    fun kick(ctx: Context) {
        syncHistory(ctx)
        processOutbox(ctx)
    }
}
