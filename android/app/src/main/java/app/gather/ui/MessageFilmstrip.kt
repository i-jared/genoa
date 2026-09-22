package app.gather.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.gather.data.Message
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun MessageFilmstrip(
    messages: List<Message>,
    currentId: String?,
    baseUrl: String,
    token: String?,
    onSelect: (String) -> Unit,
    onEarlier: () -> Unit,
    hasOlder: Boolean = false,
    hasNewer: Boolean = false,
    loadingOlder: Boolean = false,
    loadingNewer: Boolean = false,
    onLater: () -> Unit = {},
    loadThumbnails: Boolean = true,
) {
    if (messages.isEmpty()) return
    val initialIndex = (messages.indexOfFirst { it.id == currentId } + 1).coerceAtLeast(0)
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    // Keep the same keyed thumbnail anchored when pages arrive. Only an explicit
    // focus change (selection or playback advance) moves the strip to a message.
    LaunchedEffect(currentId) {
        val index = messages.indexOfFirst { it.id == currentId }
        if (index >= 0) scroll.scrollToItem(index + 1)
    }
    val earlier by rememberUpdatedState(onEarlier)
    val later by rememberUpdatedState(onLater)
    val canLoadOlder by rememberUpdatedState(hasOlder && !loadingOlder)
    val canLoadNewer by rememberUpdatedState(hasNewer && !loadingNewer)
    LaunchedEffect(scroll) {
        snapshotFlow {
                Triple(
                    scroll.isScrollInProgress,
                    scroll.firstVisibleItemIndex,
                    (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >=
                        scroll.layoutInfo.totalItemsCount - 2,
                )
            }
            .distinctUntilChanged()
            .collect { (scrolling, first, atEnd) ->
                if (scrolling && first <= 1 && canLoadOlder) earlier()
                if (scrolling && atEnd && canLoadNewer) later()
            }
    }
    LazyRow(
        state = scroll,
        modifier =
            Modifier.fillMaxWidth().testTag("message-filmstrip").semantics {
                contentDescription = "Conversation messages"
            },
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "earlier") {
            if (loadingOlder) MediaLoading("Loading…")
            else if (hasOlder) TextButton(onEarlier) { Text("Earlier") }
            else Spacer(Modifier.width(1.dp))
        }
        items(messages, key = { it.id }) { message ->
            MediaThumbnail(
                message,
                baseUrl,
                token,
                Modifier.size(56.dp, 72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (message.id == currentId)
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(12.dp),
                            )
                        else Modifier
                    )
                    .semantics { selected = message.id == currentId },
                onClick = { onSelect(message.id) },
                loadRemoteMedia = loadThumbnails,
            )
        }
        item(key = "later") {
            if (loadingNewer) MediaLoading("Loading…")
            else if (hasNewer) TextButton(onLater) { Text("Later") }
        }
    }
}
