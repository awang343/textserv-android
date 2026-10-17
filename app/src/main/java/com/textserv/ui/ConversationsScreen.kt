package com.textserv.ui

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.textserv.App
import com.textserv.data.Contacts
import com.textserv.data.Conversation
import com.textserv.data.TelephonyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ConvRow(val c: Conversation, val title: String)

/** Lives at activity scope so the list is already populated when navigating back to it. */
internal class ConversationsViewModel(app: Application) : AndroidViewModel(app) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<ConvRow>?> = TelephonyStore.changes(app)
        .mapLatest {
            withContext(Dispatchers.IO) {
                TelephonyStore.conversations(app).map { ConvRow(it, Contacts.title(app, it.addresses)) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationsScreen(onOpen: (Long, ThreadPreview) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: ConversationsViewModel = viewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<ConvRow?>(null) }
    var serverConfigured by remember { mutableStateOf(App.prefs.serverConfigured) }
    LaunchedEffect(Unit) { App.prefs.changes().collect { serverConfigured = App.prefs.serverConfigured } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.AutoMirrored.Filled.Chat, null) },
                text = { Text("Start chat") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!serverConfigured) {
                TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text("Server not set up — tap to connect")
                }
            }
            val list = rows
            when {
                list == null -> {}
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No conversations yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.c.threadId }) { row ->
                        ConversationRow(
                            row,
                            Modifier.animateItem().combinedClickable(
                                onClick = { onOpen(row.c.threadId, ThreadPreview(row.title, row.c.addresses)) },
                                onLongClick = { confirmDelete = row },
                            ),
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("Deletes the conversation with ${row.title} from this phone. The server copy is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch(Dispatchers.IO) { TelephonyStore.deleteThread(ctx, row.c.threadId) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConversationRow(row: ConvRow, modifier: Modifier) {
    val ctx = LocalContext.current
    val unread = !row.c.read
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            row.title, row.c.addresses.joinToString(","), group = row.c.addresses.size > 1,
            modifier = Modifier.navSharedElement("avatar-${row.c.threadId}"),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    row.title, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTime(ctx, row.c.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                row.c.snippet.ifEmpty { "Attachment" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
