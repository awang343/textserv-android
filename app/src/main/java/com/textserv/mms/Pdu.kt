package com.textserv.mms

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Minimal OMA MMS encapsulation (WAP-209 / MMS 1.2) codec: just enough to parse
 * M-Notification.ind / M-Retrieve.conf / M-Send.conf and to compose M-Send.req /
 * M-NotifyResp.ind. Android's own PDU classes are hidden APIs, hence this.
 */
object Mms {
    const val SEND_REQ = 0x80
    const val SEND_CONF = 0x81
    const val NOTIFICATION_IND = 0x82
    const val NOTIFYRESP_IND = 0x83
    const val RETRIEVE_CONF = 0x84

    // Header field codes (already OR'ed with 0x80).
    const val H_BCC = 0x81
    const val H_CC = 0x82
    const val H_CONTENT_LOCATION = 0x83
    const val H_CONTENT_TYPE = 0x84
    const val H_DATE = 0x85
    const val H_DELIVERY_REPORT = 0x86
    const val H_EXPIRY = 0x88
    const val H_FROM = 0x89
    const val H_MESSAGE_CLASS = 0x8A
    const val H_MESSAGE_ID = 0x8B
    const val H_MESSAGE_TYPE = 0x8C
    const val H_MMS_VERSION = 0x8D
    const val H_MESSAGE_SIZE = 0x8E
    const val H_PRIORITY = 0x8F
    const val H_READ_REPORT = 0x90
    const val H_REPORT_ALLOWED = 0x91
    const val H_RESPONSE_STATUS = 0x92
    const val H_STATUS = 0x95
    const val H_SUBJECT = 0x96
    const val H_TO = 0x97
    const val H_TRANSACTION_ID = 0x98
    const val H_RETRIEVE_STATUS = 0x99

    const val VERSION_1_2 = 0x12
    const val RESPONSE_STATUS_OK = 0x80
    const val STATUS_RETRIEVED = 0x81
    const val CHARSET_UTF8 = 106

    const val CT_MULTIPART_RELATED = "application/vnd.wap.multipart.related"
    const val CT_MULTIPART_MIXED = "application/vnd.wap.multipart.mixed"
    const val CT_SMIL = "application/smil"
    const val CT_TEXT = "text/plain"

    /** WSP well-known content types (WAP-230 Appendix A), indexed by assigned number. */
    val WELL_KNOWN_TYPES = listOf(
        "*/*", "text/*", "text/html", "text/plain", "text/x-hdml", "text/x-ttml",
        "text/x-vCalendar", "text/x-vCard", "text/vnd.wap.wml", "text/vnd.wap.wmlscript",
        "text/vnd.wap.wta-event", "multipart/*", "multipart/mixed", "multipart/form-data",
        "multipart/byterantes", "multipart/alternative", "application/*",
        "application/java-vm", "application/x-www-form-urlencoded", "application/x-hdmlc",
        "application/vnd.wap.wmlc", "application/vnd.wap.wmlscriptc",
        "application/vnd.wap.wta-eventc", "application/vnd.wap.uaprof",
        "application/vnd.wap.wtls-ca-certificate", "application/vnd.wap.wtls-user-certificate",
        "application/x-x509-ca-cert", "application/x-x509-user-cert", "image/*", "image/gif",
        "image/jpeg", "image/tiff", "image/png", "image/vnd.wap.wbmp",
        "application/vnd.wap.multipart.*", "application/vnd.wap.multipart.mixed",
        "application/vnd.wap.multipart.form-data", "application/vnd.wap.multipart.byteranges",
        "application/vnd.wap.multipart.alternative", "application/xml", "text/xml",
        "application/vnd.wap.wbxml", "application/x-x968-cross-cert",
        "application/x-x968-ca-cert", "application/x-x968-user-cert", "text/vnd.wap.si",
        "application/vnd.wap.sic", "text/vnd.wap.sl", "application/vnd.wap.slc",
        "text/vnd.wap.co", "application/vnd.wap.coc", "application/vnd.wap.multipart.related",
        "application/vnd.wap.sia", "text/vnd.wap.connectivity-xml",
        "application/vnd.wap.connectivity-wbxml", "application/pkcs7-mime",
        "application/vnd.wap.hashed-certificate", "application/vnd.wap.signed-certificate",
        "application/vnd.wap.cert-response", "application/xhtml+xml", "application/wml+xml",
        "text/css", "application/vnd.wap.mms-message",
        "application/vnd.wap.rollover-certificate", "application/vnd.wap.locc+wbxml",
        "application/vnd.wap.loc+xml", "application/vnd.syncml.dm+wbxml",
        "application/vnd.syncml.dm+xml", "application/vnd.syncml.notification",
        "application/vnd.wap.xhtml+xml", "application/vnd.wv.csp.cir",
        "application/vnd.oma.dd+xml", "application/vnd.oma.drm.message",
        "application/vnd.oma.drm.content", "application/vnd.oma.drm.rights+xml",
        "application/vnd.oma.drm.rights+wbxml",
    )

