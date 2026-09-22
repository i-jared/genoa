package app.gather.data

data class WatchPage(
    val messages: List<Message>,
    val focusId: String?,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    val startSeq: Long,
    val endSeq: Long,
)

/** Replace the refreshed interval, including deletions, without dropping loaded history. */
fun mergeWatchPage(existing: List<Message>, page: WatchPage): List<Message> =
    (existing.filter { it.seq < page.startSeq || it.seq > page.endSeq } + page.messages)
        .distinctBy { it.id }
        .sortedBy { it.seq }
