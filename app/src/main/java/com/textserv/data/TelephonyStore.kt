package com.textserv.data

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import androidx.core.database.getStringOrNull
import com.textserv.mms.PduPart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class Conversation(
    val threadId: Long,
    val addresses: List<String>,
    val snippet: String,
    val date: Long,
    val read: Boolean,
)

data class Attachment(val uri: Uri, val contentType: String, val name: String?)

enum class MsgStatus { RECEIVED, SENDING, SENT, FAILED }

data class Msg(
    val id: Long,
    val isMms: Boolean,
    val threadId: Long,
    val date: Long, // ms
    val outgoing: Boolean,
    val status: MsgStatus,
    val sender: String?, // incoming only
    val body: String,
    val attachments: List<Attachment>,
    val read: Boolean,
) {
    val ref get() = (if (isMms) "mms:" else "sms:") + id
    val uri: Uri get() = Uri.withAppendedPath(if (isMms) Mms.CONTENT_URI else Sms.CONTENT_URI, id.toString())
}

/** Full view of a message for uploading to the server. */
data class SyncMsg(
    val msg: Msg,
    val participants: List<String>, // normalized, excluding us
    val parts: List<Attachment>,    // non-text, non-SMIL MMS parts
)

/**
 * Everything that touches the system SMS/MMS provider. As the default SMS app we're the one
 * responsible for writing both incoming and outgoing messages here.
 */
object TelephonyStore {
    private val MMS_PART_URI: Uri = Uri.parse("content://mms/part")
    private val CANONICAL_ADDRESSES: Uri = Uri.parse("content://mms-sms/canonical-addresses")
    private val THREADS_SIMPLE: Uri =
        Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()

    const val ADDR_FROM = 137
    const val ADDR_TO = 151
    const val ADDR_CC = 130

