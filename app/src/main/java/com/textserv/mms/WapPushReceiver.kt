package com.textserv.mms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import com.textserv.data.TelephonyStore
import com.textserv.util.goAsyncIO

/**
 * WAP_PUSH_DELIVER: an MMS notification (M-Notification.ind) saying a message is waiting on
 * the carrier's MMSC. We fetch it straight away.
 */
class WapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1)
        goAsyncIO {
            val pdu = try {
                PduParser(data).parse()
            } catch (e: Exception) {
                Log.w("textserv", "unparseable WAP push", e)
                return@goAsyncIO
            }
            // Delivery reports / read reports also arrive here; we only care about new messages.
            if (pdu.messageType != Mms.NOTIFICATION_IND) return@goAsyncIO
            val location = pdu.contentLocation ?: return@goAsyncIO
            val tid = pdu.transactionId ?: location
            if (TelephonyStore.mmsExistsWithTransactionId(context, tid)) return@goAsyncIO // duplicate push
            MmsDownloader.download(context, location, tid, pdu.from, subId)
        }
    }
}
