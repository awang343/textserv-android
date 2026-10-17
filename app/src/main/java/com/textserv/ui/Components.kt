package com.textserv.ui

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.text.format.DateUtils
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.textserv.data.Attachment
import com.textserv.data.TelephonyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val avatarColors = listOf(
    Color(0xFF5B8DEF), Color(0xFFE5735B), Color(0xFF4CAF7D), Color(0xFFB26CD9),
    Color(0xFFE0A030), Color(0xFF3FA7B5), Color(0xFFD9577E), Color(0xFF7C8A99),
)

val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Morphs this element into the one with the same [key] on the screen being navigated to. */
@Composable
fun Modifier.navSharedElement(key: Any): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) { sharedElement(rememberSharedContentState(key), anim) }
}

@Composable
fun Avatar(name: String, key: String, size: Dp = 44.dp, group: Boolean = false, modifier: Modifier = Modifier) {
    val color = avatarColors[abs(key.hashCode()) % avatarColors.size]
    val initial = if (group) "👥" else name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
    Box(
        modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

fun formatTime(ctx: Context, ms: Long): String {
    val now = System.currentTimeMillis()
    return when {
        DateUtils.isToday(ms) -> DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_TIME)
        now - ms < 6 * DateUtils.DAY_IN_MILLIS -> DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        else -> DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
}

private val imageCache = LruCache<Uri, ImageBitmap>(48)

/** Downsampled image from any content Uri (MMS part, photo picker). */
@Composable
fun UriImage(uri: Uri, modifier: Modifier = Modifier, maxDim: Int = 900, contentScale: ContentScale = ContentScale.Fit) {
    val ctx = LocalContext.current
    val bmp by produceState(imageCache.get(uri), uri) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                        val longest = max(info.size.width, info.size.height)
                        if (longest > maxDim) {
                            val s = maxDim.toDouble() / longest
                            dec.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
                        }
                    }.asImageBitmap().also { imageCache.put(uri, it) }
                }.getOrNull()
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.size(160.dp).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** Opens an MMS part in another app, via a FileProvider copy (we can't grant provider Uris). */
suspend fun openAttachment(ctx: Context, a: Attachment) {
    val file = withContext(Dispatchers.IO) {
        val bytes = TelephonyStore.readBytes(ctx, a.uri) ?: return@withContext null
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(a.contentType) ?: "bin"
        val name = (a.name?.substringBeforeLast('.') ?: "attachment").replace(Regex("[^A-Za-z0-9_-]"), "_")
        File(File(ctx.cacheDir, "view").apply { mkdirs() }, "$name.$ext").apply { writeBytes(bytes) }
    } ?: return
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.contentType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
