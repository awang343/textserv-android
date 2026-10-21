package com.textserv.sync

import android.content.Context
import com.textserv.data.LogBody
import com.textserv.data.MsgStatus
import com.textserv.data.ServerApi
import com.textserv.data.SyncMsg
import com.textserv.data.TelephonyStore

/** Turns provider messages into server log entries (uploading MMS attachments on the way). */
object Uploader {
    suspend fun toLogBody(ctx: Context, api: ServerApi, m: SyncMsg): LogBody? {
        if (m.participants.isEmpty()) return null
        if (m.msg.status == MsgStatus.SENDING) return null // its sent-receiver will sync it
        val attachmentIds = m.parts.mapNotNull { a ->
            val bytes = TelephonyStore.readBytes(ctx, a.uri) ?: return@mapNotNull null
            api.uploadAttachment(bytes, a.contentType, a.name ?: "attachment").id
        }
        return LogBody(
            direction = if (m.msg.outgoing) "outgoing" else "incoming",
            addresses = m.participants,
            sender = if (m.msg.outgoing) null else m.msg.sender,
            body = m.msg.body,
            ts = m.msg.date,
            status = if (m.msg.status == MsgStatus.FAILED) "failed" else null,
            phoneRef = m.msg.ref,
            attachments = attachmentIds,
            read = !m.msg.outgoing && m.msg.read,
        )
    }
}
