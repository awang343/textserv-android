package com.textserv.mms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class PduTest {
    private fun bytes(vararg parts: Any): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) when (p) {
            is Int -> out.write(p)
            is String -> { out.write(p.toByteArray(Charsets.UTF_8)); out.write(0) } // text-string
            is ByteArray -> out.write(p)
            else -> error("bad part")
        }
        return out.toByteArray()
    }

    /** Value-length of a From header: address-present token + text + NUL. */
    private fun fromLen(addr: String) = 1 + addr.length + 1

    @Test
    fun parsesNotificationInd() {
        val pdu = bytes(
            0x8C, 0x82,                        // message type: notification-ind
            0x98, "T1234",                     // transaction id
            0x8D, 0x92,                        // version 1.2
            0x89, fromLen("+15551234567/TYPE=PLMN"), 0x80, "+15551234567/TYPE=PLMN", // from
            0x8A, 0x80,                        // class personal
            0x8E, 0x02, 0x0B, 0xB8,            // size 3000
            0x88, 0x05, 0x81, 0x03, 0x03, 0xF4, 0x80, // expiry relative
            0x83, "http://mmsc.example/abc",   // content location
        )
        val p = PduParser(pdu).parse()
        assertEquals(Mms.NOTIFICATION_IND, p.messageType)
        assertEquals("T1234", p.transactionId)
        assertEquals("+15551234567", p.from)
        assertEquals(3000L, p.messageSize)
        assertEquals("http://mmsc.example/abc", p.contentLocation)
    }

    @Test
    fun sendReqRoundTrips() {
        val img = ByteArray(5000) { (it * 7).toByte() }
        val parts = listOf(
            PduPart(Mms.CT_SMIL, "<smil/>".toByteArray(), Mms.CHARSET_UTF8, "smil.xml", "smil.xml", "smil"),
            PduPart("image/jpeg", img, null, "image_0.jpg", "image_0.jpg", "image_0.jpg"),
            PduPart(Mms.CT_TEXT, "héllo 👋".toByteArray(), Mms.CHARSET_UTF8, "text_0.txt", "text_0.txt", "text_0"),
        )
        val raw = PduComposer().sendReq("Tabc", listOf("+15550001111", "+15550002222"), "Sübject", parts)
        val p = PduParser(raw).parse()
        assertEquals(Mms.SEND_REQ, p.messageType)
        assertEquals("Tabc", p.transactionId)
        assertEquals(listOf("+15550001111", "+15550002222"), p.to)
        assertEquals("Sübject", p.subject)
        assertNull(p.from) // insert-address-token
        assertEquals(Mms.CT_MULTIPART_RELATED, p.contentType)
        assertEquals(3, p.parts.size)
        assertEquals(Mms.CT_SMIL, p.parts[0].contentType)
        assertEquals("<smil>", p.parts[0].contentId)
        assertEquals("image/jpeg", p.parts[1].contentType)
        assertEquals("image_0.jpg", p.parts[1].name)
        assertEquals("image_0.jpg", p.parts[1].contentLocation)
        assertArrayEquals(img, p.parts[1].data)
        assertEquals("héllo 👋", p.parts[2].text())
        assertEquals(Mms.CHARSET_UTF8, p.parts[2].charset)
    }

    @Test
    fun parsesRetrieveConfWithCharsetEncodedFieldsAndLargeParts() {
        val big = ByteArray(40_000) { it.toByte() } // needs multi-byte uintvar lengths
        val subject = "Grüße".toByteArray(Charsets.UTF_8)
        // Body: 2 parts, text/plain with charset + image/png with a text Content-ID header.
        val textHeaders = bytes(0x03, 0x83, 0x81, 0xEA, 0x8E, "t.txt")      // ct (len 3): text/plain; charset=utf-8
        val textData = "hi there".toByteArray()
        val imgHeaders = bytes(0xA0, 0xC0, 0x22, "<img1>")                 // short ct image/png, Content-ID quoted
        val body = ByteArrayOutputStream().apply {
            write(0x02)
            write(textHeaders.size); write(textData.size); write(textHeaders); write(textData)
            write(imgHeaders.size)
            // uintvar 40000 = 0x82 0xB8 0x40
            write(0x82); write(0xB8); write(0x40)
            write(imgHeaders); write(big)
        }.toByteArray()
        val pdu = bytes(
            0x8C, 0x84,
            0x98, "TX9",
            0x8D, 0x92,
            0x8B, "msgid-1",
            0x85, 0x04, 0x65, 0x00, 0x00, 0x00,                       // date
            0x89, fromLen("+15559998888/TYPE=PLMN"), 0x80, "+15559998888/TYPE=PLMN",
            0x97, "+15550001111/TYPE=PLMN",
            0x97, "+15550002222/TYPE=PLMN",
            0x82, "+15550003333/TYPE=PLMN",                          // cc
            0x96, subject.size + 2, 0xEA, subject, 0x00,                  // subject, encoded w/ charset
            "X-Custom", "whatever",                                   // application header -> skipped
            0xC8, "unknown-field-value",                               // unassigned well-known field -> skipped
            0x84, 0x1B, 0xB3, 0x8A, "<smil>", 0x89, "application/smil", // multipart.related w/ params
            body,
        )
        val p = PduParser(pdu).parse()
        assertEquals(Mms.RETRIEVE_CONF, p.messageType)
        assertEquals("msgid-1", p.messageId)
        assertEquals("+15559998888", p.from)
        assertEquals(listOf("+15550001111", "+15550002222"), p.to)
        assertEquals(listOf("+15550003333"), p.cc)
        assertEquals("Grüße", p.subject)
        assertEquals(0x65000000L, p.date)
        assertEquals(2, p.parts.size)
        assertEquals("hi there", p.parts[0].text())
        assertEquals("t.txt", p.parts[0].contentLocation)
        assertEquals("image/png", p.parts[1].contentType)
        assertEquals("<img1>", p.parts[1].contentId)
        assertArrayEquals(big, p.parts[1].data)
    }

    @Test
    fun notifyRespIndRoundTrips() {
        val p = PduParser(PduComposer().notifyRespInd("T77")).parse()
        assertEquals(Mms.NOTIFYRESP_IND, p.messageType)
        assertEquals("T77", p.transactionId)
    }

    @Test
    fun parsesSendConf() {
        val p = PduParser(bytes(0x8C, 0x81, 0x98, "T1", 0x8D, 0x92, 0x92, 0x80, 0x8B, "mid-9")).parse()
        assertEquals(Mms.SEND_CONF, p.messageType)
        assertEquals(Mms.RESPONSE_STATUS_OK, p.responseStatus)
        assertEquals("mid-9", p.messageId)
    }
}
