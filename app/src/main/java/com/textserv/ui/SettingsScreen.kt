package com.textserv.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.textserv.App
import com.textserv.data.Phone
import com.textserv.data.ServerApi
import com.textserv.sync.SyncScheduler
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

private const val PUSH_INSTANCE = "default"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val prefs = App.prefs
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf(prefs.serverUrl) }
    var token by rememberSaveable { mutableStateOf(prefs.token) }
    var myNumber by rememberSaveable { mutableStateOf(prefs.myNumber) }
    var testResult by remember { mutableStateOf<String?>(null) }

    var pushStatus by remember { mutableStateOf(prefs.pushStatus) }
    var distributor by remember { mutableStateOf<String?>(null) }
    var remaining by remember { mutableIntStateOf(prefs.syncRemaining) }
    var syncError by remember { mutableStateOf(prefs.lastSyncError) }
    var detectedNumbers by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        detectedNumbers = Phone.ownNumbers(ctx).joinToString(", ")
        prefs.changes().collect {
            pushStatus = prefs.pushStatus
            remaining = prefs.syncRemaining
            syncError = prefs.lastSyncError
            distributor = UnifiedPush.getAckDistributor(ctx)
        }
    }

    fun save() {
        // A different server or account hasn't seen this phone's history yet.
        val switched = url.trim().trimEnd('/') != prefs.serverUrl
        prefs.serverUrl = url
        prefs.token = token
        if (switched) prefs.resetHistorySync()
        prefs.myNumber = myNumber
        url = prefs.serverUrl
        SyncScheduler.kick(ctx)
        if (prefs.pushEndpoint != null) SyncScheduler.registerPush(ctx)
    }

    fun setUpPush(pick: Boolean) {
        val done: (Boolean) -> Unit = { ok ->
            if (ok) {
                UnifiedPush.register(ctx, PUSH_INSTANCE, "Textserv", null)
                prefs.pushStatus = "Waiting for endpoint from ${UnifiedPush.getSavedDistributor(ctx) ?: "distributor"}…"
            } else {
                prefs.pushStatus = "No UnifiedPush distributor found. Install ntfy (or another distributor) first."
            }
        }
        if (pick) UnifiedPush.tryPickDistributor(activity, done) else UnifiedPush.tryUseCurrentOrDefaultDistributor(activity, done)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Settings") },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Server")
            OutlinedTextField(
                url, { url = it }, Modifier.fillMaxWidth(),
                label = { Text("Server URL") }, placeholder = { Text("http://100.x.y.z:8787") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth(),
                label = { Text("Token (from server config.toml)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    save()
                    testResult = "Testing…"
                    scope.launch {
                        testResult = try {
                            ServerApi(prefs.serverUrl, prefs.token, prefs.deviceId, prefs.deviceName).ping(); "✓ Connected"
                        } catch (e: Exception) {
                            "✗ ${e.message}"
                        }
                    }
                }) { Text("Save & test") }
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            HorizontalDivider()
            Section("Push (for sending from the server)")
            Text("Distributor: ${distributor ?: "none"}", style = MaterialTheme.typography.bodyMedium)
            Text(pushStatus, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { setUpPush(pick = false) }) { Text("Set up push") }
                OutlinedButton(onClick = { setUpPush(pick = true) }) { Text("Choose distributor") }
            }

            HorizontalDivider()
            Section("Sync")
            Text(
                when {
                    !prefs.serverConfigured -> "Connect a server to start syncing."
                    remaining < 0 -> "History import not started yet."
                    remaining == 0 -> "✓ All messages synced."
                    else -> "Importing history… $remaining messages left"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            syncError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { SyncScheduler.kick(ctx) }) { Text("Sync now") }
                OutlinedButton(onClick = {
                    prefs.smsCursor = 0; prefs.mmsCursor = 0; prefs.syncRemaining = -1
                    SyncScheduler.syncHistory(ctx)
                }) { Text("Re-upload everything") }
            }

            HorizontalDivider()
            Section("My number")
            Text(
                "Used to keep you out of group-chat participant lists. Detected from SIM: ${detectedNumbers.ifEmpty { "nothing" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                myNumber, { myNumber = it }, Modifier.fillMaxWidth(),
                label = { Text("Override (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            OutlinedButton(onClick = ::save) { Text("Save") }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
