package com.textserv.send

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Telephony
import android.telephony.SmsManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.textserv.App
import com.textserv.data.Phone
import com.textserv.data.TelephonyStore
import com.textserv.mms.Mms
import com.textserv.mms.MmsSentReceiver
import com.textserv.mms.PduComposer
import com.textserv.mms.PduPart
import com.textserv.sms.SmsSentReceiver
import com.textserv.sync.SyncScheduler
import java.io.File
import java.util.UUID

class OutgoingAttachment(val bytes: ByteArray, val contentType: String, val name: String)

class SendException(message: String) : Exception(message)

/**
 * The single entry point for sending, used by the UI, notification replies and server-requested
 * sends alike. Writes the message to the provider first (so it shows up immediately as
 * "sending"), then hands it to the radio; the *SentReceivers finish the job.
 */
object Sender {
    const val EXTRA_SERVER_ID = "server_id"
    const val EXTRA_PDU_FILE = "pdu_file"

    private fun smsManager(ctx: Context): SmsManager {
        val subId = SmsManager.getDefaultSmsSubscriptionId()
        val base = ctx.getSystemService(SmsManager::class.java)
        return if (subId >= 0) base.createForSubscriptionId(subId) else base
    }

    /** "sms:+1555,+1556?body=hi" -> ["+1555", "+1556"] */
    fun recipientsFromUri(uri: Uri): List<String> =
        Uri.decode(uri.schemeSpecificPart ?: "").substringBefore('?')
            .split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    fun bodyFromUri(uri: Uri): String? =
        Uri.decode(uri.schemeSpecificPart ?: "").substringAfter('?', "")
            .split('&').firstOrNull { it.startsWith("body=") }?.removePrefix("body=")

    fun attachmentFromUri(ctx: Context, uri: Uri): OutgoingAttachment {
        val cr = ctx.contentResolver
        val type = cr.getType(uri) ?: "application/octet-stream"
        val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: ("attachment." + (MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "bin"))
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw SendException("Can't read $name")
        return OutgoingAttachment(bytes, type, name)
    }

    /**
     * Sends [text] + [attachments] to [recipients]. [serverId] is set when the server asked
     * for this send, so the outcome gets reported back to that server message.
     * Returns the provider thread id.
     */
    suspend fun send(
        ctx: Context,
        recipients: List<String>,
        text: String,
        attachments: List<OutgoingAttachment>,
        serverId: String? = null,
    ): Long {
        val to = recipients.map { Phone.normalize(ctx, it) }.filter { it.isNotEmpty() }.distinct()
        if (to.isEmpty()) throw SendException("No recipients")
        if (text.isBlank() && attachments.isEmpty()) throw SendException("Nothing to send")
        val threadId = TelephonyStore.threadIdFor(ctx, to)
        if (to.size == 1 && attachments.isEmpty()) sendSms(ctx, to[0], text, threadId, serverId)
        else sendMms(ctx, to, text, attachments, threadId, serverId)
        return threadId
    }

