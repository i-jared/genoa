package app.gather.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import kotlinx.coroutines.channels.Channel

private sealed class LivePhase {
    data class Preflight(val allowed: Boolean, val surfaceReady: Boolean) : LivePhase()

    data object Establishing : LivePhase()

    data class Sharing(val elapsedMs: Long, val paused: Boolean) : LivePhase()

    data object Ending : LivePhase()
}

@Composable
fun LiveScreen(
    vm: GenoaViewModel,
    reply: String? = null,
    focusId: String? = null,
    onWatch: (String) -> Unit,
    onRecord: (Boolean, String?) -> Unit,
    startOnOpen: Boolean = false,
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
    // A reply tap is a start request, even while the new camera surface is being created.
    var startRequested by remember { mutableStateOf(startOnOpen) }
    var permissionRequested by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var finalizationJob by remember { mutableStateOf<Job?>(null) }
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
    data class Segment(val file: File, val number: Int, val seconds: Double)
    val segments = remember { Channel<Segment>(Channel.UNLIMITED) }
    val sender = remember {
        scope.launch(Dispatchers.IO) {
            for (segment in segments) {
                var sent = false
                var attempt = 0
                while (!sent && isActive) {
                    try {
                        vm.repo.api.putBytes(
                            "/api/live/$id/segments/${segment.number}?duration=${segment.seconds}",
                            segment.file.readBytes(),
                        )
                        sent = true
                        withContext(Dispatchers.Main) {
                            val current = phase
                            if (current is LivePhase.Sharing && current.paused) {
                                phase = current.copy(paused = false)
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        attempt++
                        withContext(Dispatchers.Main) {
                            val current = phase
                            if (current is LivePhase.Sharing) {
                                phase = current.copy(paused = true)
                            }
                        }
                        delay(minOf(10000L, attempt * 1000L))
                    }
                }
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
            capture?.stop()
            segments.close()
            vm.recording(false)
        }
    }
    fun finishWithReplay(file: File, durationMs: Long) {
        if (finalizationJob != null) return
        phase = LivePhase.Ending
        finalizationJob =
            scope.launch {
                segments.close()
                withTimeoutOrNull(10_000) { sender.join() }
                sender.cancel()
                val draft =
                    vm.repo
                        .saveDraft(cid, file, "video", "video/mp4", durationMs, null)
                        .copy(id = id)
                withContext(Dispatchers.IO) {
                    vm.repo.drafts
                        .all()
                        .filter { it.file == file.absolutePath && it.id != id }
                        .forEach { vm.repo.drafts.remove(it.id) }
                    vm.repo.drafts.save(draft)
                }
                vm.repo.enqueue(draft)
                runCatching { vm.repo.api.json("POST", "/api/live/$id/end") }
                onClose()
            }
    }

    fun finishWithoutReplay() {
        if (finalizationJob != null) return
        phase = LivePhase.Ending
        finalizationJob =
            scope.launch {
                segments.close()
                withTimeoutOrNull(10_000) { sender.join() }
                sender.cancel()
                withContext(Dispatchers.IO) { vm.repo.drafts.remove(id) }
                runCatching { vm.repo.api.json("POST", "/api/live/$id/end") }
                onClose()
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
        vm.run {
            var sessionAccepted = false
            try {
                withContext(Dispatchers.IO) { previousPreview?.stop() }
                vm.repo.api.json(
                    "POST",
                    "/api/conversations/$cid/live",
                    json("id" to id, "rotation" to rotation),
                )
                sessionAccepted = true
                val local =
                    vm.repo.saveDraft(
                        cid,
                        File(directory, "recording.mp4"),
                        "video",
                        "video/mp4",
                        0,
                        null,
                    )
                withContext(Dispatchers.IO) {
                    vm.repo.drafts.remove(local.id)
                    vm.repo.drafts.save(local.copy(id = id))
                }
                check(surface === targetSurface && targetSurface.isValid) {
                    "Camera preview became unavailable. Try sharing again."
                }
                val liveCapture =
                    LiveCapture(
                        context,
                        targetSurface,
                        directory,
                        { file, number, seconds ->
                            segments.trySend(Segment(file, number, seconds))
                        },
                        { file, durationMs -> scope.launch { finishWithReplay(file, durationMs) } },
                        { error ->
                            scope.launch {
                                captureError = error
                                phase = LivePhase.Ending
                                delay(3_000)
                                finishWithoutReplay()
                            }
                        },
                        displayRotation,
                    )
                capture = liveCapture
                liveCapture.start()
                vm.recording(true)
                phase = LivePhase.Sharing(elapsedMs = 0, paused = false)
            } catch (error: Exception) {
                captureError = error.message ?: "Could not start live sharing."
                if (sessionAccepted) {
                    withContext(Dispatchers.IO) { vm.repo.drafts.remove(id) }
                    runCatching { vm.repo.api.json("POST", "/api/live/$id/end") }
                    onClose()
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

    BackHandler {
        when (phase) {
            is LivePhase.Preflight -> onClose()
            is LivePhase.Sharing -> endSharing()
            LivePhase.Establishing,
            LivePhase.Ending -> Unit
        }
    }

    val foreground = Color(0xFFEDEFE9)
    val closeLabel =
        if (phase is LivePhase.Sharing || phase is LivePhase.Ending) {
            "End live sharing"
        } else {
            "Close"
        }
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
                    onClick = {
                        when (phase) {
                            is LivePhase.Preflight -> onClose()
                            is LivePhase.Sharing -> endSharing()
                            LivePhase.Establishing,
                            LivePhase.Ending -> Unit
                        }
                    },
                    enabled = phase is LivePhase.Preflight || phase is LivePhase.Sharing,
                ) {
                    GenoaIcon("close", null, tint = foreground)
                }
                when (val current = phase) {
                    is LivePhase.Sharing ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "SHARING LIVE",
                                color = foreground,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                " · ${duration(current.elapsedMs)}",
                                Modifier.clearAndSetSemantics {},
                                color = foreground,
                                style = MaterialTheme.typography.labelLarge,
                            )
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
                if (current is LivePhase.Sharing && current.paused)
                    Text(
                        "Live connection paused.",
                        color = Color(0xFFFFB4AB),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                captureError?.let {
                    Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodyMedium)
                }
                when (current) {
                    LivePhase.Establishing -> MediaLoading("Starting live sharing…")
                    LivePhase.Ending -> MediaLoading("Saving your recording…")
                    else -> Unit
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
                    if (startRequested) MediaLoading("Starting live sharing…")
                    ReplyComposer(
                        vm,
                        state,
                        onRecord,
                        onLive = { startRequested = true },
                        light = true,
                        recordEnabled = !startRequested,
                    )
                } else
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        FilledIconButton(
                            onClick = {
                                when (current) {
                                    is LivePhase.Preflight -> startSharing()
                                    is LivePhase.Sharing -> endSharing()
                                    LivePhase.Establishing,
                                    LivePhase.Ending -> Unit
                                }
                            },
                            enabled =
                                when (current) {
                                    is LivePhase.Preflight ->
                                        !current.allowed || current.surfaceReady
                                    is LivePhase.Sharing -> true
                                    LivePhase.Establishing,
                                    LivePhase.Ending -> false
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
                            else GenoaIcon("video", "Record")
                        }
                    }
            }
        },
    )
}
