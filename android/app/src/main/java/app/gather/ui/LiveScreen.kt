package app.gather.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.gather.data.*
import app.gather.media.CameraPreviewTextureView
import app.gather.media.LiveCapture
import app.gather.media.LivePreview
import app.gather.media.VideoRotation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

private sealed class LivePhase {
    data class Preflight(val allowed: Boolean, val surfaceReady: Boolean) : LivePhase()

    data object Establishing : LivePhase()

    data class Sharing(val elapsedMs: Long) : LivePhase()

    data object Ending : LivePhase()

    /** Sharing is over but something needs the person's attention before leaving. */
    data class Ended(val message: String, val canRetry: Boolean) : LivePhase()
}

@Composable
fun LiveScreen(
    vm: GenoaViewModel,
    reply: String? = null,
    focusId: String? = null,
    onWatch: (String) -> Unit,
    onRecord: (Boolean, String?) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsState()
    val cid = state.selected?.id ?: return
    val id = remember { UUID.randomUUID().toString() }
    val directory = remember { File(context.filesDir, "drafts/live-$id") }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var displayRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
    val rotation = VideoRotation.frontHint(context, displayRotation)
    var capture by remember { mutableStateOf<LiveCapture?>(null) }
    var preview by remember { mutableStateOf<LivePreview?>(null) }
    var previewFrameReady by remember { mutableStateOf(false) }
    var previewRetry by remember { mutableIntStateOf(0) }
    var phase by remember {
        mutableStateOf<LivePhase>(
            LivePhase.Preflight(
                allowed =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED,
                surfaceReady = false,
            )
        )
    }
    // Start sharing live is a request that waits for permissions and the camera surface.
    var startRequested by remember { mutableStateOf(false) }
    var permissionRequested by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    // Set once the server may know about this live message; it must then be ended durably.
    var sessionStarted by remember { mutableStateOf(false) }
    val uplink by vm.repo.live.states.collectAsState()
    val uplinkState = uplink[id]
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val current = phase
            if (current is LivePhase.Preflight) {
                val allowed =
                    it[Manifest.permission.CAMERA] == true &&
                        it[Manifest.permission.RECORD_AUDIO] == true
                phase = current.copy(allowed = allowed)
                permissionRequested = false
                if (!allowed) {
                    startRequested = false
                    captureError = "Camera and microphone permissions are needed to record."
                }
            }
        }
    LaunchedEffect(phase is LivePhase.Sharing) {
        val sharing = phase as? LivePhase.Sharing ?: return@LaunchedEffect
        val start = System.currentTimeMillis() - sharing.elapsedMs
        while (phase is LivePhase.Sharing) {
            val current = phase as LivePhase.Sharing
            phase = current.copy(elapsedMs = System.currentTimeMillis() - start)
            delay(1000)
        }
    }
    LaunchedEffect(phase, surface, previewRetry, startRequested) {
        val preflight = phase as? LivePhase.Preflight
        val previewSurface = surface
        if (preflight == null || previewSurface == null) {
            preview?.stop()
            preview = null
            return@LaunchedEffect
        }
        if (preflight.allowed && preview == null && !startRequested) {
            previewFrameReady = false
            val livePreview =
                LivePreview(context, previewSurface) { error ->
                    scope.launch { captureError = error }
                }
            preview = livePreview
            livePreview.start()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            preview?.stop()
            // Stopping capture reports the replay to LiveUplink, which finishes without this screen.
            capture?.stop()
            if (sessionStarted && capture == null) vm.repo.live.finish(id, cid, null, 0)
            vm.recording(false)
        }
    }

    // Distribution stopped for a reason retrying cannot fix: stop capture and keep the replay.
    LaunchedEffect(uplinkState) {
        val stopped = uplinkState as? LiveUplinkState.Stopped ?: return@LaunchedEffect
        if (phase is LivePhase.Sharing) {
            captureError = stopped.message
            phase = LivePhase.Ending
            capture?.stop()
        }
    }

    LaunchedEffect(uplinkState, phase) {
        if (phase != LivePhase.Ending) return@LaunchedEffect
        when (val result = uplinkState) {
            is LiveUplinkState.Finalized -> {
                val notice = result.notice ?: captureError
                if (notice == null) onClose() else phase = LivePhase.Ended(notice, canRetry = false)
            }
            is LiveUplinkState.FinalizeFailed ->
                phase = LivePhase.Ended(result.message, canRetry = true)
            else -> Unit
        }
    }

    fun endSharing() {
        if (phase !is LivePhase.Sharing) return
        phase = LivePhase.Ending
        capture?.stop()
    }

    fun startSharing() {
        val preflight = phase as? LivePhase.Preflight ?: return
        if (!preflight.allowed) return
        val targetSurface = surface ?: return
        val previousPreview = preview
        preview = null
        phase = LivePhase.Establishing
        captureError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { previousPreview?.stop() }
                // Recorded before the request so an interrupted start is still ended later.
                withContext(Dispatchers.IO) {
                    vm.repo.live.begin(
                        LocalLiveSession(id, cid, vm.repo.session.userId, false, directory.absolutePath)
                    )
                }
                sessionStarted = true
                vm.repo.api.json(
                    "POST",
                    "/api/conversations/$cid/live",
                    json("id" to id, "rotation" to rotation),
                )
                withContext(Dispatchers.IO) {
                    vm.repo.drafts.save(
                        Draft(
                            id,
                            cid,
                            File(directory, "recording.mp4").absolutePath,
                            "video",
                            "video/mp4",
                            0,
                            null,
                            DraftState.SAVED,
                            0,
                            null,
                            System.currentTimeMillis(),
                            vm.repo.session.userId,
                        )
                    )
                }
                check(surface === targetSurface && targetSurface.isValid) {
                    "Camera preview became unavailable. Try sharing again."
                }
                var started: LiveCapture? = null
                val liveCapture =
                    LiveCapture(
                        context,
                        targetSurface,
                        directory,
                        { file, number, seconds ->
                            val offer = vm.repo.live.offer(id, file, number, seconds)
                            liveOfferMessage(offer)?.let { message ->
                                scope.launch {
                                    captureError = message
                                    if (phase is LivePhase.Sharing) phase = LivePhase.Ending
                                }
                                started?.stop()
                            }
                        },
                        { file, durationMs -> vm.repo.live.finish(id, cid, file, durationMs) },
                        { error ->
                            scope.launch {
                                captureError = error
                                if (phase is LivePhase.Sharing) phase = LivePhase.Ending
                            }
                        },
                        displayRotation,
                    )
                started = liveCapture
                capture = liveCapture
                liveCapture.start()
                vm.recording(true)
                phase = LivePhase.Sharing(elapsedMs = 0)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                captureError = error.message ?: "Could not start live sharing."
                if (sessionStarted) {
                    if (error is ApiException) {
                        // The server refused the session, so there is nothing to end.
                        withContext(Dispatchers.IO) { vm.repo.live.abandon(id) }
                        sessionStarted = false
                        phase = LivePhase.Preflight(allowed = true, surfaceReady = surface != null)
                    } else {
                        vm.repo.live.finish(id, cid, null, 0)
                        phase = LivePhase.Ending
                    }
                } else {
                    phase = LivePhase.Preflight(allowed = true, surfaceReady = surface != null)
                }
            }
        }
    }

    LaunchedEffect(startRequested, phase, surface) {
        val preflight = phase as? LivePhase.Preflight ?: return@LaunchedEffect
        if (!startRequested) return@LaunchedEffect
        if (!preflight.allowed) {
            if (!permissionRequested) {
                permissionRequested = true
                permission.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }
        } else if (preflight.surfaceReady && surface?.isValid == true) {
            // Consume once: recomposition and a failed network start must not start another session.
            startRequested = false
            startSharing()
        }
    }

    // Finalization belongs to LiveUplink, so leaving while it runs loses nothing.
    fun close() {
        when (phase) {
            is LivePhase.Preflight,
            is LivePhase.Ended,
            LivePhase.Ending -> onClose()
            is LivePhase.Sharing -> endSharing()
            LivePhase.Establishing -> Unit
        }
    }
    BackHandler { close() }

    val foreground = Color(0xFFEDEFE9)
    val closeLabel = if (phase is LivePhase.Sharing) "End live sharing" else "Close"
    ImmersiveChrome(
        policy = ChromePolicy.Persistent,
        screenDescription = "Live sharing",
        media = {
            AndroidView(
                factory = {
                    CameraPreviewTextureView(it).apply {
                        onDisplayRotationChanged = { displayRotation = it }
                        surfaceTextureListener =
                            object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    texture: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    texture.setDefaultBufferSize(1280, 720)
                                    displayRotation = this@apply.display.rotation
                                    surface = Surface(texture)
                                    val current = phase
                                    if (current is LivePhase.Preflight) {
                                        phase = current.copy(surfaceReady = true)
                                    }
                                    updateTransform()
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    texture: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    displayRotation = this@apply.display.rotation
                                    updateTransform()
                                }

                                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                                    previewFrameReady = true
                                }

                                override fun onSurfaceTextureDestroyed(
                                    texture: SurfaceTexture
                                ): Boolean {
                                    if (phase is LivePhase.Sharing) endSharing()
                                    surface?.release()
                                    surface = null
                                    val current = phase
                                    if (current is LivePhase.Preflight) {
                                        phase = current.copy(surfaceReady = false)
                                    }
                                    return true
                                }
                            }
                    }
                },
                update = { view -> view.updateTransform() },
                modifier = Modifier.fillMaxSize(),
            )
            val preflight = phase as? LivePhase.Preflight
            if (preflight?.allowed == true && !startRequested && !previewFrameReady && captureError == null)
                MediaLoading(
                    "Starting camera…",
                    modifier = Modifier.align(Alignment.Center),
                    onRetry = {
                        val previous = preview
                        preview = null
                        scope.launch {
                            withContext(Dispatchers.IO) { previous?.stop() }
                            previewRetry++
                        }
                    },
                )
        },
        chrome = {
            Row(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChromeIconButton(
                    label = closeLabel,
                    onClick = ::close,
                    enabled = phase != LivePhase.Establishing,
                ) {
                    GenoaIcon("close", null, tint = foreground)
                }
                when (val current = phase) {
                    is LivePhase.Sharing ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            // Filled coral badge with text: sharing state never relies on color alone.
                            Row(
                                Modifier.background(Capture, RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .testTag("sharing-live"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(8.dp).background(AvatarInk, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "SHARING LIVE",
                                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    color = AvatarInk,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    " · ${duration(current.elapsedMs)}",
                                    Modifier.clearAndSetSemantics {},
                                    color = AvatarInk,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    else -> Spacer(Modifier.weight(1f))
                }
            }

            val current = phase
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "To: ${state.selected?.title.orEmpty()}",
                    color = foreground,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (current is LivePhase.Sharing && uplinkState is LiveUplinkState.Paused)
                    Text(
                        "Live connection paused. Recording continues on this phone; viewers catch up when it reconnects.",
                        color = Color(0xFFFFB4AB),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                val notice = (current as? LivePhase.Ended)?.message ?: captureError
                notice?.let {
                    Text(
                        it,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = Color(0xFFFFB4AB),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                when (current) {
                    LivePhase.Establishing -> MediaLoading("Starting live sharing…")
                    LivePhase.Ending -> MediaLoading("Saving your recording…")
                    else -> Unit
                }
                if (current is LivePhase.Ended) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (current.canRetry)
                            Button({
                                phase = LivePhase.Ending
                                vm.repo.live.retryFinish(id)
                            }) {
                                Text("Try again")
                            }
                        OutlinedButton(onClose) { Text("Back to conversation", color = foreground) }
                    }
                    if (current.canRetry)
                        Text(
                            "If you leave, the recording stays in this conversation as a draft you can send or discard.",
                            color = foreground,
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
                if (current is LivePhase.Preflight) {
                    MessageFilmstrip(
                        state.messages,
                        focusId,
                        vm.repo.session.baseUrl,
                        vm.repo.session.token,
                        onWatch,
                        vm::loadOlder,
                        hasOlder = state.hasOlder,
                        hasNewer = state.hasNewer,
                        loadingOlder = state.loadingOlder,
                        loadingNewer = state.loadingNewer,
                        onLater = vm::loadNewer,
                    )
                    // Consent: nobody can watch until this explicit, labeled action.
                    Text(
                        "Start sharing live when you’re ready. Everyone in ${state.selected?.title.orEmpty()} can watch as you record, and the replay stays in the conversation.",
                        color = foreground,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (startRequested) MediaLoading("Starting live sharing…")
                    ReplyComposer(
                        vm,
                        state,
                        onRecord,
                        onLive = { startRequested = true },
                        light = true,
                        recordEnabled = !startRequested,
                        recordLabel = "Start sharing live",
                    )
                } else if (current !is LivePhase.Ended)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        FilledIconButton(
                            onClick = {
                                if (current is LivePhase.Sharing) endSharing()
                            },
                            enabled =
                                when (current) {
                                    is LivePhase.Preflight ->
                                        !current.allowed || current.surfaceReady
                                    is LivePhase.Sharing -> true
                                    else -> false
                                },
                            modifier = Modifier.size(64.dp),
                            colors =
                                IconButtonDefaults.filledIconButtonColors(
                                    containerColor =
                                        if (current is LivePhase.Sharing) Capture
                                        else MaterialTheme.colorScheme.primary,
                                    contentColor =
                                        if (current is LivePhase.Sharing) AvatarInk
                                        else MaterialTheme.colorScheme.onPrimary,
                                ),
                        ) {
                            if (current is LivePhase.Sharing || current is LivePhase.Ending)
                                Icon(Icons.Outlined.Stop, "Stop recording")
                            else GenoaIcon("video", "Start sharing live")
                        }
                    }
            }
        },
    )
}
