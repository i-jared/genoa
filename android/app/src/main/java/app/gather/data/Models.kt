package app.gather.data

import org.json.JSONObject

data class Contact(val id: String, val name: String)

data class Person(
    val id: String,
    val name: String,
    val email: String,
    val aiConsent: Boolean,
    val notifications: Boolean,
    val aiAvailable: Boolean,
)

data class Conversation(
    val id: String,
    val title: String,
    val members: Int,
    val unread: Int,
    val favorite: Boolean,
    val muted: Boolean,
    val preview: String,
    val activity: Long,
    val unwatched: Int = 0,
)

data class Reaction(
    val userId: String,
    val emoji: String,
    val positionMs: Long = 0,
    val id: String = "",
    val userName: String = "Someone",
    val createdAt: Long = 0,
) : java.io.Serializable

data class Message(
    val id: String,
    val seq: Long,
    val conversationId: String,
    val authorId: String,
    val authorName: String,
    val kind: String,
    val text: String,
    val state: String,
    val assetId: String?,
    val mime: String,
    val durationMs: Long,
    val createdAt: Long,
    val positionMs: Long,
    val watched: Boolean,
    val bookmarked: Boolean,
    val replyTo: String?,
    val urgent: Boolean,
    val reactions: List<Reaction>,
    val transcript: String?,
    val summary: String?,
    val aiStatus: String,
    val liveRotation: Int = 0,
) : java.io.Serializable

enum class DraftState {
    SAVED,
    QUEUED,
    PROCESSING,
    BLOCKED,
    UPLOADING,
    FAILED,
    SENT,
}

data class Draft(
    val id: String,
    val conversationId: String,
    val file: String,
    val kind: String,
    val mime: String,
    val durationMs: Long,
    val replyTo: String?,
    val state: DraftState,
    val percent: Int,
    val error: String?,
    val createdAt: Long,
    val ownerId: String = "",
)

fun JSONObject.nullable(key: String): String? =
    if (isNull(key) || !has(key)) null else getString(key)

fun JSONObject.message(): Message {
    val array = optJSONArray("reactions")
    val reactions =
        if (array == null) emptyList()
        else
            (0 until array.length()).map { index ->
                val reaction = array.getJSONObject(index)
                Reaction(
                    reaction.getString("user_id"),
                    reaction.getString("emoji"),
                    reaction.optLong("position_ms"),
                    reaction.optString("id", "${reaction.getString("user_id")}:$index"),
                    reaction.optString("user_name", "Someone"),
                    reaction.optLong("created_at"),
                )
            }
    return Message(
        id = getString("id"),
        seq = getLong("seq"),
        conversationId = getString("conversation_id"),
        authorId = getString("author_id"),
        authorName = getString("author_name"),
        kind = getString("kind"),
        text = optString("text"),
        state = getString("state"),
        assetId = nullable("asset_id"),
        mime = optString("mime"),
        durationMs = optLong("duration_ms"),
        createdAt = getLong("created_at"),
        positionMs = optLong("position_ms"),
        watched = optInt("watched") == 1,
        bookmarked = optInt("bookmarked") == 1,
        replyTo = nullable("reply_to"),
        urgent = optInt("urgent") == 1,
        reactions = reactions,
        transcript = nullable("transcript"),
        summary = nullable("summary"),
        aiStatus = optString("ai_status"),
        liveRotation = optInt("live_rotation"),
    )
}