    /** Emits once immediately and then whenever any SMS/MMS changes. */
    fun changes(ctx: Context): Flow<Unit> = callbackFlow {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        ctx.contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, obs)
        ctx.contentResolver.registerContentObserver(Sms.CONTENT_URI, true, obs)
        ctx.contentResolver.registerContentObserver(Mms.CONTENT_URI, true, obs)
        trySend(Unit)
        awaitClose { ctx.contentResolver.unregisterContentObserver(obs) }
    }

    // ---------------------------------------------------------------- reading

    private fun canonicalAddresses(ctx: Context): Map<Long, String> {
        val out = HashMap<Long, String>()
        ctx.contentResolver.query(CANONICAL_ADDRESSES, arrayOf("_id", "address"), null, null, null)?.use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getString(1) ?: ""
        }
        return out
    }

    fun conversations(ctx: Context): List<Conversation> {
        val addrs = canonicalAddresses(ctx)
        val out = ArrayList<Conversation>()
        ctx.contentResolver.query(
            THREADS_SIMPLE,
            arrayOf("_id", "date", "recipient_ids", "snippet", "read", "message_count"),
            null, null, "date DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == 0) continue // empty thread (e.g. abandoned new-conversation)
                val ids = (c.getStringOrNull(2) ?: "").split(' ').mapNotNull { it.toLongOrNull() }
                out += Conversation(
                    threadId = c.getLong(0),
                    addresses = ids.mapNotNull { addrs[it] },
                    snippet = c.getStringOrNull(3) ?: "",
                    date = c.getLong(1),
                    read = c.getInt(4) != 0,
                )
            }
        }
        return out
    }

    fun threadAddresses(ctx: Context, threadId: Long): List<String> {
        val addrs = canonicalAddresses(ctx)
        ctx.contentResolver.query(THREADS_SIMPLE, arrayOf("_id", "recipient_ids"), "_id = ?", arrayOf(threadId.toString()), null)?.use { c ->
            if (c.moveToFirst()) {
                return (c.getStringOrNull(1) ?: "").split(' ').mapNotNull { it.toLongOrNull()?.let(addrs::get) }
            }
        }
        return emptyList()
    }

    fun threadIdFor(ctx: Context, addresses: Collection<String>): Long =
        if (addresses.size == 1) Telephony.Threads.getOrCreateThreadId(ctx, addresses.first())
        else Telephony.Threads.getOrCreateThreadId(ctx, addresses.toSet())

    private fun smsStatus(type: Int): MsgStatus = when (type) {
        Sms.MESSAGE_TYPE_INBOX -> MsgStatus.RECEIVED
        Sms.MESSAGE_TYPE_SENT -> MsgStatus.SENT
        Sms.MESSAGE_TYPE_FAILED -> MsgStatus.FAILED
        else -> MsgStatus.SENDING
    }

    private fun mmsStatus(box: Int): MsgStatus = when (box) {
        Mms.MESSAGE_BOX_INBOX -> MsgStatus.RECEIVED
        Mms.MESSAGE_BOX_SENT -> MsgStatus.SENT
        Mms.MESSAGE_BOX_FAILED -> MsgStatus.FAILED
        else -> MsgStatus.SENDING
    }

    private val SMS_COLS = arrayOf("_id", "thread_id", "address", "body", "date", "type", "read")
    private val MMS_COLS = arrayOf("_id", "thread_id", "date", "msg_box", "read", "sub")

    private fun smsFromCursor(c: android.database.Cursor): Msg {
        val type = c.getInt(5)
        return Msg(
            id = c.getLong(0), isMms = false, threadId = c.getLong(1),
            date = c.getLong(4), outgoing = type != Sms.MESSAGE_TYPE_INBOX, status = smsStatus(type),
            sender = if (type == Sms.MESSAGE_TYPE_INBOX) c.getStringOrNull(2) else null,
            body = c.getStringOrNull(3) ?: "", attachments = emptyList(), read = c.getInt(6) != 0,
        )
    }

    private fun mmsFromCursor(ctx: Context, c: android.database.Cursor): Msg {
        val id = c.getLong(0)
        val box = c.getInt(3)
        val (text, atts) = mmsParts(ctx, id)
        val subject = c.getStringOrNull(5)?.takeIf { it.isNotBlank() }
        return Msg(
            id = id, isMms = true, threadId = c.getLong(1),
            date = c.getLong(2) * 1000, outgoing = box != Mms.MESSAGE_BOX_INBOX, status = mmsStatus(box),
            sender = if (box == Mms.MESSAGE_BOX_INBOX) mmsAddresses(ctx, id).firstOrNull { it.second == ADDR_FROM }?.first else null,
            body = listOfNotNull(subject, text.takeIf { it.isNotEmpty() }).joinToString("\n"),
            attachments = atts, read = c.getInt(4) != 0,
        )
    }

    /** Most recent [limit] messages of a thread, oldest first. */
    fun messages(ctx: Context, threadId: Long, limit: Int = 300): List<Msg> {
        val out = ArrayList<Msg>()
        val args = arrayOf(threadId.toString())
        ctx.contentResolver.query(Sms.CONTENT_URI, SMS_COLS, "thread_id = ? AND type != ${Sms.MESSAGE_TYPE_DRAFT}", args, "date DESC LIMIT $limit")?.use { c ->
            while (c.moveToNext()) out += smsFromCursor(c)
        }
        ctx.contentResolver.query(
            Mms.CONTENT_URI, MMS_COLS,
            "thread_id = ? AND m_type IN (128, 132) AND msg_box != ${Mms.MESSAGE_BOX_DRAFTS}", args, "date DESC LIMIT $limit",
        )?.use { c ->
            while (c.moveToNext()) out += mmsFromCursor(ctx, c)
        }
        out.sortBy { it.date }
        return out.takeLast(limit)
    }

    fun loadByRef(ctx: Context, ref: String): Msg? {
        val (kind, idStr) = ref.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        val id = idStr.toLongOrNull() ?: return null
        return if (kind == "sms") {
            ctx.contentResolver.query(Sms.CONTENT_URI, SMS_COLS, "_id = ?", arrayOf(id.toString()), null)?.use { c ->
                if (c.moveToFirst()) smsFromCursor(c) else null
            }
        } else {
            ctx.contentResolver.query(Mms.CONTENT_URI, MMS_COLS, "_id = ?", arrayOf(id.toString()), null)?.use { c ->
                if (c.moveToFirst()) mmsFromCursor(ctx, c) else null
            }
        }
    }

    /** (address, type) pairs for an MMS. */
    fun mmsAddresses(ctx: Context, mmsId: Long): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        ctx.contentResolver.query(Uri.parse("content://mms/$mmsId/addr"), arrayOf("address", "type"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val a = c.getStringOrNull(0) ?: continue
                if (a == "insert-address-token") continue
                out += a to c.getInt(1)
            }
        }
        return out
    }

    /** (concatenated text, media attachments) */
    private fun mmsParts(ctx: Context, mmsId: Long): Pair<String, List<Attachment>> {
        val texts = ArrayList<String>()
        val atts = ArrayList<Attachment>()
        ctx.contentResolver.query(
            MMS_PART_URI, arrayOf("_id", "ct", "text", "name", "cl", "fn"), "mid = ?", arrayOf(mmsId.toString()), "seq ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val ct = c.getStringOrNull(1)?.lowercase() ?: continue
                when {
                    ct == "application/smil" -> {}
                    ct == "text/plain" -> c.getStringOrNull(2)?.let { texts += it }
                    else -> atts += Attachment(
                        Uri.withAppendedPath(MMS_PART_URI, c.getLong(0).toString()), ct,
                        c.getStringOrNull(3) ?: c.getStringOrNull(5) ?: c.getStringOrNull(4),
                    )
                }
            }
        }
        return texts.joinToString("\n") to atts
    }

    fun loadForSync(ctx: Context, ref: String): SyncMsg? {
        val msg = loadByRef(ctx, ref) ?: return null
        val own = Phone.ownNumbers(ctx)
        val people = if (!msg.isMms) {
            listOfNotNull(
                ctx.contentResolver.query(Sms.CONTENT_URI, arrayOf("address"), "_id = ?", arrayOf(msg.id.toString()), null)
                    ?.use { c -> if (c.moveToFirst()) c.getStringOrNull(0) else null }
            ).map { Phone.normalize(ctx, it) }
        } else {
            val addrs = mmsAddresses(ctx, msg.id)
            participants(
                ctx,
                from = addrs.firstOrNull { it.second == ADDR_FROM }?.first,
                recipients = addrs.filter { it.second == ADDR_TO || it.second == ADDR_CC }.map { it.first },
                outgoing = msg.outgoing,
                own = own,
            )
        }
        return SyncMsg(msg.copy(sender = msg.sender?.let { Phone.normalize(ctx, it) }), people.sorted(), msg.attachments)
    }

    /**
     * The other people in an MMS conversation, normalized and sorted. For incoming messages
     * our own number shows up among the recipients and must be dropped; if we don't know our
     * number, a single recipient is assumed to be us.
     */
    fun participants(
        ctx: Context,
        from: String?,
        recipients: List<String>,
        outgoing: Boolean,
        own: Set<String> = Phone.ownNumbers(ctx),
    ): List<String> {
        val all = if (outgoing) recipients
        else if (own.isEmpty() && recipients.size == 1) listOfNotNull(from)
        else listOfNotNull(from) + recipients
        return all.map { Phone.normalize(ctx, it) }
            .filter { it.isNotEmpty() && !Phone.isOwn(ctx, it, own) }
            .distinct()
            .sorted()
            .ifEmpty { listOfNotNull(from?.let { Phone.normalize(ctx, it) }) }
    }

    fun maxSmsId(ctx: Context): Long = maxId(ctx, Sms.CONTENT_URI)
    fun maxMmsId(ctx: Context): Long = maxId(ctx, Mms.CONTENT_URI)
    private fun maxId(ctx: Context, uri: Uri): Long =
        ctx.contentResolver.query(uri, arrayOf("_id"), null, null, "_id DESC LIMIT 1")?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0
        } ?: 0

    /** Provider ids after [afterId], ascending, skipping drafts and in-flight sends. */
    fun smsIdsAfter(ctx: Context, afterId: Long, limit: Int): List<Long> = idsAfter(
        ctx, Sms.CONTENT_URI, afterId, limit,
        "type NOT IN (${Sms.MESSAGE_TYPE_DRAFT}, ${Sms.MESSAGE_TYPE_OUTBOX}, ${Sms.MESSAGE_TYPE_QUEUED})",
    )
    fun mmsIdsAfter(ctx: Context, afterId: Long, limit: Int): List<Long> = idsAfter(
        ctx, Mms.CONTENT_URI, afterId, limit,
        "m_type IN (128, 132) AND msg_box NOT IN (${Mms.MESSAGE_BOX_DRAFTS}, ${Mms.MESSAGE_BOX_OUTBOX})",
    )
    /** Highest id we've scanned up to, including skipped rows, so the cursor can move past them. */
    fun lastIdUpTo(ctx: Context, uri: Uri, afterId: Long, limit: Int): Long =
        ctx.contentResolver.query(uri, arrayOf("_id"), "_id > ?", arrayOf(afterId.toString()), "_id ASC LIMIT $limit")?.use { c ->
            if (c.moveToLast()) c.getLong(0) else afterId
        } ?: afterId

    private fun idsAfter(ctx: Context, uri: Uri, afterId: Long, limit: Int, extra: String): List<Long> {
        val out = ArrayList<Long>()
        ctx.contentResolver.query(uri, arrayOf("_id"), "_id > ? AND $extra", arrayOf(afterId.toString()), "_id ASC LIMIT $limit")?.use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    fun countAfter(ctx: Context, smsAfter: Long, mmsAfter: Long): Int {
        fun count(uri: Uri, after: Long) =
            ctx.contentResolver.query(uri, arrayOf("_id"), "_id > ?", arrayOf(after.toString()), null)?.use { it.count } ?: 0
        return count(Sms.CONTENT_URI, smsAfter) + count(Mms.CONTENT_URI, mmsAfter)
    }

    fun unread(ctx: Context, threadId: Long): List<Msg> =
        messages(ctx, threadId, 30).filter { !it.read && !it.outgoing }

    fun markThreadRead(ctx: Context, threadId: Long) {
        val v = ContentValues().apply { put("read", 1); put("seen", 1) }
        val args = arrayOf(threadId.toString())
        ctx.contentResolver.update(Sms.CONTENT_URI, v, "thread_id = ? AND (read = 0 OR seen = 0)", args)
        ctx.contentResolver.update(Mms.CONTENT_URI, v, "thread_id = ? AND (read = 0 OR seen = 0)", args)
    }

    fun deleteMessage(ctx: Context, msg: Msg) {
        ctx.contentResolver.delete(msg.uri, null, null)
    }

    fun deleteThread(ctx: Context, threadId: Long) {
        ctx.contentResolver.delete(Uri.withAppendedPath(Telephony.Threads.CONTENT_URI, threadId.toString()), null, null)
    }

    // ---------------------------------------------------------------- writing

    fun insertIncomingSms(ctx: Context, address: String, body: String, sentAt: Long, subId: Int): Uri? {
        val threadId = threadIdFor(ctx, listOf(address))
        val v = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.DATE_SENT, sentAt)
            put(Sms.READ, 0)
            put(Sms.SEEN, 0)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_INBOX)
            put(Sms.THREAD_ID, threadId)
            put(Sms.SUBSCRIPTION_ID, subId)
        }
        return ctx.contentResolver.insert(Sms.Inbox.CONTENT_URI, v)
    }

    fun insertOutgoingSms(ctx: Context, address: String, body: String, threadId: Long, subId: Int): Uri? {
        val v = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, body)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_OUTBOX)
            put(Sms.THREAD_ID, threadId)
            put(Sms.SUBSCRIPTION_ID, subId)
        }
        return ctx.contentResolver.insert(Sms.CONTENT_URI, v)
    }

    fun setSmsType(ctx: Context, uri: Uri, type: Int, errorCode: Int = 0) {
        val v = ContentValues().apply { put(Sms.TYPE, type); if (errorCode != 0) put(Sms.ERROR_CODE, errorCode) }
        ctx.contentResolver.update(uri, v, null, null)
    }

    fun setMmsBox(ctx: Context, uri: Uri, box: Int, messageId: String? = null) {
        val v = ContentValues().apply { put(Mms.MESSAGE_BOX, box); messageId?.let { put(Mms.MESSAGE_ID, it) } }
        ctx.contentResolver.update(uri, v, null, null)
    }

    fun mmsExistsWithTransactionId(ctx: Context, tid: String): Boolean =
        ctx.contentResolver.query(Mms.CONTENT_URI, arrayOf("_id"), "tr_id = ?", arrayOf(tid), null)?.use { it.count > 0 } ?: false

    /**
     * Writes an MMS (incoming retrieve-conf or outgoing send-req) with its parts and addresses.
     * [addresses] are (address, type) pairs using [ADDR_FROM]/[ADDR_TO]/[ADDR_CC].
     */
    fun insertMms(
        ctx: Context,
        threadId: Long,
        box: Int,
        messageType: Int,
        addresses: List<Pair<String, Int>>,
        parts: List<PduPart>,
        subject: String?,
        dateSec: Long,
        transactionId: String?,
        messageId: String?,
        contentType: String,
        subId: Int,
    ): Uri? {
        val incoming = box == Mms.MESSAGE_BOX_INBOX
        val v = ContentValues().apply {
            put(Mms.THREAD_ID, threadId)
            put(Mms.DATE, if (incoming) System.currentTimeMillis() / 1000 else dateSec)
            put(Mms.DATE_SENT, dateSec)
            put(Mms.MESSAGE_BOX, box)
            put(Mms.READ, if (incoming) 0 else 1)
            put(Mms.SEEN, if (incoming) 0 else 1)
            put(Mms.MESSAGE_TYPE, messageType)
            put(Mms.MMS_VERSION, com.textserv.mms.Mms.VERSION_1_2)
            put(Mms.CONTENT_TYPE, contentType)
            put(Mms.MESSAGE_CLASS, "personal")
            put(Mms.PRIORITY, 129)
            put(Mms.TEXT_ONLY, if (parts.all { it.isText || it.isSmil }) 1 else 0)
            put(Mms.SUBSCRIPTION_ID, subId)
            transactionId?.let { put(Mms.TRANSACTION_ID, it) }
            messageId?.let { put(Mms.MESSAGE_ID, it) }
            if (!subject.isNullOrEmpty()) { put(Mms.SUBJECT, subject); put(Mms.SUBJECT_CHARSET, com.textserv.mms.Mms.CHARSET_UTF8) }
        }
        val uri = ctx.contentResolver.insert(Mms.CONTENT_URI, v) ?: return null
        val mmsId = uri.lastPathSegment!!

        parts.forEachIndexed { i, p ->
            val pv = ContentValues().apply {
                put(Mms.Part.MSG_ID, mmsId)
                put(Mms.Part.SEQ, if (p.isSmil) -1 else i)
                put(Mms.Part.CONTENT_TYPE, p.contentType)
                p.name?.let { put(Mms.Part.NAME, it); put(Mms.Part.FILENAME, it) }
                p.contentLocation?.let { put(Mms.Part.CONTENT_LOCATION, it) }
                p.contentId?.let { put(Mms.Part.CONTENT_ID, it) }
                if (p.isText || p.isSmil) {
                    put(Mms.Part.CHARSET, com.textserv.mms.Mms.CHARSET_UTF8)
                    put(Mms.Part.TEXT, p.text())
                }
            }
            val partUri = ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/part"), pv)
            if (partUri != null && !(p.isText || p.isSmil)) {
                ctx.contentResolver.openOutputStream(partUri)?.use { it.write(p.data) }
            }
        }

        for ((addr, type) in addresses) {
            val av = ContentValues().apply {
                put(Mms.Addr.ADDRESS, addr)
                put(Mms.Addr.TYPE, type)
                put(Mms.Addr.CHARSET, com.textserv.mms.Mms.CHARSET_UTF8)
                put(Mms.Addr.MSG_ID, mmsId)
            }
            ctx.contentResolver.insert(Uri.parse("content://mms/$mmsId/addr"), av)
        }
        return uri
    }

    fun readBytes(ctx: Context, uri: Uri): ByteArray? =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }

}