    fun charsetFor(mib: Int?): Charset = runCatching {
        when (mib) {
            3 -> Charsets.US_ASCII
            4 -> Charsets.ISO_8859_1
            1000 -> Charsets.UTF_16BE // ISO-10646-UCS-2
            1013 -> Charsets.UTF_16BE
            1014 -> Charsets.UTF_16LE
            1015 -> Charsets.UTF_16
            17 -> Charset.forName("Shift_JIS")
            2026 -> Charset.forName("Big5")
            2025 -> Charset.forName("GB2312")
            113 -> Charset.forName("GBK")
            else -> Charsets.UTF_8
        }
    }.getOrDefault(Charsets.UTF_8)

    /** "+15551234567/TYPE=PLMN" -> "+15551234567" */
    fun stripAddressType(addr: String): String = addr.substringBefore("/TYPE=").trim()
}

class PduPart(
    val contentType: String,
    val data: ByteArray,
    val charset: Int? = null,
    val name: String? = null,
    val contentLocation: String? = null,
    val contentId: String? = null,
) {
    val isText get() = contentType.equals(Mms.CT_TEXT, ignoreCase = true)
    val isSmil get() = contentType.equals(Mms.CT_SMIL, ignoreCase = true)
    fun text(): String = String(data, Mms.charsetFor(charset))
}

class ParsedPdu(
    val messageType: Int,
    val transactionId: String?,
    val messageId: String?,
    val contentLocation: String?,
    val from: String?,
    val to: List<String>,
    val cc: List<String>,
    val subject: String?,
    val date: Long?,
    val expiry: Long?,
    val messageSize: Long?,
    val responseStatus: Int?,
    val retrieveStatus: Int?,
    val contentType: String?,
    val parts: List<PduPart>,
)

class PduException(msg: String) : Exception(msg)

class PduParser(private val b: ByteArray) {
    private var pos = 0

    private fun eof() = pos >= b.size
    private fun peek(): Int = if (pos < b.size) b[pos].toInt() and 0xFF else throw PduException("eof")
    private fun readByte(): Int = peek().also { pos++ }

    private fun readUintvar(): Long {
        var v = 0L
        for (i in 0 until 5) {
            val x = readByte()
            v = (v shl 7) or (x and 0x7F).toLong()
            if (x and 0x80 == 0) return v
        }
        throw PduException("bad uintvar")
    }

    private fun readValueLength(): Int {
        val x = readByte()
        return when {
            x <= 30 -> x
            x == 31 -> readUintvar().toInt()
            else -> throw PduException("bad value-length $x")
        }
    }

    private fun readLongInt(): Long {
        val len = readByte()
        if (len > 30) throw PduException("bad long-int")
        var v = 0L
        repeat(len) { v = (v shl 8) or readByte().toLong() }
        return v
    }

    private fun readIntegerValue(): Long =
        if (peek() >= 0x80) (readByte() and 0x7F).toLong() else readLongInt()

    private fun readRawText(): ByteArray {
        if (!eof() && (peek() == 0x7F || peek() == 0x22)) pos++ // quote / quoted-string marker
        val start = pos
        while (pos < b.size && b[pos].toInt() != 0) pos++
        val out = b.copyOfRange(start, pos)
        if (pos < b.size) pos++ // NUL
        return out
    }

    private fun readText(): String = String(readRawText(), Charsets.UTF_8)

