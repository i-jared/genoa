package app.gather.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gather.data.Draft
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(
    vm: GenoaViewModel,
    initialAudioOnly: Boolean,
    reply: String?,
    onClose: () -> Unit,
) {
    var audioOnly by rememberSaveable { mutableStateOf(initialAudioOnly) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsState()
    val cid = state.selected?.id ?: return
    var allowed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED &&
                (audioOnly ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED)
        )
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            allowed =
                it[Manifest.permission.RECORD_AUDIO] == true &&
                    (audioOnly || it[Manifest.permission.CAMERA] == true)
        }
    var capture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var audio by remember { mutableStateOf<MediaRecorder?>(null) }
    var active by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var queuedNotice by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discarding by remember { mutableStateOf(false) }
    var previewDraft by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var finalizing by remember { mutableStateOf(false) }
    var leaveRecording by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(true) }
    var canSwitchCamera by remember { mutableStateOf(false) }
    BackHandler { if (active) leaveRecording = true else if (!finalizing && !preparing) onClose() }
    val preview = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    LaunchedEffect(audioOnly) {
        allowed =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED &&
                (audioOnly ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED)
    }
    LaunchedEffect(allowed, front, audioOnly) {
        if (allowed && !audioOnly) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    runCatching {
                            val provider = future.get()
                            val hasFront = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                            val hasBack = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                            canSwitchCamera = hasFront && hasBack
                            val selector =
                                if (front && hasFront || !hasBack)
                                    CameraSelector.DEFAULT_FRONT_CAMERA
                                else CameraSelector.DEFAULT_BACK_CAMERA
                            val rotation = preview.display.rotation
                            val p =
                                Preview.Builder().setTargetRotation(rotation).build().apply {
                                    surfaceProvider = preview.surfaceProvider
                                }
                            val recorder =
                                Recorder.Builder()
                                    .setQualitySelector(
                                        QualitySelector.from(
                                            Quality.FHD,
                                            FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD),
                                        )
                                    )
                                    .build()
                            val video =
                                VideoCapture.withOutput(recorder).apply {
                                    targetRotation = rotation
                                }
                            provider.unbindAll()
                            try {
                                provider.bindToLifecycle(lifecycle, selector, p, video)
                            } catch (unsupportedCamera: IllegalArgumentException) {
                                // Some devices expose a camera without usable video profiles.
                                if (!hasFront || !hasBack) throw unsupportedCamera
                                provider.unbindAll()
                                val fallback =
                                    if (selector == CameraSelector.DEFAULT_FRONT_CAMERA)
                                        CameraSelector.DEFAULT_BACK_CAMERA
                                    else CameraSelector.DEFAULT_FRONT_CAMERA
                                provider.bindToLifecycle(lifecycle, fallback, p, video)
                                canSwitchCamera = false
                            }
                            capture = video
                        }
                        .onFailure {
                            error = "Camera unavailable. Check permissions and try again."
                        }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }
    LaunchedEffect(active) {
        if (active) {
            val start = System.currentTimeMillis()
            while (active) {
                elapsed = System.currentTimeMillis() - start
                vm.recording(true)
                delay(1000)
            }
        } else vm.recording(false)
    }
    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            audio?.run {
                runCatching { stop() }
                release()
            }
            vm.recording(false)
        }
    }
    fun save(f: File, duration: Long) {
        if (discarding) return
        finalizing = true
        scope.launch {
            runCatching {
                    vm.repo.saveDraft(
                        cid,
                        f,
                        if (audioOnly) "audio" else "video",
                        "${if(audioOnly)"audio" else "video"}/mp4",
                        duration,
                        null,
                    )
                }
                .onSuccess {
                    draft = it
                    finalizing = false
                }
                .onFailure {
                    error = "Couldn’t save this recording. Check available storage and try again."
                    finalizing = false
                }
        }
    }
    DisposableEffect(lifecycle, active) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && active) {
                    if (audioOnly) {
                        runCatching { audio?.stop() }
                            .onSuccess {
                                file?.let { f ->
                                    val retriever = android.media.MediaMetadataRetriever()
                                    val duration =
                                        try {
                                            retriever.setDataSource(f.absolutePath)
                                            retriever
                                                .extractMetadata(
                                                    android.media.MediaMetadataRetriever
                                                        .METADATA_KEY_DURATION
                                                )
                                                ?.toLongOrNull() ?: elapsed
                                        } finally {
                                            retriever.release()
                                        }
                                    save(f, duration)
                                }
                            }
                        audio?.release()
                        audio = null
                    } else {
                        finalizing = true
                        recording?.stop()
                    }
                    active = false
                }
            }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    fun toggleRecording() {
        if (active) {
            finalizing = true
            if (audioOnly) {
                runCatching { audio?.stop() }
                    .onSuccess {
                        file?.let { f ->
                            val retriever = android.media.MediaMetadataRetriever()
                            val duration =
                                try {
                                    retriever.setDataSource(f.absolutePath)
                                    retriever
                                        .extractMetadata(
                                            android.media.MediaMetadataRetriever
                                                .METADATA_KEY_DURATION
                                        )
                                        ?.toLongOrNull() ?: elapsed
                                } finally {
                                    retriever.release()
                                }
                            save(f, duration)
                        }
                    }
                    .onFailure {
                        error = "Couldn’t save this recording. Please try again."
                        finalizing = false
                    }
                audio?.release()
                audio = null
            } else recording?.stop()
            active = false
        } else {
            if (
                android.os.StatFs(context.filesDir.absolutePath).availableBytes < 10L * 1024 * 1024
            ) {
                error =
                    "Not enough storage to start recording. Free some space on this phone and try again."
                return
            }
            val f =
                File(
                    File(context.filesDir, "drafts").apply { mkdirs() },
                    "${UUID.randomUUID()}.mp4",
                )
            file = f
            elapsed = 0
            preparing = true
            scope.launch {
                try {
                    vm.repo.saveDraft(
                        cid,
                        f,
                        if (audioOnly) "audio" else "video",
                        if (audioOnly) "audio/mp4" else "video/mp4",
                        0,
                        null,
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    preparing = false
                    error = "Couldn’t prepare a saved draft. Check storage and try again."
                    return@launch
                }
                preparing = false
                if (audioOnly) {
                    runCatching {
                            val recorder =
                                if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context)
                                else @Suppress("DEPRECATION") MediaRecorder()
                            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                            recorder.setAudioEncodingBitRate(128000)
                            recorder.setAudioSamplingRate(44100)
                            recorder.setOutputFile(f.absolutePath)
                            recorder.prepare()
                            recorder.start()
                            audio = recorder
                            active = true
                        }
                        .onFailure { error = it.message }
                } else {
                    val pending =
                        capture
                            ?.output
                            ?.prepareRecording(context, FileOutputOptions.Builder(f).build())
                    if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        recording =
                            pending?.withAudioEnabled()?.start(
                                ContextCompat.getMainExecutor(context)
                            ) { event ->
                                when (event) {
                                    is VideoRecordEvent.Start -> active = true
                                    is VideoRecordEvent.Finalize -> {
                                        active = false
                                        recording = null
                                        if (!event.hasError())
                                            save(
                                                f,
                                                event.recordingStats.recordedDurationNanos / 1000000,
                                            )
                                        else {
                                            finalizing = false
                                            error =
                                                "Recording interrupted. Return to the conversation to check the recovered draft."
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }
    }
    val recipient = state.selected?.title.orEmpty()
    val foreground = Color(0xFFEDEFE9)
    val closeRecorder = {
        if (active) leaveRecording = true else if (!finalizing && !preparing) onClose()
    }
    @Composable
    fun PreviewPane(modifier: Modifier) {
        Box(modifier, contentAlignment = Alignment.Center) {
            when {
                draft != null ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GenoaIcon("check", null, Modifier.size(48.dp), foreground)
                        Text(
                            "Saved on this phone",
                            color = foreground,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(duration(draft!!.durationMs), color = foreground)
                        OutlinedButton(
                            { previewDraft = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                        ) {
                            GenoaIcon("play", null)
                            Spacer(Modifier.width(8.dp))
                            Text("Preview recording")
                        }
                    }
                !allowed ->
                    Column(
                        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (audioOnly) "Microphone access is needed to record your voice."
                            else "Camera and microphone access are needed to record a video.",
                            color = foreground,
                        )
                        Button({
                            permission.launch(
                                if (audioOnly) arrayOf(Manifest.permission.RECORD_AUDIO)
                                else
                                    arrayOf(
                                        Manifest.permission.RECORD_AUDIO,
                                        Manifest.permission.CAMERA,
                                    )
                            )
                        }) {
                            Text(
                                if (audioOnly) "Allow microphone" else "Allow camera and microphone"
                            )
                        }
                        if (!audioOnly)
                            TextButton({ audioOnly = true }) {
                                Text("Use voice instead", color = foreground)
                            }
                        TextButton(onClose) { Text("Return to conversation", color = foreground) }
                    }
                audioOnly ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.Mic, null, Modifier.size(72.dp), tint = foreground)
                        Text(
                            duration(elapsed),
                            color = foreground,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                else -> AndroidView({ preview }, Modifier.fillMaxSize())
            }
            if (active || finalizing || preparing)
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(12.dp),
                    color = CameraBackdrop.copy(alpha = .95f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        if (active) "Recording · ${duration(elapsed)}"
                        else if (finalizing) "Saving recording…" else "Preparing recording…",
                        Modifier.padding(12.dp),
                        color = foreground,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
        }
    }
    @Composable
    fun ControlsPane(modifier: Modifier) {
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (queuedNotice && draft == null)
                Text("Message queued. Ready for another?", color = foreground)
            error?.let { Text(it, color = Color(0xFFFFB4AB)) }
            if (state.note.isNotBlank())
                Text(
                    "Private note: ${state.note}",
                    color = foreground,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                )
            if (draft != null) {
                Button(
                    {
                        vm.run {
                            vm.repo.enqueue(draft!!)
                            draft = null
                            file = null
                            elapsed = 0L
                            queuedNotice = true
                            if (vm.incomingAfterRecording() != null) onClose()
                        }
                    },
                    Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    enabled = state.conversations.any { it.id == cid },
                ) {
                    Text("Send to $recipient")
                }
                if (state.conversations.none { it.id == cid })
                    Text(
                        "You can’t send to this conversation anymore. Your draft is saved.",
                        color = foreground,
                    )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClose) { Text("Keep draft", color = foreground) }
                    TextButton({ discard = true }) { Text("Discard", color = Color(0xFFFFB4AB)) }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        { front = !front },
                        enabled =
                            canSwitchCamera && !audioOnly && !active && !finalizing && !preparing,
                        modifier = Modifier.size(48.dp),
                    ) {
                        GenoaIcon(
                            "flip-camera",
                            "Switch camera",
                            tint =
                                if (canSwitchCamera && !audioOnly && !active) foreground
                                else foreground.copy(alpha = .4f),
                        )
                    }
                    CaptureButton(
                        active,
                        !preparing && !finalizing && allowed && (audioOnly || capture != null),
                        ::toggleRecording,
                    )
                    Spacer(Modifier.size(48.dp))
                }
                Text(
                    if (active) "Stop recording" else "Start recording",
                    color = foreground,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                if (!active && !finalizing && !preparing)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            !audioOnly,
                            { audioOnly = false },
                            label = { Text("Video") },
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    labelColor = foreground,
                                    selectedContainerColor = Color(0xFFDCEBE3),
                                    selectedLabelColor = AvatarInk,
                                ),
                        )
                        FilterChip(
                            audioOnly,
                            { audioOnly = true },
                            label = { Text("Voice") },
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    labelColor = foreground,
                                    selectedContainerColor = Color(0xFFDCEBE3),
                                    selectedLabelColor = AvatarInk,
                                ),
                        )
                    }
            }
        }
    }
    Scaffold(containerColor = CameraBackdrop) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(closeRecorder, enabled = !finalizing && !preparing) {
                    GenoaIcon("close", "Close recorder", tint = foreground)
                }
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(
                        "To: $recipient",
                        color = foreground,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth > maxHeight)
                    Row(Modifier.fillMaxSize()) {
                        PreviewPane(Modifier.weight(1f).fillMaxHeight())
                        ControlsPane(Modifier.weight(.8f).fillMaxHeight())
                    }
                else
                    Column(Modifier.fillMaxSize()) {
                        PreviewPane(Modifier.weight(1f).fillMaxWidth())
                        ControlsPane(Modifier.fillMaxWidth().heightIn(max = 360.dp))
                    }
            }
        }
    }
    if (previewDraft && draft != null) DraftPreview(draft!!) { previewDraft = false }
    if (leaveRecording)
        GenoaDialog(
            onDismissRequest = { leaveRecording = false },
            title = { Text("Stop and review?") },
            text = { Text("Your recording is private. Stop to review it before sending.") },
            confirmButton = {
                TextButton({
                    leaveRecording = false
                    toggleRecording()
                }) {
                    Text("Stop and review")
                }
            },
            dismissButton = { TextButton({ leaveRecording = false }) { Text("Keep recording") } },
        )
    if (discard)
        GenoaDialog(
            onDismissRequest = { discard = false },
            title = { Text("Discard this recording?") },
            text = { Text("This removes the saved recording from this phone.") },
            confirmButton = {
                TextButton({
                    discarding = true
                    draft?.let { vm.run { vm.repo.discard(it) } }
                    onClose()
                }) {
                    Text("Discard")
                }
            },
            dismissButton = { TextButton({ discard = false }) { Text("Keep draft") } },
        )
}

@Composable
fun CaptureButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = if (recording) "Stop recording" else "Start recording"
    Box(
        Modifier.size(72.dp)
            .border(2.dp, Color(0xFFEDEFE9), CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(if (enabled) Capture else Capture.copy(alpha = .4f))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                stateDescription = if (recording) "Recording privately" else "Not recording"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (recording)
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(AvatarInk))
    }
}
