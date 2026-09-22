package app.gather.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gather.data.Message
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, identity: String = name, size: Dp = 48.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(
                listOf(0xFFEBC19F, 0xFFACC8E5, 0xFFC6B3E2, 0xFFA2D3C5, 0xFFE8ADAE).let {
                    Color(it[Math.floorMod(identity.hashCode(), it.size)])
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name
                .trim()
                .split(" ")
                .filter { it.isNotBlank() }
                .take(if (LocalDensity.current.fontScale > 1.3f && size <= 48.dp) 1 else 2)
                .joinToString("") { it.take(1).uppercase() },
            style =
                if (size <= 32.dp) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.titleMedium,
            color = AvatarInk,
        )
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Forum,
            null,
            Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun Message.isPlayable(): Boolean = kind != "text" && state in listOf("ready", "live")

private val videoFrames = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}
private val thumbnailLoads = kotlinx.coroutines.sync.Semaphore(1)

@Composable
fun MediaThumbnail(
    message: Message,
    base: String,
    token: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    loadRemoteMedia: Boolean = true,
) {
    val context = LocalContext.current
    val label =
        when (message.kind) {
            "photo" -> "Photo from ${message.authorName}"
            "audio" -> "Voice message from ${message.authorName}"
            "text" -> "Text from ${message.authorName}"
            else ->
                if (message.state == "live") "Live video from ${message.authorName}"
                else "Video from ${message.authorName}"
        }
    val shape = RoundedCornerShape(10.dp)
    val clickable =
        if (onClick != null)
            Modifier.clickable(onClick = onClick).semantics { contentDescription = label }
        else Modifier.semantics { contentDescription = label }
    Box(
        modifier.clip(shape).background(CameraBackdrop).then(clickable),
        contentAlignment = Alignment.Center,
    ) {
        when {
            message.kind == "photo" && message.assetId != null && loadRemoteMedia ->
                AsyncImage(
                    ImageRequest.Builder(context)
                        .data("$base/api/media/${message.assetId}")
                        .addHeader("Authorization", "Bearer $token")
                        .build(),
                    label,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            message.kind == "video" && message.assetId != null -> {
                var frame by
                    remember(message.assetId) { mutableStateOf(videoFrames.get(message.assetId)) }
                LaunchedEffect(message.assetId, token, loadRemoteMedia) {
                    if (frame != null || !loadRemoteMedia) return@LaunchedEffect
                    frame =
                        withContext(Dispatchers.IO) {
                            thumbnailLoads.acquire()
                            try {
                                videoFrames.get(message.assetId)
                                    ?: runCatching {
                                            val retriever = MediaMetadataRetriever()
                                            try {
                                                retriever.setDataSource(
                                                    "$base/api/media/${message.assetId}",
                                                    mapOf("Authorization" to "Bearer $token"),
                                                )
                                                retriever.getScaledFrameAtTime(
                                                    0,
                                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                                    320,
                                                    320,
                                                )
                                            } finally {
                                                retriever.release()
                                            }
                                        }
                                        .getOrNull()
                                        ?.also { videoFrames.put(message.assetId, it) }
                            } finally { thumbnailLoads.release() }
                        }
                }
                if (frame != null)
                    Image(
                        frame!!.asImageBitmap(),
                        label,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                else GenoaIcon("play", null, Modifier.size(20.dp), Color(0xFFEDEFE9))
            }
            message.kind == "audio" ->
                Icon(Icons.Outlined.Mic, label, Modifier.size(20.dp), tint = Color(0xFFEDEFE9))
            message.kind == "text" ->
                Text(
                    message.text.ifBlank { "Note" },
                    Modifier.padding(6.dp),
                    color = Color(0xFFEDEFE9),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            else -> GenoaIcon("play", null, Modifier.size(20.dp), Color(0xFFEDEFE9))
        }
        if (message.kind == "video")
            Text(
                if (message.state == "live") "Live" else duration(message.durationMs),
                Modifier.align(Alignment.BottomEnd).padding(4.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
    }
}

@Composable
fun ReplyContext(
    source: Message,
    base: String,
    token: String?,
    modifier: Modifier = Modifier,
    light: Boolean = true,
    onOpen: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    val color = if (light) MaterialTheme.colorScheme.primary else Color(0xFFEDEFE9)
    val muted =
        if (light) MaterialTheme.colorScheme.onSurfaceVariant
        else Color(0xFFEDEFE9).copy(alpha = 0.82f)
    Row(
        modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (source.kind != "text")
            MediaThumbnail(source, base, token, Modifier.size(48.dp), onClick = onOpen)
        Column(Modifier.weight(1f)) {
            Text(
                "Reply to ${source.authorName}",
                color = color,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when (source.kind) {
                    "video" -> if (source.state == "live") "Live video" else "Video"
                    "audio" -> "Voice message"
                    "photo" -> "Photo"
                    else -> source.text.take(48).ifBlank { "Message" }
                },
                color = muted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
        onCancel?.let { IconButton(it) { GenoaIcon("close", "Cancel reply") } }
    }
}

fun duration(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

fun timeLabel(ms: Long): String =
    SimpleDateFormat(
            if (System.currentTimeMillis() - ms < 86400000) "h:mm a" else "MMM d",
            Locale.getDefault(),
        )
        .format(Date(ms))
