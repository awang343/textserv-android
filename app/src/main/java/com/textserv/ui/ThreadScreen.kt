package com.textserv.ui

import android.content.ClipData
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.textserv.data.Attachment
import com.textserv.data.Contacts
import com.textserv.data.Msg
import com.textserv.data.MsgStatus
import com.textserv.data.TelephonyStore
import com.textserv.notify.Notifier
import com.textserv.send.Sender
import com.textserv.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the conversation list already knows, so the header can render before the thread loads. */
data class ThreadPreview(val title: String, val addresses: List<String>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(threadId: Long, initialDraft: String?, preview: ThreadPreview?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<Msg>>(emptyList()) }
    var addresses by remember { mutableStateOf(preview?.addresses ?: emptyList()) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var draft by rememberSaveable(threadId) { mutableStateOf(initialDraft ?: "") }
    val pending = remember { mutableStateListOf<Uri>() }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val group = addresses.size > 1

    // While this thread is on screen: suppress its notifications and keep it marked read.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(threadId, lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { Notifier.activeThreadId = threadId; Notifier.cancelThread(ctx, threadId) }
                Lifecycle.Event.ON_PAUSE -> Notifier.activeThreadId = -1
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); Notifier.activeThreadId = -1 }
    }

    LaunchedEffect(threadId) {
        TelephonyStore.changes(ctx).collect {
            val (msgs, addrs, nm) = withContext(Dispatchers.IO) {
                val addrs = TelephonyStore.threadAddresses(ctx, threadId)
                val msgs = TelephonyStore.messages(ctx, threadId)
                val nm = (addrs + msgs.mapNotNull { it.sender }).distinct().associateWith { Contacts.name(ctx, it) }
                if (msgs.any { !it.read }) SyncScheduler.syncReads(ctx, TelephonyStore.markThreadRead(ctx, threadId))
                Triple(msgs, addrs, nm)
            }
            val grew = msgs.size != messages.size
            messages = msgs; addresses = addrs; names = nm
            if (grew && msgs.isNotEmpty()) listState.animateScrollToItem(0)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        pending.addAll(uris)
    }

    fun send() {
        val text = draft.trim()
        val atts = pending.toList()
        if (text.isEmpty() && atts.isEmpty()) return
        sending = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val outgoing = atts.map { Sender.attachmentFromUri(ctx, it) }
                    Sender.send(ctx, addresses, text, outgoing)
                }
                draft = ""
                pending.clear()
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "Send failed", Toast.LENGTH_LONG).show()
            } finally {
                sending = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    val title = if (names.isEmpty()) preview?.title.orEmpty() else addresses.joinToString(", ") { names[it] ?: it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (addresses.isNotEmpty()) {
                            Avatar(
                                title, addresses.joinToString(","), size = 36.dp, group = group,
                                modifier = Modifier.navSharedElement("avatar-$threadId"),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = if (group) "${addresses.size} people" else addresses.firstOrNull()?.takeIf { it != title }
                            if (sub != null) Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                draft = draft, onDraft = { draft = it },
                pending = pending, onRemove = { pending.remove(it) },
                onAttach = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onSend = ::send, sending = sending,
                isMms = group || pending.isNotEmpty(),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val reversed = messages.asReversed()
            items(reversed.size, key = { reversed[it].ref }) { i ->
                val m = reversed[i]
                val older = reversed.getOrNull(i + 1)
                val showSender = group && !m.outgoing && (older == null || older.outgoing || older.sender != m.sender)
                val showTime = older == null || m.date - older.date > 30 * 60 * 1000
                Bubble(m, if (showSender) names[m.sender] ?: m.sender else null, showTime, Modifier.animateItem(), onRetry = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val atts = m.attachments.mapNotNull { a ->
                                    TelephonyStore.readBytes(ctx, a.uri)?.let { com.textserv.send.OutgoingAttachment(it, a.contentType, a.name ?: "attachment") }
                                }
                                Sender.send(ctx, addresses, m.body, atts)
                                TelephonyStore.deleteMessage(ctx, m)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(ctx, e.message ?: "Send failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }, onDelete = { scope.launch(Dispatchers.IO) { TelephonyStore.deleteMessage(ctx, m) } })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: Msg, senderName: String?, showTime: Boolean, modifier: Modifier, onRetry: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var menu by remember { mutableStateOf(false) }
    val mine = m.outgoing
    val bg = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Column(modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (showTime) {
            Text(
                formatTime(ctx, m.date), Modifier.fillMaxWidth().padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        if (senderName != null) {
            Text(senderName, Modifier.padding(start = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            Column(
                Modifier.widthIn(max = 300.dp).combinedClickable(
                    onClick = { if (m.status == MsgStatus.FAILED) onRetry() },
                    onLongClick = { menu = true },
                ),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (a in m.attachments) AttachmentView(a)
                if (m.body.isNotEmpty()) {
                    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(18.dp)) {
                        Text(m.body, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (m.body.isNotEmpty()) DropdownMenuItem(text = { Text("Copy text") }, onClick = {
                    menu = false
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", m.body))) }
                })
                if (m.status == MsgStatus.FAILED) DropdownMenuItem(text = { Text("Retry") }, onClick = { menu = false; onRetry() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
        when (m.status) {
            MsgStatus.SENDING -> StatusLine("Sending…", false)
            MsgStatus.FAILED -> StatusLine("Not sent · tap to retry", true)
            else -> {}
        }
    }
}

@Composable
private fun StatusLine(text: String, error: Boolean) {
    Text(
        text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AttachmentView(a: Attachment) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val open: () -> Unit = { scope.launch { openAttachment(ctx, a) } }
    when {
        a.contentType.startsWith("image/") -> UriImage(
            a.uri, Modifier.widthIn(max = 260.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = open),
        )
        a.contentType.startsWith("video/") -> Box(
            Modifier.size(200.dp, 140.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = open),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.PlayCircle, "Play video", Modifier.size(48.dp)) }
        else -> AssistChip(
            onClick = open,
            label = { Text(a.name ?: a.contentType, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
        )
    }
}

@Composable
private fun Composer(
    draft: String, onDraft: (String) -> Unit,
    pending: List<Uri>, onRemove: (Uri) -> Unit,
    onAttach: () -> Unit, onSend: () -> Unit, sending: Boolean, isMms: Boolean,
) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(8.dp).animateContentSize()) {
            if (pending.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    items(pending) { uri ->
                        Box {
                            UriImage(uri, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)), maxDim = 300, contentScale = ContentScale.Crop)
                            IconButton(onClick = { onRemove(uri) }, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(12.dp))) {
                                Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttach) { Icon(Icons.Filled.AddPhotoAlternate, "Attach") }
                OutlinedTextField(
                    value = draft, onValueChange = onDraft,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp),
                    placeholder = { Text(if (isMms) "MMS message" else "Text message") },
                    shape = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.size(8.dp))
                FilledIconButton(onClick = onSend, enabled = !sending && (draft.isNotBlank() || pending.isNotEmpty())) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
