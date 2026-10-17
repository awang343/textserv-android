package com.textserv.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.textserv.data.TelephonyStore
import com.textserv.send.Sender
import com.textserv.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Conversations : Screen
    data class Thread(val threadId: Long, val draft: String? = null, val preview: ThreadPreview? = null) : Screen
    data object NewConversation : Screen
    data object Settings : Screen
}

class NavViewModel : ViewModel() {
    val stack = mutableStateListOf<Screen>(Screen.Conversations)
    val current get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    /** Replace everything above the conversation list with [s]. */
    fun openFromRoot(s: Screen) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        stack.add(s)
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_THREAD_ID = "thread_id"
    }

    private val nav: NavViewModel by viewModels()
    private var isDefaultApp by mutableStateOf(false)
    private var askedPermissions = false

    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshRole() }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshRole()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            TextservTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (!isDefaultApp) DefaultAppPrompt(::requestRole) else AppNav(nav)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRole()
        if (isDefaultApp) {
            if (!askedPermissions) {
                askedPermissions = true
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.POST_NOTIFICATIONS,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.READ_PHONE_NUMBERS,
                        Manifest.permission.ACCESS_LOCAL_NETWORK,
                    )
                )
            }
            SyncScheduler.kick(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun refreshRole() {
        isDefaultApp = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS) ||
            Telephony.Sms.getDefaultSmsPackage(this) == packageName
    }

    private fun requestRole() {
        roleLauncher.launch(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS))
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1)
        if (threadId >= 0) {
            nav.openFromRoot(Screen.Thread(threadId))
            return
        }
        val data = intent.data ?: return
        if (data.scheme !in setOf("sms", "smsto", "mms", "mmsto")) return
        val recipients = Sender.recipientsFromUri(data)
        val body = intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: Sender.bodyFromUri(data)
        if (recipients.isEmpty()) {
            nav.openFromRoot(Screen.NewConversation)
            return
        }
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) { TelephonyStore.threadIdFor(this@MainActivity, recipients) }
            nav.openFromRoot(Screen.Thread(id, body))
        }
    }
}

@Composable
private fun AppNav(nav: NavViewModel) {
    val stack = nav.stack.toList()
    val state = remember { SeekableTransitionState(stack) }
    val transition = rememberTransition(state, label = "nav")
    val scope = rememberCoroutineScope()
    val saveable = rememberSaveableStateHolder()

    // Predictive back: scrub the pop transition with the gesture, then commit or spring back.
    PredictiveBackHandler(enabled = stack.size > 1) { progress ->
        val current = state.currentState
        try {
            progress.collect { e -> state.seekTo(e.progress, targetState = current.dropLast(1)) }
            nav.pop()
        } catch (e: CancellationException) {
            scope.launch { state.animateTo(current) }
            throw e
        }
    }
    val composedKeys = remember { mutableSetOf<String>() }
    LaunchedEffect(stack) {
        if (state.currentState != stack || state.targetState != stack) state.animateTo(stack)
        // Drop saved UI state (scroll position, etc.) of screens that were popped.
        val live = stack.mapIndexed(::navKey).toSet()
        composedKeys.filter { it !in live }.forEach { saveable.removeState(it); composedKeys.remove(it) }
    }

    SharedTransitionLayout {
        transition.AnimatedContent(
            transitionSpec = {
                val forward = targetState.size >= initialState.size
                val slide = tween<IntOffset>(NAV_MS, easing = FastOutSlowInEasing)
                (slideInHorizontally(slide) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(tween(NAV_MS / 2, NAV_MS / 6)))
                    .togetherWith(slideOutHorizontally(slide) { w -> if (forward) -w / 4 else w / 4 } + fadeOut(tween(NAV_MS / 3)))
                    .apply { targetContentZIndex = if (forward) 1f else -1f }
            },
            contentKey = { it.size to it.last() },
        ) { s ->
            val key = navKey(s.lastIndex, s.last())
            composedKeys.add(key)
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this@SharedTransitionLayout,
                LocalNavAnimatedScope provides this,
            ) {
                saveable.SaveableStateProvider(key) {
                    Surface(Modifier.fillMaxSize()) { NavScreen(s.last(), nav) }
                }
            }
        }
    }
}

private const val NAV_MS = 350
private fun navKey(index: Int, s: Screen) = "$index:$s"

@Composable
private fun NavScreen(s: Screen, nav: NavViewModel) {
    when (s) {
        Screen.Conversations -> ConversationsScreen(
            onOpen = { id, preview -> nav.push(Screen.Thread(id, preview = preview)) },
            onNew = { nav.push(Screen.NewConversation) },
            onSettings = { nav.push(Screen.Settings) },
        )
        is Screen.Thread -> ThreadScreen(s.threadId, s.draft, s.preview, onBack = nav::pop)
        Screen.NewConversation -> NewConversationScreen(
            onBack = nav::pop,
            onStart = { id -> nav.pop(); nav.push(Screen.Thread(id)) },
        )
        Screen.Settings -> SettingsScreen(onBack = nav::pop)
    }
}

@Composable
private fun DefaultAppPrompt(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Textserv", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "To send and receive SMS and MMS, Textserv needs to be your default messaging app.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Set as default") }
    }
}
