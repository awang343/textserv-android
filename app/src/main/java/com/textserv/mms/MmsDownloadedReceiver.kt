package com.textserv.mms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.textserv.data.Phone
import com.textserv.data.TelephonyStore
import com.textserv.notify.Notifier
import com.textserv.sync.SyncScheduler
import com.textserv.util.goAsyncIO
import java.io.File

/** Download finished: parse the M-Retrieve.conf, store it, notify, sync. */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val file = intent.getStringExtra(MmsDownloader.EXTRA_FILE)?.let(::File) ?: return
        val location = intent.getStringExtra(MmsDownloader.EXTRA_LOCATION) ?: return
        val tid = intent.getStringExtra(MmsDownloader.EXTRA_TID) ?: location
        val from = intent.getStringExtra(MmsDownloader.EXTRA_FROM)
        val subId = intent.getIntExtra(MmsDownloader.EXTRA_SUB, -1)
        val code = resultCode
        goAsyncIO {
            try {
                if (code != Activity.RESULT_OK || file.length() == 0L) {
                    Log.w("textserv", "MMS download failed: result=$code")
                    Notifier.notifyDownloadFailed(context, location, tid, from, subId)
                    return@goAsyncIO
                }
                val pdu = PduParser(file.readBytes()).parse()
                if (pdu.messageType != Mms.RETRIEVE_CONF) {
                    Notifier.notifyDownloadFailed(context, location, tid, from, subId)
                    return@goAsyncIO
                }
                if (TelephonyStore.mmsExistsWithTransactionId(context, tid)) return@goAsyncIO

                val sender = pdu.from ?: from ?: ""
                val participants = TelephonyStore.participants(context, sender, pdu.to + pdu.cc, outgoing = false)
                val threadId = TelephonyStore.threadIdFor(context, participants)
                val addrs = buildList {
                    add(sender to TelephonyStore.ADDR_FROM)
                    pdu.to.forEach { add(it to TelephonyStore.ADDR_TO) }
                    pdu.cc.forEach { add(it to TelephonyStore.ADDR_CC) }
                }
                val uri = TelephonyStore.insertMms(
                    context, threadId,
                    box = Telephony.Mms.MESSAGE_BOX_INBOX,
                    messageType = Mms.RETRIEVE_CONF,
                    addresses = addrs,
                    parts = pdu.parts,
                    subject = pdu.subject,
                    dateSec = pdu.date ?: (System.currentTimeMillis() / 1000),
                    transactionId = tid,
                    messageId = pdu.messageId,
                    contentType = pdu.contentType ?: Mms.CT_MULTIPART_RELATED,
                    subId = subId,
                ) ?: return@goAsyncIO
                MmsDownloader.acknowledge(context, tid, subId)
                Notifier.notifyThread(context, threadId)
                SyncScheduler.syncMessage(context, "mms:${uri.lastPathSegment}")
                Log.i("textserv", "stored MMS from ${Phone.normalize(context, sender)} with ${pdu.parts.size} parts")
            } finally {
                file.delete()
            }
        }
    }
}
