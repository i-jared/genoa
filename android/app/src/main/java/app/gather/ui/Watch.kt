package app.gather.ui

import app.gather.data.Message
import app.gather.data.Reaction

enum class WatchKind {
    VIDEO,
    VOICE,
    PHOTO,
    TEXT,
}

const val PHOTO_DWELL_MS = 4_000L

data class WatchProgress(val positionMs: Long, val durationMs: Long)

fun watchProgress(
    positionMs: Long,
    playerDurationMs: Long,
    messageDurationMs: Long,
    ended: Boolean,
): WatchProgress {
    val duration =
        if (playerDurationMs > 0) playerDurationMs else messageDurationMs.coerceAtLeast(0)
    val position =
        if (duration > 0) {
            if (ended) duration else positionMs.coerceIn(0, duration)
        } else positionMs.coerceAtLeast(0)
    return WatchProgress(position, duration)
}

fun Message.watchKind(): WatchKind =
    when (kind) {
        "audio" -> WatchKind.VOICE
        "photo" -> WatchKind.PHOTO
        "text" -> WatchKind.TEXT
        else -> WatchKind.VIDEO
    }

fun Message.watchAdvanceMs(): Long? =
    when (watchKind()) {
        WatchKind.VIDEO,
        WatchKind.VOICE -> null
        WatchKind.PHOTO -> PHOTO_DWELL_MS
        WatchKind.TEXT -> textDwellMs(text)
    }

fun textDwellMs(text: String): Long {
    val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
    return (2_000L + words * 400L).coerceIn(3_000L, 8_000L)
}

fun startingWatchIndex(messages: List<Message>, selfId: String?): Int {
    if (messages.isEmpty()) return -1
    val firstUnwatched = messages.indexOfFirst { !it.watched && it.authorId != selfId }
    return if (firstUnwatched >= 0) firstUnwatched else messages.lastIndex
}

fun showWatchAuthor(messages: List<Message>, index: Int): Boolean {
    if (index !in messages.indices) return false
    return index == 0 || messages[index].authorId != messages[index - 1].authorId
}

enum class WatchChrome {
    PLAYING,
    REPLY,
}

fun watchChrome(messages: List<Message>, currentId: String?, itemFinished: Boolean): WatchChrome {
    if (messages.isEmpty()) return WatchChrome.REPLY
    if (!itemFinished || currentId == null) return WatchChrome.PLAYING
    return if (nextWatchMessage(messages, currentId) == null) WatchChrome.REPLY
    else WatchChrome.PLAYING
}

fun nextWatchMessage(messages: List<Message>, currentId: String): Message? {
    val index = messages.indexOfFirst { it.id == currentId }
    if (index < 0) return null
    return messages.getOrNull(index + 1)
}

fun timedReactionsDue(
    reactions: List<Reaction>,
    positionMs: Long,
    windowMs: Long = 2_500L,
): List<Reaction> =
    reactions
        .filter { positionMs >= it.positionMs && positionMs < it.positionMs + windowMs }
        .sortedBy { it.positionMs }
