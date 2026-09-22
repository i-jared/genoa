package app.gather.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.gather.data.Draft
import java.io.File

@Composable
fun DraftPreview(draft: Draft, onClose: () -> Unit) {
    val context = LocalContext.current
    val player =
        remember(draft.id) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(draft.file))))
                prepare()
            }
        }
    DisposableEffect(player) { onDispose { player.release() } }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text("Private preview", style = MaterialTheme.typography.titleLarge)
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player } },
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(if (draft.kind == "audio") 160.dp else 280.dp),
                )
                TextButton(onClose, Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}
