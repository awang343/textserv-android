package com.textserv.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.textserv.data.TelephonyStore
import com.textserv.notify.Notifier
import com.textserv.sync.SyncScheduler
import com.textserv.util.goAsyncIO

/** SMS_DELIVER: only the default SMS app gets this, and must store the message itself. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1)
        goAsyncIO {
            // A multipart SMS arrives as several PDUs in one broadcast.
            parts.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }.forEach { (address, msgs) ->
                val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
                val uri = TelephonyStore.insertIncomingSms(context, address, body, msgs.first().timestampMillis, subId)
                    ?: return@forEach
                val threadId = TelephonyStore.threadIdFor(context, listOf(address))
                Notifier.notifyThread(context, threadId)
                SyncScheduler.syncMessage(context, "sms:${uri.lastPathSegment}")
            }
        }
    }
}
