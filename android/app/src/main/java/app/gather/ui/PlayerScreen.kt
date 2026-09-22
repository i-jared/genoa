package app.gather.ui

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.gather.data.Draft
import app.gather.data.DraftState
import app.gather.data.Message
import app.gather.media.PlaybackService
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    vm: GenoaViewModel,
    state: AppState,
    focusId: String?,
    onFocus: (String?) -> Unit,
    onRecord: (Boolean, String?) -> Unit,
    onLive: (String?) -> Unit,
    onClose: () -> Unit,
) {
    val conversation = state.selected ?: return
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var speedMenu by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0) }
    var mediaDuration by remember { mutableLongStateOf(0) }
    var mediaState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var playing by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var completionEligible by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var completionReported by remember { mutableStateOf(false) }
    var savedOnly by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var emojiPicker by remember { mutableStateOf(false) }
    var pickerOrigin by remember { mutableStateOf(Offset.Zero) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val sentEmojis = remember { mutableStateListOf<SentEmoji>() }
    var dialog by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(state.note) }
    var selected by remember { mutableStateOf<Message?>(null) }
    var previewDraft by remember { mutableStateOf<Draft?>(null) }
    var discardDraft by remember { mutableStateOf<Draft?>(null) }
    var composing by remember { mutableStateOf(false) }
    var itemFinished by remember { mutableStateOf(false) }
    var cameraAllowed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            cameraAllowed = it
        }
    var savedMessages by remember(conversation.id) { mutableStateOf<List<Message>>(emptyList()) }
    LaunchedEffect(savedOnly, state.messages) {
        if (savedOnly)
            vm.run { savedMessages = vm.repo.messages(conversation.id, bookmarked = true) }
    }
    val pendingDrafts =
        state.drafts.filter { draft ->
            draft.conversationId == conversation.id &&
                draft.state != DraftState.SENT &&
                state.messages.none {
                    it.id == draft.id && (it.state == "ready" || it.state == "live")
                }
        }
    val pendingIds = pendingDrafts.map { it.id }.toSet()
    val queue = (if (savedOnly) savedMessages else state.messages).filter { it.id !in pendingIds }
    val currentQueue by rememberUpdatedState(queue)
    val current =
        queue.find { it.id == focusId }
            ?: queue.find { it.id == state.openingFocusId }
            ?: queue.getOrNull(startingWatchIndex(queue, state.person?.id))
    val currentIndex = current?.let { queue.indexOfFirst { item -> item.id == it.id } } ?: -1
    var videoRevealed by remember(current?.id) { mutableStateOf(false) }
    LaunchedEffect(current?.id) { vm.focus(current) }
    LaunchedEffect(current?.id, queue.lastOrNull()?.id) {
        if (!savedOnly && state.hasNewer && currentIndex >= queue.size - 5) vm.loadNewer()
    }
    val latestState by vm.state.collectAsState()
    val latestHasNewer by rememberUpdatedState(state.hasNewer && !savedOnly)
    val currentMessage =
        current?.let { latestState.messages.find { item -> item.id == it.id } ?: it }
    val currentMessageState by rememberUpdatedState(currentMessage)
    val source =
        currentMessage?.let { message ->
            if (message.assetId != null) "${vm.repo.session.baseUrl}/api/media/${message.assetId}"
            else "${vm.repo.session.baseUrl}/api/live/${message.id}/playlist.m3u8"
        }
    var photoLoaded by remember(current?.id, source) { mutableStateOf(false) }
    var photoFailed by remember(current?.id, source) { mutableStateOf(false) }
    var photoRetry by remember(current?.id, source) { mutableIntStateOf(0) }
    val livePlayback = current?.assetId == null
    val playable =
        current?.watchKind() == WatchKind.VIDEO || current?.watchKind() == WatchKind.VOICE
    fun sendReaction(emoji: String) {
        val message = current ?: return
        val origin = pickerOrigin - overlayOrigin
        vm.reaction(message, emoji, position) { id ->
            if (
                vm.state.value.selected?.id == message.conversationId &&
                    controller?.currentMediaItem?.mediaId == message.id
            ) {
                if (sentEmojis.size >= 12) sentEmojis.removeAt(0)
                sentEmojis.add(SentEmoji(id, emoji, origin))
            }
        }
    }
    val chrome =
        if (state.replyMode || itemFinished) WatchChrome.REPLY
        else watchChrome(queue, current?.id, false)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inForeground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(conversation.id, queue.map { it.id }, focusId, state.person?.id) {
        val start = current?.id ?: queue.getOrNull(startingWatchIndex(queue, state.person?.id))?.id
        if (start != focusId) onFocus(start)
    }
    LaunchedEffect(current?.id) {
        sentEmojis.clear()
        completionEligible = false
        advanced = false
        completionReported = false
        unavailable = false
        playbackError = null
        position = 0
        mediaDuration = current?.durationMs ?: 0
        itemFinished = false
    }
    LaunchedEffect(itemFinished) { if (itemFinished) vm.replyMode(true) }
    LaunchedEffect(current?.id, photoLoaded, photoRetry) {
        if (current?.kind == "photo" && !photoLoaded) {
            delay(15_000)
            photoFailed = true
        }
    }
    LaunchedEffect(chrome, state.openingConversation) {
        if (state.openingConversation) return@LaunchedEffect
        if (chrome == WatchChrome.REPLY && !cameraAllowed)
            cameraPermission.launch(Manifest.permission.CAMERA)
        if (chrome == WatchChrome.REPLY) controller?.pause()
    }
    LaunchedEffect(
        current?.id,
        current?.kind,
        dialog,
        seeking,
        composing,
        inForeground,
        chrome,
        emojiPicker,
        photoLoaded,
        state.hasNewer,
        state.loadingNewer,
    ) {
        val message = current ?: return@LaunchedEffect
        val dwell = message.watchAdvanceMs() ?: return@LaunchedEffect
        if (message.kind == "photo" && !photoLoaded) return@LaunchedEffect
        if (
            chrome == WatchChrome.REPLY ||
                !inForeground ||
                dialog.isNotBlank() ||
                seeking ||
                composing ||
                emojiPicker
        )
            return@LaunchedEffect
        delay(dwell)
        vm.progress(message, 0, true)
        val next = nextIncomingMessage(currentQueue, vm.repo.session.userId, message.id)
        if (next != null) onFocus(next.id) else if (!latestHasNewer) itemFinished = true
    }
    LaunchedEffect(controller, current?.id) {
        while (true) {
            val message = currentMessageState
            val active = controller?.takeIf { it.currentMediaItem?.mediaId == message?.id }
            val progress =
                watchProgress(
                    active?.currentPosition ?: 0,
                    active?.duration ?: 0,
                    message?.durationMs ?: 0,
                    active?.playbackState == Player.STATE_ENDED,
                )
            val currentPosition = progress.positionMs
            mediaDuration = progress.durationMs
            mediaState = active?.playbackState ?: Player.STATE_IDLE
            if (
                (playing || controller?.isPlaying == true) &&
                    currentPosition > position &&
                    currentPosition - position < 2000
            )
                completionEligible = true
            if (!seeking) position = currentPosition
            playing = active?.isPlaying == true
            if (
                !advanced &&
                    !itemFinished &&
                    completionEligible &&
                    active?.playbackState == Player.STATE_ENDED &&
                    message != null &&
                    message.watchAdvanceMs() == null &&
                    message.state != "live" &&
                    !seeking
            ) {
                if (!completionReported) {
                    position = progress.durationMs
                    vm.progress(message, position, true)
                    completionReported = true
                    // Paint the endpoint before advancing or waiting for the next history page.
                    delay(200)
                }
                if (
                    seeking ||
                        controller?.currentMediaItem?.mediaId != message.id ||
                        controller?.playbackState != Player.STATE_ENDED
                )
                    continue
                val next = nextIncomingMessage(currentQueue, vm.repo.session.userId, message.id)
                if (next != null) {
                    advanced = true
                    onFocus(next.id)
                } else if (!latestHasNewer) {
                    itemFinished = true
                }
            }
            playbackError =
                active?.playerError?.let {
                    "Playback interrupted. Check your connection and try again."
                }
            delay(250)
        }
    }
    DisposableEffect(Unit) {
        var disposed = false
        val future =
            MediaController.Builder(
                    context,
                    SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                )
                .buildAsync()
        future.addListener(
            {
                if (!disposed) {
                    runCatching { future.get() }
                        .onSuccess {
                            controller = it
                            speed = it.playbackParameters.speed
                        }
                        .onFailure {
                            playbackError =
                                "Couldn't connect to playback. Reopen this conversation to retry."
                        }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            controller = null
            MediaController.releaseFuture(future)
        }
    }
    LaunchedEffect(controller, source, current?.id, playable, state.replyMode) {
        val activeController = controller ?: return@LaunchedEffect
        val message = currentMessage ?: return@LaunchedEffect
        val uri = source ?: return@LaunchedEffect
        if (state.replyMode) {
            activeController.pause()
            return@LaunchedEffect
        }
        if (!playable) {
            activeController.stop()
            return@LaunchedEffect
        }
        val activeItem = activeController.currentMediaItem
        if (activeItem?.mediaId != message.id) {
            activeController.setMediaItem(
                playerMediaItem(message, uri, conversation.title),
                if (message.watched) 0L else message.positionMs,
            )
            activeController.setPlaybackSpeed(speed)
            activeController.prepare()
            activeController.play()
        } else if (activeItem.localConfiguration?.uri?.toString() != uri) {
            val resumePosition = activeController.currentPosition.coerceAtLeast(0)
            val resumePlaying = activeController.isPlaying
            activeController.setMediaItem(
                playerMediaItem(message, uri, conversation.title),
                resumePosition,
            )
            activeController.prepare()
            if (resumePlaying) activeController.play()
        }
    }
    LaunchedEffect(latestState.selected?.id, latestState.messages, current?.id) {
        if (
            current != null &&
                (latestState.selected?.id != current.conversationId ||
                    latestState.messages.none { it.id == current.id })
        ) {
            controller?.stop()
            unavailable = true
        }
    }
    // Connect to the playback service while the history request is still in flight.
    if (state.openingConversation) {
        Box(
            Modifier.fillMaxSize().background(CameraBackdrop),
            contentAlignment = Alignment.Center,
        ) {
            MediaLoading("Loading conversation…")
        }
        return
    }
    val foreground = Color(0xFFEDEFE9)
    val chromeHold =
        when {
            chrome == WatchChrome.REPLY -> ChromeHold.Persistent
            dialog.isNotBlank() || speedMenu || composing || emojiPicker -> ChromeHold.Sheet
            playbackError != null || unavailable -> ChromeHold.Fault
            seeking -> ChromeHold.Seeking
            else -> ChromeHold.Transient
        }
    ImmersiveChrome(
        policy =
            if (chrome == WatchChrome.REPLY) ChromePolicy.Persistent else ChromePolicy.AutoHide,
        screenDescription = "Message player",
        isPlaying = playing || current?.watchAdvanceMs() != null,
        hold = chromeHold,
        modifier = Modifier.onGloballyPositioned { overlayOrigin = it.positionInRoot() },
        media = {
            when {
                chrome == WatchChrome.REPLY && cameraAllowed -> FrontCameraPreview()
                current == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No messages yet",
                            color = foreground,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                current.kind == "photo" && source != null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        key(current.id, source, photoRetry) {
                            AsyncImage(
                                model =
                                    ImageRequest.Builder(context)
                                        .data(source)
                                        .addHeader(
                                            "Authorization",
                                            "Bearer ${vm.repo.session.token}",
                                        )
                                        .build(),
                                contentDescription = "Photo from ${current.authorName}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                                onSuccess = {
                                    photoLoaded = true
                                    photoFailed = false
                                },
                                onError = { photoFailed = true },
                            )
                        }
                        if (photoFailed)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Couldn’t load this photo.", color = foreground)
                                TextButton({
                                    photoFailed = false
                                    photoRetry++
                                }) {
                                    Text("Retry photo")
                                }
                            }
                        else if (!photoLoaded) MediaLoading("Loading photo…")
                    }
                current.kind == "text" ->
                    Box(
                        Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (current.urgent)
                                Text(
                                    "Urgent note",
                                    color = Color(0xFFFFB4AB),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            Text(
                                current.text,
                                color = foreground,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    }
                current.kind == "audio" ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Mic,
                                "Voice message from ${current.authorName}",
                                Modifier.size(72.dp),
                                tint = foreground,
                            )
                            Text(
                                duration(current.durationMs),
                                color = foreground,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            if (
                                mediaState == Player.STATE_IDLE ||
                                    mediaState == Player.STATE_BUFFERING
                            )
                                MediaLoading(
                                    "Loading voice message…",
                                    onRetry = {
                                        controller?.prepare()
                                        controller?.play()
                                    },
                                )
                        }
                    }
                else ->
                    if (source != null)
                        key(current.id, source) {
                            PreparedVideo(
                                controller,
                                current.id,
                                source,
                                if (livePlayback) current.liveRotation else null,
                                onReady = { videoRevealed = true },
                            )
                        }
            }
        },
        captions = {
            currentMessage?.let { message ->
                if (chrome == WatchChrome.PLAYING)
                    Box(Modifier.align(Alignment.TopCenter)) {
                        ReactionCards(
                            message.id,
                            message.reactions,
                            state.person?.id,
                            position,
                            seeking,
                        )
                    }
            }
            sentEmojis.toList().forEach { event ->
                key(event.id) {
                    FloatingSentEmoji(event) { sentEmojis.removeAll { it.id == event.id } }
                }
            }
        },
        chrome = {
            Row(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChromeIconButton("Back", onClose) { GenoaIcon("back", null, tint = foreground) }
                Column(
                    Modifier.weight(1f)
                        .padding(horizontal = 4.dp)
                        .clickable { dialog = "members" }
                        .semantics(mergeDescendants = true) {}
                ) {
                    Text(
                        conversation.title,
                        color = foreground,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    val subtitle =
                        when {
                            showWatchAuthor(queue, currentIndex) && current != null ->
                                if (current.authorId == state.person?.id)
                                    "You · ${timeLabel(current.createdAt)}"
                                else "${current.authorName} · ${timeLabel(current.createdAt)}"
                            current != null -> timeLabel(current.createdAt)
                            else ->
                                state.recordingPerson
                                    ?: "${conversation.members} ${if (conversation.members == 1) "person" else "people"}"
                        }
                    Text(
                        subtitle,
                        color = foreground.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                ConversationMenu(
                    conversation,
                    savedOnly,
                    onDetails = { dialog = "members" },
                    onNote = {
                        note = state.note
                        dialog = "note"
                    },
                    onToggleSaved = { savedOnly = !savedOnly },
                    onFavorite = { vm.toggleConversation(favorite = !conversation.favorite) },
                    onMute = { vm.toggleConversation(muted = !conversation.muted) },
                    tint = foreground,
                )
            }
        },
        bottomControls = {
            if (playable && !unavailable && chrome == WatchChrome.PLAYING) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            Modifier.weight(1f).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilledIconButton(
                                onClick = {
                                    controller?.let {
                                        if (it.isPlaying) it.pause()
                                        else {
                                            if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                                            it.play()
                                        }
                                    }
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                if (playing) Icon(Icons.Outlined.Pause, "Pause message")
                                else
                                    GenoaIcon(
                                        "play",
                                        if (position > 0) "Resume message" else "Play message",
                                    )
                            }
                            Text(
                                "${duration(position)} / ${
                                if (currentMessage?.state == "live") "Live"
                                else duration(mediaDuration)
                            }",
                                color = foreground,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Box {
                                ChromeIconButton(
                                    label =
                                        "Playback speed ${PlaybackRate.entries.firstOrNull { it.factor == speed }?.label ?: "1×"}",
                                    onClick = { speedMenu = true },
                                ) {
                                    Icon(Icons.Outlined.Speed, null, tint = foreground)
                                }
                                DropdownMenu(speedMenu, { speedMenu = false }) {
                                    PlaybackRate.entries.forEach { rate ->
                                        DropdownMenuItem(
                                            text = { Text(rate.label) },
                                            onClick = {
                                                speed = rate.factor
                                                controller?.playbackParameters =
                                                    PlaybackParameters(rate.factor, 1f)
                                                speedMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        current?.let { message ->
                            ReactionRail(
                                onReact = ::sendReaction,
                                onMore = { emojiPicker = true },
                                onMenu = {
                                    selected = message
                                    dialog = "actions"
                                },
                                light = true,
                                onPickerPosition = { pickerOrigin = it },
                            )
                        }
                    }
                    if (mediaDuration > 0 && currentMessage?.state != "live") {
                        Slider(
                            value = position.toFloat().coerceIn(0f, mediaDuration.toFloat()),
                            onValueChange = {
                                seeking = true
                                completionEligible = false
                                position = it.toLong()
                            },
                            onValueChangeFinished = {
                                controller?.seekTo(position)
                                seeking = false
                            },
                            valueRange = 0f..mediaDuration.toFloat(),
                            modifier =
                                Modifier.semantics { contentDescription = "Playback position" },
                        )
                    }
                    playbackError?.let {
                        Text(it, color = Color(0xFFFFB4AB))
                        OutlinedButton(
                            onClick = {
                                controller?.prepare()
                                controller?.play()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                        ) {
                            Text("Retry playback")
                        }
                    }
                }
            } else if (unavailable) {
                Text(
                    "This message is no longer available.",
                    color = Color(0xFFFFB4AB),
                    modifier = Modifier.padding(16.dp),
                )
            }
        },
        footer = {
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.connectionError?.let { ConnectionNotice(it, vm::refresh) }
                if (pendingDrafts.isNotEmpty())
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                    ) {
                        DraftStrip(
                            pendingDrafts,
                            onPreview = { previewDraft = it },
                            onSend = { vm.run { vm.repo.enqueue(it) } },
                            onDiscard = { discardDraft = it },
                            light = true,
                        )
                    }
                MessageFilmstrip(
                    queue,
                    current?.id,
                    vm.repo.session.baseUrl,
                    vm.repo.session.token,
                    onSelect = { id ->
                        vm.replyMode(false)
                        itemFinished = false
                        advanced = false
                        completionReported = false
                        completionEligible = false
                        if (id == current?.id) {
                            position = 0
                            controller?.seekTo(0)
                            controller?.play()
                        }
                        onFocus(id)
                    },
                    onEarlier = vm::loadOlder,
                    hasOlder = !savedOnly && state.hasOlder,
                    hasNewer = !savedOnly && state.hasNewer,
                    loadingOlder = state.loadingOlder,
                    loadingNewer = state.loadingNewer,
                    onLater = vm::loadNewer,
                    loadThumbnails =
                        !playable ||
                            chrome == WatchChrome.REPLY ||
                            (mediaState == Player.STATE_READY &&
                                (current?.kind != "video" || videoRevealed)),
                )
                if (chrome == WatchChrome.REPLY)
                    ReplyComposer(
                        vm,
                        state,
                        onRecord = onRecord,
                        onLive = onLive,
                        light = true,
                        onComposing = { composing = it },
                    )
            }
        },
    )
    ConversationSheets(
        vm,
        state,
        dialog,
        selected,
        note,
        onNote = { note = it },
        onDialog = { dialog = it },
        previewDraft = previewDraft,
        discardDraft = discardDraft,
        onPreviewDraft = { previewDraft = it },
        onDiscardDraft = { discardDraft = it },
    )
    if (emojiPicker && current != null)
        EmojiPickerSheet(
            onPick = {
                sendReaction(it)
                emojiPicker = false
            },
            onDismiss = { emojiPicker = false },
        )
}

private fun playerMediaItem(
    message: Message,
    source: String,
    conversationTitle: String,
): MediaItem =
    MediaItem.Builder()
        .setMediaId(message.id)
        .setUri(source)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("A message from ${message.authorName}")
                .setExtras(
                    android.os.Bundle().apply {
                        putSerializable(
                            "genoa_message",
                            message.copy(transcript = null, summary = null, reactions = emptyList()),
                        )
                    }
                )
                .setArtist(conversationTitle)
                .build()
        )
        .build()
