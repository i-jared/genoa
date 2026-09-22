package app.gather.ui

import app.gather.data.Reaction
import org.junit.Assert.*
import org.junit.Test

class ReactionTimelineTest {
    private fun reaction(id: String, position: Long = 100, user: String = "rosa") =
        Reaction(user, "❤️", position, id, "Rosa", id.toLong())

    @Test
    fun cardsExcludeSelfAndExpireInOrderEvenWhenPaused() {
        val timeline = ReactionTimeline()
        val reactions =
            listOf(
                reaction("1", user = "me"),
                reaction("2"),
                reaction("3"),
                reaction("4"),
                reaction("5"),
            )
        val first = timeline.update(reactions, "me", 100, 0)
        assertEquals(listOf("2", "3", "4"), first.map { it.reaction.id })
        assertEquals(
            listOf("3", "4", "5"),
            timeline.update(reactions, "me", 100, 2_800).map { it.reaction.id },
        )
        assertEquals(
            listOf("4", "5"),
            timeline.update(reactions, "me", 100, 3_200).map { it.reaction.id },
        )
        assertTrue(timeline.update(reactions, "me", 100, 7_000).isEmpty())
    }

    @Test
    fun pollingDoesNotDuplicateCardsAndRewindReplaysThem() {
        val timeline = ReactionTimeline()
        val reactions = listOf(reaction("1", 1000))
        assertTrue(timeline.update(reactions, "me", 900, 0).isEmpty())
        assertEquals(1, timeline.update(reactions, "me", 1100, 100).size)
        assertEquals(1, timeline.update(reactions, "me", 1300, 200).size)
        assertTrue(timeline.update(reactions, "me", 0, 300).isEmpty())
        assertEquals(1, timeline.update(reactions, "me", 1000, 400).size)
    }

    @Test
    fun seekingDoesNotFloodCardsFromSkippedMoments() {
        val timeline = ReactionTimeline()
        val reactions = listOf(reaction("1", 1000), reaction("2", 8000))
        timeline.update(reactions, "me", 0, 0)
        assertTrue(timeline.update(reactions, "me", 8000, 100, seeking = true).isEmpty())
        assertEquals(
            listOf("2"),
            timeline.update(reactions, "me", 8000, 200).map { it.reaction.id },
        )
    }
}
