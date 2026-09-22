package app.gather.data

data class LocalWatchProgress(
    val account: String,
    val messageId: String,
    val conversationId: String,
    val positionMs: Long,
    val watched: Boolean,
    val revision: String,
    val dirty: Boolean = true,
)

fun applyWatchProgress(messages: List<Message>, progress: List<LocalWatchProgress>, cached: Boolean = false): List<Message> {
    val byId = progress.associateBy { it.messageId }
    return messages.map { message ->
        val local = byId[message.id] ?: return@map message
        message.copy(
            positionMs = if (local.dirty || cached) local.positionMs else message.positionMs,
            watched = message.watched || local.watched,
        )
    }
}
