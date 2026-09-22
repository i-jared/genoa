package app.gather.ui

import app.gather.data.Reaction

data class ReactionCard(val reaction: Reaction, val expiresAt: Long)

/** Playback time triggers cards; elapsed real time dismisses them even while paused. */
class ReactionTimeline {
    private val seen = mutableSetOf<String>()
    private val waiting = ArrayDeque<Reaction>()
    private val cards = mutableListOf<ReactionCard>()
    private var previousPosition: Long? = null

    fun update(
        reactions: List<Reaction>,
        selfId: String?,
        positionMs: Long,
        now: Long,
        seeking: Boolean = false,
    ): List<ReactionCard> {
        val previous = previousPosition
        if (seeking || (previous != null && positionMs < previous - 500)) {
            seen.clear()
            waiting.clear()
            cards.clear()
        }
        if (!seeking) {
            val lower =
                if (previous != null && positionMs >= previous && positionMs - previous < 2_000)
                    previous
                else (positionMs - 500).coerceAtLeast(0)
            reactions
                .sortedWith(
                    compareBy<Reaction> { it.positionMs }.thenBy { it.createdAt }.thenBy { it.id }
                )
                .filter { it.userId != selfId && it.positionMs in lower..positionMs }
                .forEach { reaction ->
                    if (seen.add(reaction.id) && waiting.size < 32) waiting.addLast(reaction)
                }
        }
        previousPosition = positionMs
        cards.removeAll { now >= it.expiresAt }
        while (cards.size < 3 && waiting.isNotEmpty()) {
            val expires = maxOf(now + 2_800, (cards.lastOrNull()?.expiresAt ?: 0) + 400)
            cards.add(ReactionCard(waiting.removeFirst(), expires))
        }
        return cards.toList()
    }
}