    private fun readEncodedString(): String {
        if (peek() > 31) return readText()
        val len = readValueLength()
        val end = pos + len
        val charset = if (peek() == 0x80) { pos++; null } else readIntegerValue().toInt()
        val start = pos
        while (pos < end && b[pos].toInt() != 0) pos++
        val s = String(b, start, pos - start, Mms.charsetFor(charset))
        pos = end
        return s
    }

    /** Skips any single WSP value whose type we don't care about. */
    private fun skipValue() {
        val x = peek()
        when {
            x >= 0x80 -> pos++
            x <= 30 -> pos += 1 + x
            x == 31 -> { pos++; val len = readUintvar().toInt(); pos += len }
            else -> readRawText()
        }
    }

    private fun wellKnownType(n: Int) = Mms.WELL_KNOWN_TYPES.getOrElse(n) { "application/octet-stream" }

    data class ContentType(val mediaType: String, val params: Map<String, String>)

    private fun readContentType(): ContentType {
        val x = peek()
        if (x >= 0x80) return ContentType(wellKnownType(readByte() and 0x7F), emptyMap())
        if (x > 31) return ContentType(readText(), emptyMap())
        val len = readValueLength()
        val end = pos + len
        val media = when {
            peek() >= 0x80 -> wellKnownType(readByte() and 0x7F)
            peek() <= 30 -> wellKnownType(readLongInt().toInt())
            else -> readText()
        }
        val params = HashMap<String, String>()
        while (pos < end) {
            val t = peek()
            if (t >= 0x80 || t <= 30) {
                val token = readIntegerValue().toInt()
                when (token) {
                    0x01 -> if (peek() == 0x80) pos++ else params["charset"] = readIntegerValue().toString()
                    0x09, 0x03 -> params["type"] =
                        if (peek() >= 0x80 || peek() <= 30) wellKnownType(readIntegerValue().toInt()) else readText()
                    0x0A, 0x19 -> params["start"] = readText()
                    0x05, 0x17 -> params["name"] = readText()
                    0x06, 0x18 -> params["filename"] = readText()
                    else -> skipValue()
                }
            } else {
                val name = readText().lowercase()
                val v = peek()
                params[name] = if (v >= 0x80 || v <= 30) readIntegerValue().toString() else readText()
            }
        }
        pos = end
        return ContentType(media, params)
    }

    fun parse(): ParsedPdu {
        var type = -1
        var tid: String? = null
        var mid: String? = null
        var location: String? = null
        var from: String? = null
        val to = ArrayList<String>()
        val cc = ArrayList<String>()
        var subject: String? = null
        var date: Long? = null
        var expiry: Long? = null
        var size: Long? = null
        var respStatus: Int? = null
        var retrStatus: Int? = null
        var contentType: ContentType? = null

        while (!eof() && contentType == null) {
            val field = readByte()
            if (field < 0x80) {
                // Application header: Token-text name (first byte already consumed) + text value.
                pos--
                readRawText(); if (!eof()) readRawText()
                continue
            }
            when (field) {
                Mms.H_MESSAGE_TYPE -> type = readByte()
                Mms.H_TRANSACTION_ID -> tid = readText()
                Mms.H_MESSAGE_ID -> mid = readText()
                Mms.H_CONTENT_LOCATION -> location = readText()
                Mms.H_FROM -> {
                    val len = readValueLength()
                    val end = pos + len
                    if (readByte() == 0x80) from = Mms.stripAddressType(readEncodedString())
                    pos = end
                }
                Mms.H_TO -> to += Mms.stripAddressType(readEncodedString())
                Mms.H_CC -> cc += Mms.stripAddressType(readEncodedString())
                Mms.H_BCC -> readEncodedString()
                Mms.H_SUBJECT -> subject = readEncodedString()
                Mms.H_DATE -> date = readLongInt()
                Mms.H_MESSAGE_SIZE -> size = readLongInt()
                Mms.H_EXPIRY -> {
                    val len = readValueLength()
                    val end = pos + len
                    val absolute = readByte() == 0x80
                    val v = readLongInt()
                    expiry = if (absolute) v else (System.currentTimeMillis() / 1000) + v
                    pos = end
                }
                Mms.H_RESPONSE_STATUS -> respStatus = readByte()
                Mms.H_RETRIEVE_STATUS -> retrStatus = readByte()
                Mms.H_CONTENT_TYPE -> contentType = readContentType()
                else -> skipValue()
            }
        }

        val parts = if (contentType == null) emptyList() else readBody(contentType)
        return ParsedPdu(
            messageType = type, transactionId = tid, messageId = mid, contentLocation = location,
            from = from, to = to, cc = cc, subject = subject, date = date, expiry = expiry,
            messageSize = size, responseStatus = respStatus, retrieveStatus = retrStatus,
            contentType = contentType?.mediaType, parts = parts,
        )
    }

