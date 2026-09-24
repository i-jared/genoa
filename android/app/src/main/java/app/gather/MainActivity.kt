package app.gather

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gather.ui.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var notificationIntent by mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationIntent = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GenoaTheme {
                val vm: GenoaViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                var live by remember { mutableStateOf(false) }
                var settings by rememberSaveable { mutableStateOf(false) }
                var recorder by remember { mutableStateOf<Boolean?>(null) }
                var reply by remember { mutableStateOf<String?>(null) }
                var focusId by remember { mutableStateOf<String?>(null) }
                var chooseRecipient by rememberSaveable { mutableStateOf(false) }
                val screenState = rememberSaveableStateHolder()
                var notificationConversation by rememberSaveable {
                    mutableStateOf(intent.getStringExtra("conversation_id"))
                }
                var notificationMessage by rememberSaveable {
                    mutableStateOf(intent.getStringExtra("message_id"))
                }
                LaunchedEffect(notificationIntent?.data, intent.data) {
                    val data = (notificationIntent ?: intent).data
                    if (data?.host == "join" && data.scheme in listOf("genoa", "gather")) {
                        data.lastPathSegment
                            ?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess }
                            ?.let {
                                vm.repo.session.pendingInvite = it
                                if (state.person != null) vm.join(it)
                            }
                    }
                    if (data?.scheme == "genoa" && data.host == "auth") {
                        vm.completeSocialSignIn(data)
                        intent.data = null
                        notificationIntent?.data = null
                    }
                }
                LaunchedEffect(notificationIntent) {
                    notificationIntent?.let {
                        notificationConversation = it.getStringExtra("conversation_id")
                        notificationMessage = it.getStringExtra("message_id")
                    }
                }
                LaunchedEffect(vm) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        vm.synchronizeWhileVisible()
                    }
                }
                val lifecycleState by lifecycle.currentStateAsState()
                LaunchedEffect(
                    notificationConversation,
                    notificationMessage,
                    state.person?.id,
                    live,
                    recorder,
                    lifecycleState,
                ) {
                    val cid = notificationConversation ?: return@LaunchedEffect
                    if (
                        state.person == null ||
                            live ||
                            recorder != null ||
                            !lifecycleState.isAtLeast(Lifecycle.State.STARTED)
                    )
                        return@LaunchedEffect
                    while (!vm.openNotification(cid, notificationMessage)) delay(5_000)
                    notificationConversation = null
                    notificationMessage = null
                    intent.removeExtra("conversation_id")
                    intent.removeExtra("message_id")
                    focusId = null
                    settings = false
                    chooseRecipient = false
                }
                fun record(audio: Boolean, target: String?) {
                    stopService(
                        android.content.Intent(
                            this@MainActivity,
                            app.gather.media.PlaybackService::class.java,
                        )
                    )
                    vm.replyMode(true)
                    recorder = audio
                    reply = target
                }
                // Opens preflight; sharing starts only from its explicit Start sharing live action.
                fun shareLive(target: String?) {
                    stopService(
                        android.content.Intent(
                            this@MainActivity,
                            app.gather.media.PlaybackService::class.java,
                        )
                    )
                    reply = target
                    vm.replyMode(true)
                    live = true
                }
                LaunchedEffect(state.selected?.id) { focusId = null }
                val darkAppearance = androidx.compose.foundation.isSystemInDarkTheme()
                SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                        .apply {
                            isAppearanceLightStatusBars =
                                !darkAppearance &&
                                    recorder == null &&
                                    !live &&
                                    state.selected == null
                            isAppearanceLightNavigationBars =
                                !darkAppearance &&
                                    recorder == null &&
                                    !live &&
                                    state.selected == null
                        }
                }
                val snackbar = remember { SnackbarHostState() }
                val notificationPermission =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) {}
                LaunchedEffect(state.person?.id) {
                    if (state.person != null) {
                        vm.registerPush()
                        if (Build.VERSION.SDK_INT >= 33)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        vm.repo.session.pendingInvite?.let { vm.join(it) }
                    }
                }
                LaunchedEffect(state.error) {
                    state.error?.let {
                        snackbar.showSnackbar(
                            it,
                            withDismissAction = true,
                            duration = SnackbarDuration.Long,
                        )
                        vm.clearError()
                    }
                }
                BackHandler(
                    enabled = !live && (settings || (state.selected != null && recorder == null))
                ) {
                    when {
                        settings -> settings = false
                        state.selected != null -> {
                            focusId = null
                            vm.back()
                        }
                    }
                }
                val primaryNavigation =
                    state.person != null &&
                        !live &&
                        recorder == null &&
                        (settings || state.selected == null)
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        if (primaryNavigation)
                            Column {
                                PlaybackMiniPlayer { message ->
                                    state.conversations
                                        .find { it.id == message.conversationId }
                                        ?.let { conversation ->
                                            if (state.selected?.id != conversation.id)
                                                vm.open(conversation, message)
                                            focusId = message.id
                                        }
                                }
                                GenoaNavigation(
                                    settings,
                                    {
                                        settings = false
                                        vm.back()
                                    },
                                    {
                                        settings = false
                                        vm.back()
                                        chooseRecipient = true
                                    },
                                    { settings = true },
                                )
                            }
                    },
                ) { padding ->
                    Box(
                        Modifier.fillMaxSize()
                            .padding(
                                bottom =
                                    if (primaryNavigation) padding.calculateBottomPadding()
                                    else 0.dp
                            ),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            Modifier.fillMaxHeight()
                                .widthIn(
                                    max =
                                        if (recorder != null || live || state.selected != null)
                                            10000.dp
                                        else 600.dp
                                )
                                .fillMaxWidth()
                        ) {
                            when {
                                state.person == null -> AuthScreen(vm, state)
                                live ->
                                    LiveScreen(
                                        vm,
                                        reply,
                                        focusId,
                                        onWatch = { id ->
                                            vm.replyMode(false)
                                            focusId = id
                                            live = false
                                        },
                                        onRecord = { audio, target ->
                                            live = false
                                            record(audio, target)
                                        },
                                    ) {
                                        vm.run {
                                            focusId = vm.incomingAfterRecording()?.id ?: focusId
                                            live = false
                                        }
                                    }
                                recorder != null ->
                                    RecorderScreen(vm, recorder!!, reply) {
                                        vm.run {
                                            focusId = vm.incomingAfterRecording()?.id ?: focusId
                                            recorder = null
                                        }
                                    }
                                settings -> SettingsScreen(vm, state) { settings = false }
                                state.selected != null ->
                                    screenState.SaveableStateProvider(
                                        "watch:${state.selected!!.id}"
                                    ) {
                                        PlayerScreen(
                                            vm,
                                            state,
                                            focusId,
                                            onFocus = { focusId = it },
                                            onRecord = { audio, target -> record(audio, target) },
                                            onLive = { target -> shareLive(target) },
                                            onClose = {
                                                focusId = null
                                                vm.back()
                                            },
                                        )
                                    }
                                else ->
                                    screenState.SaveableStateProvider("home") {
                                        ConversationsScreen(
                                            vm,
                                            state,
                                            onSettings = { settings = true },
                                            onRecord = { c ->
                                                vm.open(c)
                                                shareLive(null)
                                            },
                                            chooseRecipient = chooseRecipient,
                                            onDismissSelector = { chooseRecipient = false },
                                        )
                                    }
                            }
                        }
                        if (state.loading)
                            LinearProgressIndicator(Modifier.fillMaxWidth().statusBarsPadding())
                    }
                }
            }
        }
    }
}
