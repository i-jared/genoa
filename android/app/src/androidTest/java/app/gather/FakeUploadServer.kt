package app.gather

import app.gather.data.ApiException
import app.gather.data.UploadTransport
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject

/**
 * An in-memory stand-in for the Worker's upload, message, and live routes. It models the server
 * state that matters for discard races: whether a message exists and whether it was published.
 */
class FakeUploadServer(private val chunkSize: Int = 4) : UploadTransport {
    enum class Message { UPLOADING, READY, LIVE, DELETED }

    val messages: MutableMap<String, Message> = Collections.synchronizedMap(mutableMapOf())
    val parts: MutableMap<String, MutableSet<Int>> = Collections.synchronizedMap(mutableMapOf())
    val segments: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var offline = false
    /** Every request answers with this status once set. */
    @Volatile var failWith: Int? = null

    private val holds = Collections.synchronizedMap(mutableMapOf<String, Hold>())
    private val losses = Collections.synchronizedSet(mutableSetOf<String>())

    class Hold(val afterApply: Boolean) {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    /** Pauses the next matching request, before or after the server applies it. */
    fun hold(request: String, afterApply: Boolean = false) = Hold(afterApply).also { holds[request] = it }

    /** The server applies the next matching request, but its response never arrives. */
    fun loseResponse(request: String) {
        losses += request
    }

    fun live(id: String) = messages[id] in listOf(Message.UPLOADING, Message.READY, Message.LIVE)

    override suspend fun call(method: String, path: String, body: JSONObject?): String =
        exchange("$method $path") { apply(method, path, body) }

    override suspend fun putBytes(path: String, data: ByteArray) {
        exchange("PUT $path") { apply("PUT", path, null) }
    }

    private suspend fun exchange(request: String, apply: () -> String): String {
        requests += request
        if (offline) throw IOException("offline")
        failWith?.let { throw ApiException(it, "status $it") }
        val hold = holds.remove(request)
        if (hold != null && !hold.afterApply) {
            hold.reached.complete(Unit)
            hold.release.await()
        }
        val response = apply()
        if (hold != null && hold.afterApply) {
            hold.reached.complete(Unit)
            hold.release.await()
        }
        if (losses.remove(request)) throw IOException("connection reset after the server applied it")
        return response
    }

    private fun apply(method: String, path: String, body: JSONObject?): String {
        val segmentsPath = Regex("/api/live/([^/]+)/segments/(\\d+).*").matchEntire(path)
        return when {
            method == "POST" && path == "/api/uploads" -> {
                val id = body!!.getString("id")
                when (messages[id]) {
                    Message.DELETED -> throw ApiException(404, "Message unavailable")
                    Message.READY -> JSONObject().put("state", "complete").put("chunk_size", chunkSize).toString()
                    else -> {
                        if (messages[id] == null) messages[id] = Message.UPLOADING
                        parts.getOrPut(id) { mutableSetOf() }
                        JSONObject().put("state", "open").put("chunk_size", chunkSize).toString()
                    }
                }
            }
            method == "GET" && path.startsWith("/api/uploads/") -> {
                val id = path.removePrefix("/api/uploads/")
                if (!live(id)) throw ApiException(404, "Upload unavailable")
                val done = JSONArray()
                parts[id].orEmpty().forEach { done.put(JSONObject().put("number", it)) }
                JSONObject().put("state", "open").put("parts", done).toString()
            }
            method == "PUT" && path.startsWith("/api/uploads/") -> {
                val id = path.removePrefix("/api/uploads/").substringBefore("/")
                if (!live(id)) throw ApiException(404, "Upload unavailable")
                parts.getOrPut(id) { mutableSetOf() } += path.substringAfterLast("/").toInt()
                "{}"
            }
            method == "POST" && path.endsWith("/complete") -> {
                val id = path.removePrefix("/api/uploads/").removeSuffix("/complete")
                if (!live(id)) throw ApiException(404, "Upload unavailable")
                messages[id] = Message.READY
                JSONObject().put("state", "ready").toString()
            }
            method == "POST" && path.startsWith("/api/conversations/") && path.endsWith("/messages") -> {
                val id = body!!.getString("id")
                if (messages[id] == Message.DELETED) throw ApiException(409, "Message ID already exists")
                messages[id] = Message.READY
                "{}"
            }
            method == "DELETE" && path.startsWith("/api/messages/") -> {
                val id = path.removePrefix("/api/messages/")
                if (!live(id)) throw ApiException(404, "Message unavailable")
                messages[id] = Message.DELETED
                "{}"
            }
            method == "PUT" && segmentsPath != null -> {
                val id = segmentsPath.groupValues[1]
                if (messages[id] != Message.LIVE) throw ApiException(409, "This recording has ended")
                segments += segmentsPath.groupValues[2].toInt()
                "{}"
            }
            method == "POST" && path.startsWith("/api/live/") && path.endsWith("/end") -> {
                val id = path.removePrefix("/api/live/").removeSuffix("/end")
                if (messages[id] == Message.LIVE) messages[id] = Message.READY
                "{}"
            }
            else -> throw ApiException(404, "Not found")
        }
    }
}
