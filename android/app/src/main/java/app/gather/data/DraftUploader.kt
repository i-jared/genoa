package app.gather.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** The authenticated HTTP operations delivery needs; tests substitute an in-memory server. */
interface UploadTransport {
    /** Returns the response body; throws [ApiException] for HTTP errors and IOException offline. */
    suspend fun call(method: String, path: String, body: JSONObject? = null): String

    suspend fun putBytes(path: String, data: ByteArray)
}

sealed interface UploadOutcome {
    /** The server accepted the message. */
    data object Sent : UploadOutcome

    /** The draft was discarded or removed; nothing more may be written for it. */
    data object Stopped : UploadOutcome

    /** A temporary problem; the same stable ID is safe to retry. */
    data object Retry : UploadOutcome

    /** Retrying will not help until the person acts; the reason is shown on the draft. */
    data object Terminal : UploadOutcome
}

/** How an upload failure is presented and whether it is retried automatically. */
data class UploadFailure(val state: DraftState, val message: String, val retry: Boolean)

fun classifyUploadFailure(error: Throwable): UploadFailure {
    if (error !is ApiException)
        return UploadFailure(DraftState.FAILED, "Upload paused. Your recording is saved.", true)
    return when {
        error.accountSuspended ->
            UploadFailure(DraftState.FAILED, error.message ?: SUSPENDED_FALLBACK, false)
        error.status == 401 ->
            UploadFailure(DraftState.FAILED, "Sign in again to finish sending. Your recording is saved.", true)
        error.status in listOf(403, 404) ->
            UploadFailure(
                DraftState.BLOCKED,
                "You can’t send to this conversation anymore. Your recording is saved.",
                false,
            )
        // Storage quota (413), unplayable media (422), and rejected requests will fail the same
        // way again; show the server's explanation and let the person retry or discard.
        error.status == 413 ->
            UploadFailure(
                DraftState.FAILED,
                error.message?.takeIf { it.isNotBlank() && it != "Request failed" }
                    ?: "Your Genoa storage is full. Your recording is saved on this phone.",
                false,
            )
        error.status == 422 ->
            UploadFailure(
                DraftState.FAILED,
                error.message?.takeIf { it.isNotBlank() && it != "Request failed" }
                    ?: "This file couldn’t be played. Discard it or choose another file.",
                false,
            )
        error.status == 429 || error.status >= 500 || error.status == 408 ->
            UploadFailure(DraftState.FAILED, "Upload paused. Your recording is saved.", true)
        else ->
            UploadFailure(
                DraftState.FAILED,
                error.message ?: "Upload paused. Your recording is saved.",
                false,
            )
    }
}

/**
 * Owns delivery of one draft at a time per stable ID, and discard of drafts.
 *
 * Discard is ordered: persist a tombstone, stop scheduled and in-process work, delete local media,
 * then reconcile server deletion. Every write made here is conditional on the draft not being
 * discarded, and every network step first checks the tombstone.
 */