    private fun readBody(ct: ContentType): List<PduPart> {
        if (!ct.mediaType.startsWith("application/vnd.wap.multipart") && !ct.mediaType.startsWith("multipart/")) {
            // Single-part body.
            return listOf(PduPart(ct.mediaType, b.copyOfRange(pos, b.size), ct.params["charset"]?.toIntOrNull()))
        }
        val parts = ArrayList<PduPart>()
        val count = readUintvar().toInt()
        repeat(count) {
            val headersLen = readUintvar().toInt()
            val dataLen = readUintvar().toInt()
            val headersEnd = pos + headersLen
            val pct = readContentType()
            var location: String? = null
            var cid: String? = null
            var dispositionName: String? = null
            while (pos < headersEnd) {
                val x = peek()
                if (x >= 0x80) {
                    when (readByte() and 0x7F) {
                        0x0E -> location = readText()
                        0x40 -> cid = readText()
                        0x2E, 0x45 -> {
                            // Content-Disposition: Value-length Disposition *(Parameter)
                            if (peek() <= 31) {
                                val len = readValueLength()
                                val end = pos + len
                                pos++ // disposition token
                                while (pos < end) {
                                    val p = peek()
                                    if (p >= 0x80) {
                                        val tok = readByte() and 0x7F
                                        if (tok == 0x06 || tok == 0x18 || tok == 0x05 || tok == 0x17) dispositionName = readText()
                                        else skipValue()
                                    } else { readText(); if (pos < end) skipValue() }
                                }
                                pos = end
                            } else skipValue()
                        }
                        else -> skipValue()
                    }
                } else {
                    val name = readText().lowercase()
                    val value = if (pos < headersEnd) readText() else ""
                    when (name) {
                        "content-location" -> location = value
                        "content-id" -> cid = value
                    }
                }
            }
            pos = headersEnd
            val data = b.copyOfRange(pos, minOf(pos + dataLen, b.size))
            pos += dataLen
            parts += PduPart(
                contentType = pct.mediaType,
                data = data,
                charset = pct.params["charset"]?.toIntOrNull(),
                name = pct.params["name"] ?: pct.params["filename"] ?: dispositionName,
                contentLocation = location,
                contentId = cid,
            )
        }
        return parts
    }
}

class PduComposer {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun byte(v: Int) = out.write(v and 0xFF)
    private fun bytes(v: ByteArray) = out.write(v)

    private fun ByteArrayOutputStream.uintvar(v: Long) {
        val tmp = ArrayList<Int>()
        var x = v
        tmp += (x and 0x7F).toInt()
        x = x shr 7
        while (x > 0) { tmp += ((x and 0x7F) or 0x80).toInt(); x = x shr 7 }
        for (i in tmp.indices.reversed()) write(tmp[i])
    }

    private fun ByteArrayOutputStream.valueLength(len: Int) {
        if (len < 31) write(len) else { write(31); uintvar(len.toLong()) }
    }

    private fun ByteArrayOutputStream.text(s: String) {
        val raw = s.toByteArray(Charsets.UTF_8)
        if (raw.isNotEmpty() && (raw[0].toInt() and 0xFF) >= 0x80) write(0x7F)
        write(raw); write(0)
    }

    private fun ByteArrayOutputStream.longInt(v: Long) {
        val tmp = ArrayList<Int>()
        var x = v
        do { tmp += (x and 0xFF).toInt(); x = x shr 8 } while (x > 0)
        write(tmp.size)
        for (i in tmp.indices.reversed()) write(tmp[i])
    }

