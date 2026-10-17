package com.textserv.mms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import com.textserv.data.TelephonyStore
import com.textserv.send.Sender
import com.textserv.sync.SyncScheduler
import com.textserv.util.goAsyncIO
import java.io.File

/** Result of an outgoing MMS. */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val code = resultCode
        val response = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)
        goAsyncIO {
            intent.getStringExtra(Sender.EXTRA_PDU_FILE)?.let { File(it).delete() }
            val conf = response?.let { runCatching { PduParser(it).parse() }.getOrNull() }
            val ok = code == Activity.RESULT_OK &&
                (conf == null || conf.responseStatus == null || conf.responseStatus == Mms.RESPONSE_STATUS_OK)
            TelephonyStore.setMmsBox(
                context, uri,
                if (ok) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED,
                conf?.messageId,
            )
            SyncScheduler.syncMessage(context, "mms:${uri.lastPathSegment}")
        }
    }
}