class DraftUploader(
    private val drafts: DraftStore,
    private val transport: UploadTransport,
    private val onSent: (Draft) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val active = ConcurrentHashMap<String, Deferred<UploadOutcome>>()

    fun isActive(id: String) = active[id]?.isActive == true

    suspend fun upload(id: String, ownerId: String): UploadOutcome = coroutineScope {
        val job = async(Dispatchers.IO) { deliver(id, ownerId) }
        active[id] = job
        try {
            job.await()
        } catch (e: CancellationException) {
            // Discard cancelled only this delivery; a WorkManager stop cancels the caller too.
            currentCoroutineContext().ensureActive()
            UploadOutcome.Stopped
        } finally {
            active.remove(id, job)
        }
    }

    /**
     * Makes the discard durable and stops local work. [cancelScheduled] cancels queued work and
     * must return only when that cancellation is recorded. Remote deletion is [reconcileDiscards].
     */
    suspend fun discard(id: String, cancelScheduled: suspend () -> Unit = {}): Draft? {
        val previous = withContext(Dispatchers.IO) { drafts.markDiscarded(id, now()) }
        cancelScheduled()
        stop(id)
        withContext(Dispatchers.IO) { previous?.let { deleteDraftMedia(it.file) } }
        return previous
    }

    /** Cancels an in-process delivery and waits for it; aborted HTTP calls end promptly. */
    suspend fun stop(id: String) {
        val job = active[id] ?: return
        withTimeoutOrNull(STOP_TIMEOUT_MS) { job.cancelAndJoin() }
    }

    /**
     * Deletes discarded messages on the server. A tombstone is removed only after a DELETE issued
     * once the settle window has passed returns 200 or 404: before that, a request sent just
     * before discard could still reach the server and create the message after a 404.
     * Returns true when nothing remains to reconcile for [ownerId].
     */
    suspend fun reconcileDiscards(ownerId: String): Boolean {
        var pending = false
        for (tombstone in withContext(Dispatchers.IO) { drafts.discarded() }) {
            if (tombstone.ownerId != ownerId) continue
            withContext(Dispatchers.IO) { deleteDraftMedia(tombstone.file) }
            if (isActive(tombstone.id)) {
                pending = true
                continue
            }
            val settled = now() >= tombstone.discardedAt + SETTLE_MS
            val confirmed =
                try {
                    transport.call("DELETE", "/api/messages/${tombstone.id}")
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    // 404: nothing exists. 403 without suspension: not ours to delete, so no
                    // further request from this phone can change it.
                    e.status == 404 || (e.status == 403 && !e.accountSuspended)
                } catch (_: IOException) {
                    false
                }
            if (confirmed && settled) withContext(Dispatchers.IO) { drafts.remove(tombstone.id) }
            else pending = true
        }
        return !pending
    }

    private suspend fun deliver(id: String, ownerId: String): UploadOutcome {
        val draft = drafts.get(id) ?: return UploadOutcome.Stopped
        if (draft.state == DraftState.SENT) {
            onSent(draft)
            return UploadOutcome.Sent
        }
        if (draft.ownerId != ownerId) return UploadOutcome.Terminal
        val file = File(draft.file)
        var current = draft
        suspend fun live() {
            currentCoroutineContext().ensureActive()
            if (drafts.isStopped(id)) throw DraftStopped()
        }
        fun write(next: Draft) {
            if (!drafts.update(next)) throw DraftStopped()
            current = next
        }
        try {
            live()
            if (draft.kind == "text") {
                val payload = JSONObject(file.readText()).put("id", id)
                live()
                transport.call("POST", "/api/conversations/${draft.conversationId}/messages", payload)
                // Accepted. A discard that won the race keeps its tombstone for reconciliation.
                if (!drafts.removeUnlessDiscarded(id)) return UploadOutcome.Stopped
                file.delete()
                return UploadOutcome.Sent
            }
            write(current.copy(state = DraftState.UPLOADING, error = null))
            live()
            val setup =
                JSONObject(
                    transport.call(
                        "POST",
                        "/api/uploads",
                        json(
                                "id" to id,
                                "conversation_id" to draft.conversationId,
                                "kind" to draft.kind,
                                "mime" to draft.mime,
                                "bytes" to file.length(),
                                "duration_ms" to draft.durationMs,
                            )
                            .apply { draft.replyTo?.let { put("reply_to", it) } },
                    )
                )
            if (setup.optString("state") != "complete") {
                live()
                val status = JSONObject(transport.call("GET", "/api/uploads/$id"))
                val done = status.getJSONArray("parts")
                val numbers =
                    (0 until done.length()).map { done.getJSONObject(it).getInt("number") }.toSet()
                val chunk = setup.getInt("chunk_size")
                require(chunk in 1..MAX_CHUNK) { "Unexpected upload part size" }
                RandomAccessFile(file, "r").use { input ->
                    var part = 1
                    while (input.filePointer < input.length()) {
                        val length = minOf(chunk.toLong(), input.length() - input.filePointer).toInt()
                        if (part in numbers) input.seek(input.filePointer + length)
                        else {
                            val bytes = ByteArray(length)
                            input.readFully(bytes)
                            live()
                            transport.putBytes("/api/uploads/$id/parts/$part", bytes)
                        }
                        write(
                            current.copy(
                                state = DraftState.UPLOADING,
                                percent = (input.filePointer * 100 / input.length()).toInt(),
                            )
                        )
                        part++
                    }
                }
                write(current.copy(state = DraftState.PROCESSING, percent = 100, error = null))
                live()
                transport.call("POST", "/api/uploads/$id/complete")
            }
            write(current.copy(state = DraftState.SENT, percent = 100, error = null))
            onSent(current)
            return UploadOutcome.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (_: DraftStopped) {
            return UploadOutcome.Stopped
        } catch (e: Exception) {
            val failure = classifyUploadFailure(e)
            // A lost final response lands here too; the stable ID makes the retry safe, and a
            // discard in the meantime leaves this write as a no-op.
            if (!drafts.update(current.copy(state = failure.state, error = failure.message)))
                return UploadOutcome.Stopped
            return if (failure.retry) UploadOutcome.Retry else UploadOutcome.Terminal
        }
    }

    private class DraftStopped : Exception()

    companion object {
        /** Longer than a server needs to finish a request that was fully sent before discard. */
        const val SETTLE_MS = 3 * 60 * 1000L
        private const val STOP_TIMEOUT_MS = 15_000L
        private const val MAX_CHUNK = 64 * 1024 * 1024
    }
}

/** Live drafts own a per-message directory; other drafts are single files. */
fun deleteDraftMedia(path: String) {
    val file = File(path)
    val parent = file.parentFile
    if (parent != null && parent.name.startsWith("live-")) parent.deleteRecursively()
    else file.delete()
}