    private fun sendSms(ctx: Context, to: String, text: String, threadId: Long, serverId: String?) {
        val subId = SmsManager.getDefaultSmsSubscriptionId()
        val uri = TelephonyStore.insertOutgoingSms(ctx, to, text, threadId, subId)
            ?: throw SendException("Couldn't save message")
        val ref = "sms:${uri.lastPathSegment}"
        if (serverId != null) App.prefs.rememberServerRef(ref, serverId)

        val sent = PendingIntent.getBroadcast(
            ctx, uri.lastPathSegment!!.toInt(),
            Intent(ctx, SmsSentReceiver::class.java).setData(uri),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            val sm = smsManager(ctx)
            val parts = sm.divideMessage(text)
            if (parts.size <= 1) {
                sm.sendTextMessage(to, null, text, sent, null)
            } else {
                // Only the last part reports back; that's when the whole message is out.
                val intents = ArrayList<PendingIntent?>(parts.size).apply {
                    repeat(parts.size - 1) { add(null) }
                    add(sent)
                }
                sm.sendMultipartTextMessage(to, null, parts, intents, null)
            }
        } catch (e: Exception) {
            TelephonyStore.setSmsType(ctx, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
            SyncScheduler.syncMessage(ctx, ref)
            throw SendException("Send failed: ${e.message}")
        }
    }

    private fun sendMms(
        ctx: Context,
        to: List<String>,
        text: String,
        attachments: List<OutgoingAttachment>,
        threadId: Long,
        serverId: String?,
    ) {
        val sm = smsManager(ctx)
        val maxSize = runCatching {
            sm.carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 300 * 1024)
        }.getOrDefault(300 * 1024).coerceAtLeast(100 * 1024)

        val media = MmsMedia.fit(ctx, attachments, maxSize - text.length - 4096)
        val parts = buildParts(text, media)
        val tid = "T" + UUID.randomUUID().toString().replace("-", "").take(12)
        val subId = SmsManager.getDefaultSmsSubscriptionId()

        val own = Phone.ownNumbers(ctx).firstOrNull() ?: "insert-address-token"
        val uri = TelephonyStore.insertMms(
            ctx, threadId,
            box = Telephony.Mms.MESSAGE_BOX_OUTBOX,
            messageType = Mms.SEND_REQ,
            addresses = listOf(own to TelephonyStore.ADDR_FROM) + to.map { it to TelephonyStore.ADDR_TO },
            parts = parts, subject = null, dateSec = System.currentTimeMillis() / 1000,
            transactionId = tid, messageId = null, contentType = Mms.CT_MULTIPART_RELATED, subId = subId,
        ) ?: throw SendException("Couldn't save message")
        val ref = "mms:${uri.lastPathSegment}"
        if (serverId != null) App.prefs.rememberServerRef(ref, serverId)

        try {
            val pdu = PduComposer().sendReq(tid, to, null, parts)
            val dir = File(ctx.cacheDir, "mms").apply { mkdirs() }
            val file = File(dir, "send-${uri.lastPathSegment}.pdu").apply { writeBytes(pdu) }
            val contentUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            val sent = PendingIntent.getBroadcast(
                ctx, uri.lastPathSegment!!.toInt(),
                Intent(ctx, MmsSentReceiver::class.java).setData(uri).putExtra(EXTRA_PDU_FILE, file.absolutePath),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            sm.sendMultimediaMessage(ctx, contentUri, null, null, sent)
        } catch (e: Exception) {
            TelephonyStore.setMmsBox(ctx, uri, Telephony.Mms.MESSAGE_BOX_FAILED)
            SyncScheduler.syncMessage(ctx, ref)
            throw SendException("Send failed: ${e.message}")
        }
    }

    /** SMIL presentation + text + media, the layout every MMS client understands. */
    private fun buildParts(text: String, media: List<OutgoingAttachment>): List<PduPart> {
        val parts = ArrayList<PduPart>()
        val body = StringBuilder()
        val named = media.mapIndexed { i, a ->
            val ext = a.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }
                ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(a.contentType) ?: "bin"
            "${a.contentType.substringBefore('/')}_$i.$ext" to a
        }
        for ((name, a) in named) {
            val tag = when {
                a.contentType.startsWith("image/") -> "img"
                a.contentType.startsWith("video/") -> "video"
                a.contentType.startsWith("audio/") -> "audio"
                else -> "ref"
            }
            val region = if (tag == "img" || tag == "video") " region=\"Image\"" else ""
            body.append("<par dur=\"5000ms\"><$tag src=\"$name\"$region/></par>")
        }
        if (text.isNotEmpty()) body.append("<par dur=\"5000ms\"><text src=\"text_0.txt\" region=\"Text\"/></par>")
        val smil = "<smil><head><layout><root-layout/>" +
            "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>" +
            "<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>" +
            "</layout></head><body>$body</body></smil>"
        parts += PduPart(Mms.CT_SMIL, smil.toByteArray(), Mms.CHARSET_UTF8, "smil.xml", "smil.xml", "smil")
        for ((name, a) in named) parts += PduPart(a.contentType, a.bytes, null, name, name, name)
        if (text.isNotEmpty()) {
            parts += PduPart(Mms.CT_TEXT, text.toByteArray(Charsets.UTF_8), Mms.CHARSET_UTF8, "text_0.txt", "text_0.txt", "text_0")
        }
        return parts
    }
}