    private fun ByteArrayOutputStream.encodedString(s: String) {
        if (s.all { it.code < 0x80 }) { text(s); return }
        val raw = s.toByteArray(Charsets.UTF_8)
        valueLength(1 + raw.size + 1)
        write(0x80 or Mms.CHARSET_UTF8)
        write(raw); write(0)
    }

    private fun ByteArrayOutputStream.media(type: String) {
        val idx = Mms.WELL_KNOWN_TYPES.indexOfFirst { it.equals(type, ignoreCase = true) }
        if (idx in 0..0x7F) write(0x80 or idx) else text(type)
    }

    private fun ByteArrayOutputStream.contentType(type: String, params: ByteArrayOutputStream.() -> Unit) {
        val inner = ByteArrayOutputStream()
        inner.media(type)
        inner.params()
        valueLength(inner.size())
        write(inner.toByteArray())
    }

    private fun header(field: Int, v: ByteArrayOutputStream.() -> Unit) {
        byte(field)
        out.v()
    }

    private fun address(a: String): String =
        if (a.contains('@')) a else a.filter { it.isDigit() || it == '+' } + "/TYPE=PLMN"

    /** M-Send.req carrying [parts]; the first part should be the SMIL presentation. */
    fun sendReq(transactionId: String, recipients: List<String>, subject: String?, parts: List<PduPart>): ByteArray {
        header(Mms.H_MESSAGE_TYPE) { write(Mms.SEND_REQ) }
        header(Mms.H_TRANSACTION_ID) { text(transactionId) }
        header(Mms.H_MMS_VERSION) { write(0x80 or Mms.VERSION_1_2) }
        header(Mms.H_DATE) { longInt(System.currentTimeMillis() / 1000) }
        header(Mms.H_FROM) { write(1); write(0x81) } // insert-address-token: MMSC fills in our number
        for (r in recipients) header(Mms.H_TO) { encodedString(address(r)) }
        if (!subject.isNullOrEmpty()) header(Mms.H_SUBJECT) { encodedString(subject) }
        header(Mms.H_MESSAGE_CLASS) { write(0x80) } // personal
        header(Mms.H_EXPIRY) { write(5); write(0x81); longInt(7L * 24 * 3600) } // relative, 1 week
        header(Mms.H_PRIORITY) { write(0x81) } // normal
        header(Mms.H_DELIVERY_REPORT) { write(0x81) } // no
        header(Mms.H_READ_REPORT) { write(0x81) } // no
        val smil = parts.firstOrNull { it.isSmil }
        header(Mms.H_CONTENT_TYPE) {
            if (smil != null) {
                contentType(Mms.CT_MULTIPART_RELATED) {
                    write(0x8A); text("<${smil.contentId?.trim('<', '>') ?: "smil"}>") // start
                    write(0x89); text(Mms.CT_SMIL) // type
                }
            } else {
                media(Mms.CT_MULTIPART_MIXED)
            }
        }
        out.uintvar(parts.size.toLong())
        for (p in parts) {
            val h = ByteArrayOutputStream()
            h.contentType(p.contentType) {
                if (p.charset != null) { write(0x81); write(0x80 or p.charset) }
                if (p.name != null) { write(0x85); text(p.name) }
            }
            if (p.contentLocation != null) { h.write(0x8E); h.text(p.contentLocation) }
            if (p.contentId != null) {
                h.write(0xC0); h.write(0x22); h.write("<${p.contentId.trim('<', '>')}>".toByteArray()); h.write(0)
            }
            out.uintvar(h.size().toLong())
            out.uintvar(p.data.size.toLong())
            bytes(h.toByteArray())
            bytes(p.data)
        }
        return toByteArray()
    }

    /** M-NotifyResp.ind acknowledging that we retrieved the message. */
    fun notifyRespInd(transactionId: String): ByteArray {
        header(Mms.H_MESSAGE_TYPE) { write(Mms.NOTIFYRESP_IND) }
        header(Mms.H_TRANSACTION_ID) { text(transactionId) }
        header(Mms.H_MMS_VERSION) { write(0x80 or Mms.VERSION_1_2) }
        header(Mms.H_STATUS) { write(Mms.STATUS_RETRIEVED) }
        header(Mms.H_REPORT_ALLOWED) { write(0x81) } // no
        return toByteArray()
    }
}
