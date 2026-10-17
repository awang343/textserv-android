package com.textserv.send

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Shrinks attachments so the whole MMS fits under the carrier's size limit. */
object MmsMedia {
    private val PASSTHROUGH_IMAGES = setOf("image/jpeg", "image/png", "image/gif")

    fun fit(ctx: Context, attachments: List<OutgoingAttachment>, budget: Int): List<OutgoingAttachment> {
        if (attachments.isEmpty()) return attachments
        val images = attachments.count { it.contentType.startsWith("image/") }
        val fixed = attachments.filterNot { it.contentType.startsWith("image/") }.sumOf { it.bytes.size }
        if (fixed > budget) throw SendException("Attachment too large for MMS (limit ${budget / 1024} KB)")
        val perImage = if (images == 0) 0 else (budget - fixed) / images
        return attachments.map { a ->
            if (!a.contentType.startsWith("image/")) a
            else if (a.contentType.lowercase() in PASSTHROUGH_IMAGES && a.bytes.size <= perImage) a
            else compressImage(a, perImage)
        }
    }

    private fun compressImage(a: OutgoingAttachment, maxBytes: Int): OutgoingAttachment {
        var maxDim = 1600
        while (maxDim >= 200) {
            val bmp = decode(a.bytes, maxDim)
            for (q in intArrayOf(85, 70, 55, 40)) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= maxBytes) {
                    bmp.recycle()
                    return OutgoingAttachment(out.toByteArray(), "image/jpeg", a.name.substringBeforeLast('.') + ".jpg")
                }
            }
            bmp.recycle()
            maxDim = (maxDim * 0.7).roundToInt()
        }
        throw SendException("Couldn't shrink ${a.name} enough for MMS")
    }

    private fun decode(bytes: ByteArray, maxDim: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { dec, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > maxDim) {
                val scale = maxDim.toDouble() / longest
                dec.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
            }
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
}
