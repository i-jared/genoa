package app.gather.ui

import app.gather.data.Message
import app.gather.data.Reaction
import app.gather.data.WatchPage
import app.gather.data.mergeWatchPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchTest {
    @Test
    fun playbackProgressUsesDecodedDurationAndFinishesAtTheEnd() {
        assertEquals(WatchProgress(2_000, 4_200), watchProgress(2_000, 4_200, 9_000, false))
        assertEquals(WatchProgress(4_200, 4_200), watchProgress(4_000, 4_200, 9_000, true))
        assertEquals(WatchProgress(4_200, 4_200), watchProgress(5_000, 4_200, 9_000, false))
    }

    @Test
    fun playbackProgressHandlesUnknownDurationWithoutNegativePositions() {
        assertEquals(WatchProgress(0, 9_000), watchProgress(-1, Long.MIN_VALUE, 9_000, false))
        assertEquals(WatchProgress(500, 0), watchProgress(500, Long.MIN_VALUE, 0, false))
        assertEquals(WatchProgress(9_000, 9_000), watchProgress(500, Long.MIN_VALUE, 9_000, true))
    }

    @Test
    fun textDwellGrowsWithWordsAndStaysBounded() {
        assertEquals(3_000L, textDwellMs("Hi"))
        assertEquals(4_000L, textDwellMs("one two three four five"))
        assertEquals(8_000L, textDwellMs((1..40).joinToString(" ") { "word" }))
    }

    @Test
    fun voiceAndVideoPlayToTheirEnd() {
        assertNull(message(kind = "audio").watchAdvanceMs())
        assertNull(message(kind = "video").watchAdvanceMs())
        assertEquals(PHOTO_DWELL_MS, message(kind = "photo").watchAdvanceMs())
    }

    @Test
    fun watchStartsAtFirstUnwatchedIncomingThenLast() {
        val own = message("own", author = "me", watched = true)
        val unread = message("new", author = "rosa", watched = false)
        val later = message("later", author = "rosa", watched = true)
        assertEquals(1, startingWatchIndex(listOf(own, unread, later), "me"))
        assertEquals(1, startingWatchIndex(listOf(own, later.copy(id = "done")), "me"))
        assertEquals(-1, startingWatchIndex(emptyList(), "me"))
    }

    @Test
    fun authorLabelOnlyWhenTheSenderChanges() {
        val first = message("1", author = "rosa")
        val second = message("2", author = "rosa")
        val third = message("3", author = "me")
        val queue = listOf(first, second, third)
        assertEquals(true, showWatchAuthor(queue, 0))
        assertEquals(false, showWatchAuthor(queue, 1))
        assertEquals(true, showWatchAuthor(queue, 2))
    }

    @Test
    fun nextWatchMessageStopsAtTheEnd() {
        val first = message("1")
        val second = message("2")
        assertEquals("2", nextWatchMessage(listOf(first, second), "1")?.id)
        assertNull(nextWatchMessage(listOf(first, second), "2"))
        assertNull(nextWatchMessage(listOf(first, second), "missing"))
    }

    @Test
    fun watchChromeShowsReplyAfterTheLastItemEnds() {
        val first = message("1")
        val second = message("2")
        assertEquals(WatchChrome.PLAYING, watchChrome(listOf(first, second), "1", true))
        assertEquals(WatchChrome.PLAYING, watchChrome(listOf(first, second), "2", false))
        assertEquals(WatchChrome.REPLY, watchChrome(listOf(first, second), "2", true))
        assertEquals(WatchChrome.REPLY, watchChrome(emptyList(), null, false))
    }

    @Test
    fun timedReactionsAppearOnlyInTheirWindow() {
        val reactions =
            listOf(
                Reaction("u1", "❤️", 0),
                Reaction("u2", "😂", 1_500),
                Reaction("u3", "🔥", 4_000),
            )
        assertEquals(listOf("❤️"), timedReactionsDue(reactions, 200).map { it.emoji })
        assertEquals(listOf("❤️", "😂"), timedReactionsDue(reactions, 1_600).map { it.emoji })
        assertEquals(listOf("😂"), timedReactionsDue(reactions, 2_600).map { it.emoji })
        assertEquals(listOf("🔥"), timedReactionsDue(reactions, 4_100).map { it.emoji })
        assertEquals(emptyList<String>(), timedReactionsDue(reactions, 6_600).map { it.emoji })
    }

    @Test fun refreshedWindowRemovesDeletedMessagesWithoutDroppingOlderPages() {
        val messages = (1..80).map { message(it.toString()).copy(seq = it.toLong()) }
        val refreshed = messages.filter { it.seq in 30..60 && it.seq != 42L }.map { it.copy(watched = true) }
        val merged = mergeWatchPage(messages, WatchPage(refreshed, null, true, true, 30, 60))
        assertEquals(79, merged.size)
        assertEquals("1", merged.first().id)
        assertEquals("80", merged.last().id)
        assertEquals(false, merged.any { it.id == "42" })
        assertEquals(true, merged.first { it.id == "40" }.watched)
    }

    @Test fun olderPagesMergeWithoutDuplicatesOrMovingTheFocusedMessage() {
        val messages = (51..80).map { message(it.toString()).copy(seq = it.toLong()) }
        val older = (1..50).map { message(it.toString()).copy(seq = it.toLong()) }
        val page = WatchPage(older, null, false, true, 1, 50)
        val merged = mergeWatchPage(mergeWatchPage(messages, page), page)
        assertEquals((1..80).map { it.toLong() }, merged.map { it.seq })
        assertEquals("80", merged.find { it.id == "80" }?.id)
    }

    private fun message(
        id: String = "m",
        author: String = "rosa",
        kind: String = "video",
        watched: Boolean = false,
        text: String = "",
    ) =
        Message(
            id = id,
            seq = 1,
            conversationId = "c",
            authorId = author,
            authorName = author,
            kind = kind,
            text = text,
            state = "ready",
            assetId = if (kind == "text") null else "asset",
            mime = "",
            durationMs = 1_000,
            createdAt = 0,
            positionMs = 0,
            watched = watched,
            bookmarked = false,
            replyTo = null,
            urgent = false,
            reactions = emptyList(),
            transcript = null,
            summary = null,
            aiStatus = "",
        )
}
