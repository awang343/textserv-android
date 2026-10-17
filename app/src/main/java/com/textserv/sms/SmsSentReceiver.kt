package com.textserv.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.textserv.data.TelephonyStore
import com.textserv.sync.SyncScheduler
import com.textserv.util.goAsyncIO

/** Result of an outgoing SMS: moves the provider row from outbox to sent/failed, then syncs it. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val ok = resultCode == Activity.RESULT_OK
        val errorCode = intent.getIntExtra("errorCode", 0)
        goAsyncIO {
            TelephonyStore.setSmsType(
                context, uri,
                if (ok) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED,
                if (ok) 0 else (errorCode.takeIf { it != 0 } ?: resultCode),
            )
            SyncScheduler.syncMessage(context, "sms:${uri.lastPathSegment}")
        }
    }
}
