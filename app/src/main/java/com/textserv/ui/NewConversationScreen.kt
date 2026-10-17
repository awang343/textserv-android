package com.textserv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.textserv.data.ContactHit
import com.textserv.data.Contacts
import com.textserv.data.TelephonyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Recipient(val number: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewConversationScreen(onBack: () -> Unit, onStart: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<Recipient>() }
    var hits by remember { mutableStateOf<List<ContactHit>>(emptyList()) }

    LaunchedEffect(query) {
        hits = withContext(Dispatchers.IO) { Contacts.search(ctx, query) }
    }

    fun addRaw() {
        val q = query.trim()
        if (q.any { it.isDigit() } || q.contains('@')) {
            chosen += Recipient(q, q)
            query = ""
        }
    }

    fun start() {
        addRaw()
        if (chosen.isEmpty()) return
        scope.launch {
            val id = withContext(Dispatchers.IO) { TelephonyStore.threadIdFor(ctx, chosen.map { it.number }) }
            onStart(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("New conversation") },
                actions = {
                    TextButton(onClick = ::start, enabled = chosen.isNotEmpty() || query.isNotBlank()) { Text("Next") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (chosen.isNotEmpty()) {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chosen.forEach { r ->
                        InputChip(
                            selected = false, onClick = { chosen.remove(r) },
                            label = { Text(r.label) },
                            trailingIcon = { Icon(Icons.Filled.Close, "Remove") },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                label = { Text("To: name or number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { addRaw() }),
            )
            LazyColumn {
                items(hits) { h ->
                    ListItem(
                        headlineContent = { Text(h.name) },
                        supportingContent = { Text("${h.label} · ${h.number}") },
                        leadingContent = { Avatar(h.name, h.number, size = 40.dp) },
                        modifier = Modifier.clickable {
                            chosen += Recipient(h.number, h.name)
                            query = ""
                        },
                    )
                }
            }
        }
    }
}
