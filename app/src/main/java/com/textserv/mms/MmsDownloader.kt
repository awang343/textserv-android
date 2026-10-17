package com.textserv.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import java.io.File

object MmsDownloader {
    const val EXTRA_LOCATION = "location"
    const val EXTRA_TID = "tid"
    const val EXTRA_FROM = "from"
    const val EXTRA_FILE = "file"
    const val EXTRA_SUB = "sub"

    private fun smsManager(ctx: Context, subId: Int): SmsManager {
        val base = ctx.getSystemService(SmsManager::class.java)
        val id = if (subId >= 0) subId else SmsManager.getDefaultSmsSubscriptionId()
        return if (id >= 0) base.createForSubscriptionId(id) else base
    }

    /** Asks the platform MMS service to fetch [location]; [MmsDownloadedReceiver] gets the result. */
    fun download(ctx: Context, location: String, tid: String, from: String?, subId: Int) {
        val dir = File(ctx.cacheDir, "mms").apply { mkdirs() }
        val file = File(dir, "dl-${System.nanoTime()}.pdu")
        file.createNewFile()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val intent = Intent(ctx, MmsDownloadedReceiver::class.java)
            .setAction("com.textserv.MMS_DOWNLOADED.${file.name}")
            .putExtra(EXTRA_LOCATION, location)
            .putExtra(EXTRA_TID, tid)
            .putExtra(EXTRA_FROM, from)
            .putExtra(EXTRA_FILE, file.absolutePath)
            .putExtra(EXTRA_SUB, subId)
        val pi = PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        smsManager(ctx, subId).downloadMultimediaMessage(ctx, location, uri, null, pi)
    }

    /** Tells the MMSC we've got the message (M-NotifyResp.ind), so it stops re-notifying. */
    fun acknowledge(ctx: Context, tid: String, subId: Int) {
        runCatching {
            val dir = File(ctx.cacheDir, "mms").apply { mkdirs() }
            val file = File(dir, "ack-${System.nanoTime()}.pdu").apply { writeBytes(PduComposer().notifyRespInd(tid)) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            smsManager(ctx, subId).sendMultimediaMessage(ctx, uri, null, null, null)
        }
    }
}
