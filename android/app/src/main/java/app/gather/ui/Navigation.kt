package app.gather.ui

import android.content.ComponentName
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.gather.media.PlaybackService
import kotlinx.coroutines.delay

@Composable
fun GenoaNavigation(
    profile: Boolean,
    onHome: () -> Unit,
    onRecord: () -> Unit,
    onProfile: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onHome,
                Modifier.size(56.dp)
                    .clip(CircleShape)
                    .background(
                        if (!profile) MaterialTheme.colorScheme.primaryContainer
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .semantics { selected = !profile },
            ) {
                GenoaIcon(
                    "home",
                    "Home",
                    Modifier.size(32.dp),
                    if (!profile) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(
                onRecord,
                Modifier.size(64.dp),
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = Capture,
                        contentColor = AvatarInk,
                    ),
            ) {
                GenoaIcon("video", "Record", Modifier.size(32.dp))
            }
            IconButton(
                onProfile,
                Modifier.size(56.dp)
                    .clip(CircleShape)
                    .background(
                        if (profile) MaterialTheme.colorScheme.primaryContainer
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .semantics { selected = profile },
            ) {
                GenoaIcon(
                    "profile",
                    "Profile",
                    Modifier.size(32.dp),
                    if (profile) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ConnectionNotice(message: String, retry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(retry) { Text("Retry connection") }
        }
    }
}

@Composable
fun PlaybackMiniPlayer(onOpen: (app.gather.data.Message) -> Unit) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var title by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var mediaId by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        val future =
            MediaController.Builder(
                    context,
                    SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                )
                .buildAsync()
        var disposed = false
        future.addListener(
            { if (!disposed) runCatching { controller = future.get() } },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            MediaController.releaseFuture(future)
        }
    }
    LaunchedEffect(controller) {
        while (true) {
            val item = controller?.currentMediaItem
            mediaId = item?.mediaId.orEmpty()
            title = item?.mediaMetadata?.title?.toString().orEmpty()
            playing = controller?.isPlaying == true
            delay(250)
        }
    }
    if (mediaId.isNotBlank())
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    {
                        val extras = controller?.currentMediaItem?.mediaMetadata?.extras
                        extras?.classLoader = app.gather.data.Message::class.java.classLoader
                        @Suppress("DEPRECATION")
                        val message =
                            extras?.getSerializable("genoa_message") as? app.gather.data.Message
                        message?.let(onOpen)
                    },
                    Modifier.weight(1f),
                ) {
                    Text(title.ifBlank { "Message playback" }, maxLines = 2)
                }
                IconButton({ if (playing) controller?.pause() else controller?.play() }) {
                    if (playing)
                        Icon(androidx.compose.material.icons.Icons.Outlined.Pause, "Pause playback")
                    else GenoaIcon("play", "Resume playback")
                }
                IconButton({
                    controller?.stop()
                    controller?.clearMediaItems()
                }) {
                    GenoaIcon("close", "Close playback")
                }
            }
        }
}
