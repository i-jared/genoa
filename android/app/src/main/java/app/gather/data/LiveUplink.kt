package app.gather.data

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Local buffering limits while live segments wait for the network. */
data class LiveLimits(
    val maxPendingSegments: Int = 150,
    val maxPendingBytes: Long = 160L * 1024 * 1024,
    /** Keep room for the replay MP4, which grows alongside the segments. */
    val minFreeBytes: Long = 300L * 1024 * 1024,
)

enum class LiveOffer {
    ACCEPTED,
    /** The connection has been away too long to keep buffering. Stop capture; keep the replay. */
    BUFFER_FULL,
    /** The phone is nearly out of space. Stop capture; keep the replay. */
    LOW_STORAGE,
    /** Sharing already stopped for a terminal reason. */
    STOPPED,
}

fun liveOffer(limits: LiveLimits, pendingCount: Int, pendingBytes: Long, incomingBytes: Long, freeBytes: Long) =
    when {
        freeBytes < limits.minFreeBytes -> LiveOffer.LOW_STORAGE
        pendingCount + 1 > limits.maxPendingSegments ||
            pendingBytes + incomingBytes > limits.maxPendingBytes -> LiveOffer.BUFFER_FULL
        else -> LiveOffer.ACCEPTED
    }

fun liveOfferMessage(offer: LiveOffer) =
    when (offer) {
        LiveOffer.BUFFER_FULL ->
            "The live connection was away too long, so sharing stopped. Your recording is saved on this phone."
        LiveOffer.LOW_STORAGE ->
            "Your phone is almost out of storage, so sharing stopped. What you recorded is saved."
        else -> null
    }

/**
 * Errors that retrying a segment cannot fix. The recording itself remains on the phone and its
 * replay upload reports its own delivery state.
 */
fun liveTerminalMessage(error: Throwable): String? {
    if (error !is ApiException) return null
    if (error.accountSuspended) return error.message
    return when (error.status) {
        401 -> "Your session ended, so live sharing stopped. Your recording is saved on this phone."
        403 -> "You can’t share in this conversation anymore. Your recording is saved on this phone."
        404, 410 -> "This live message is no longer available. Your recording is saved on this phone."
        409 -> "Live sharing already ended on the server. Your recording is saved on this phone."
        413, 422 -> "Genoa couldn’t accept more of this live video. Your recording is saved on this phone."
        else -> null
    }
}

sealed interface LiveUplinkState {
    data object Sending : LiveUplinkState

    data class Paused(val waiting: Int) : LiveUplinkState

    /** Distribution stopped for [message]; capture should stop and keep the replay. */
    data class Stopped(val message: String) : LiveUplinkState

    data object Finalizing : LiveUplinkState

    /**
     * The replay is durably queued; ending on the server continues in the background. [notice]
     * keeps the reason sharing stopped early visible after finalization.
     */
    data class Finalized(val notice: String? = null) : LiveUplinkState

    data class FinalizeFailed(val message: String) : LiveUplinkState
}

/**
 * Owns live segment delivery and finalization outside any screen. Segments are recorded in SQLite
 * before upload, so a process death leaves a queue that [drain] can finish from WorkManager.
 */
class LiveUplink(
    private val drafts: DraftStore,
    private val transport: UploadTransport,
    private val scope: CoroutineScope,
    /** Durably schedules [drain] (WorkManager in the app). */
    private val scheduleDrain: (String) -> Unit,
    /** Persists the replay as a draft and queues its upload. */
    private val deliverReplay: suspend (id: String, conversationId: String, file: File, durationMs: Long) -> Unit,
    private val limits: LiveLimits = LiveLimits(),
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val retryDelayMs: (Int) -> Long = { attempt -> minOf(10_000L, attempt * 1_000L) },
) {
    private val _states = MutableStateFlow<Map<String, LiveUplinkState>>(emptyMap())
    val states: StateFlow<Map<String, LiveUplinkState>> = _states
    private val senders = ConcurrentHashMap<String, Job>()
    private val signals = ConcurrentHashMap<String, Channel<Unit>>()
    private val finishing = ConcurrentHashMap<String, Job>()
    private val finishRequests = ConcurrentHashMap<String, FinishRequest>()

    private data class FinishRequest(val conversationId: String, val replay: File?, val durationMs: Long)

    private fun set(id: String, state: LiveUplinkState) = _states.update { it + (id to state) }

    fun forget(id: String) = _states.update { it - id }

    /** Records that sharing started, before any segment exists, so recovery can end it. */
    fun begin(session: LocalLiveSession) {
        drafts.startLive(session)
        set(session.id, LiveUplinkState.Sending)
        val signal = Channel<Unit>(Channel.CONFLATED)
        signals[session.id] = signal
        senders[session.id] = scope.launch(Dispatchers.IO) { send(session.id, signal) }
    }

    /** Called from the capture thread for each finished segment. */
    fun offer(id: String, file: File, number: Int, seconds: Double): LiveOffer {
        if (_states.value[id] is LiveUplinkState.Stopped) {
            file.delete()
            return LiveOffer.STOPPED
        }
        val pending = drafts.liveSegments(id)
        val decision =
            liveOffer(limits, pending.size, pending.sumOf { it.bytes }, file.length(), freeBytes(file.parentFile ?: file))
        if (decision != LiveOffer.ACCEPTED) {
            file.delete()
            return decision
        }
        drafts.addLiveSegment(PendingLiveSegment(id, number, file.absolutePath, seconds, file.length()))
        signals[id]?.trySend(Unit)
        return decision
    }

    private suspend fun send(id: String, signal: Channel<Unit>) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val next = drafts.liveSegments(id).firstOrNull()
            if (next == null) {
                signal.receive()
                continue
            }
            try {
                upload(next)
                attempt = 0
                set(id, LiveUplinkState.Sending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val terminal = liveTerminalMessage(e)
                if (terminal != null) {
                    dropSegments(id)
                    set(id, LiveUplinkState.Stopped(terminal))
                    return
                }
                attempt++
                set(id, LiveUplinkState.Paused(drafts.liveSegments(id).size))
                delay(retryDelayMs(attempt))
            }
        }
    }

    private suspend fun upload(segment: PendingLiveSegment) {
        val file = File(segment.file)
        if (file.exists()) {
            try {
                transport.putBytes(
                    "/api/live/${segment.messageId}/segments/${segment.number}?duration=${segment.seconds}",
                    withContext(Dispatchers.IO) { file.readBytes() },
                )
            } catch (e: ApiException) {
                // A malformed segment is skipped; the replay keeps the full recording.
                if (e.status != 400) throw e
            }
        }
        drafts.removeLiveSegment(segment.messageId, segment.number)
        file.delete()
    }

    private fun dropSegments(id: String) {
        drafts.liveSegments(id).forEach {
            File(it.file).delete()
            drafts.removeLiveSegment(id, it.number)
        }
    }

    /**
     * Finishes sharing independently of the screen: queues the replay (when one exists) and
     * schedules durable ending. Failures become [LiveUplinkState.FinalizeFailed]; call again
     * to retry. Safe to call more than once.
     */
    fun finish(id: String, conversationId: String, replay: File?, durationMs: Long): Job {
        finishing[id]?.takeIf { it.isActive }?.let { return it }
        finishRequests[id] = FinishRequest(conversationId, replay, durationMs)
        // Set before returning so an observer never mistakes a previous failure for this attempt.
        val stopped = _states.value[id] as? LiveUplinkState.Stopped
        set(id, LiveUplinkState.Finalizing)
        val job =
            scope.launch(Dispatchers.IO) {
                try {
                    senders.remove(id)?.let { withTimeoutOrNull(5_000) { it.cancelAndJoin() } }
                    signals.remove(id)
                    drafts.markLiveEnding(id)
                    if (replay != null) deliverReplay(id, conversationId, replay, durationMs)
                    else drafts.removeUnlessDiscarded(id)
                    scheduleDrain(id)
                    set(id, LiveUplinkState.Finalized(stopped?.message))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    set(
                        id,
                        LiveUplinkState.FinalizeFailed(
                            if (replay != null)
                                "Couldn’t finish saving the replay. Your recording is still on this phone."
                            else "Couldn’t end live sharing yet."
                        ),
                    )
                }
            }
        finishing[id] = job
        return job
    }

    /** Retries a failed [finish] with the same recording. */
    fun retryFinish(id: String): Job? =
        finishRequests[id]?.let { finish(id, it.conversationId, it.replay, it.durationMs) }

    /**
     * Sends remaining segments, then ends the live message. Returns false when a temporary
     * problem remains; the caller retries later. Terminal errors end reconciliation, because
     * the replay upload carries delivery.
     */
    suspend fun drain(id: String): Boolean {
        val session = drafts.liveSession(id) ?: return true
        if (senders[id]?.isActive == true) return false
        for (segment in drafts.liveSegments(id)) {
            try {
                upload(segment)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (liveTerminalMessage(e) == null) return false
                dropSegments(id)
                break
            }
        }
        try {
            transport.call("POST", "/api/live/$id/end")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (liveTerminalMessage(e) == null) return false
        } catch (_: IOException) {
            return false
        }
        File(session.directory).listFiles { f -> f.name.startsWith("segment-") }?.forEach { it.delete() }
        drafts.removeLive(id)
        return true
    }

    /** Gives up on ending a live message; its replay upload is unaffected. */
    fun abandon(id: String) {
        val session = drafts.liveSession(id) ?: return
        dropSegments(id)
        File(session.directory).listFiles { f -> f.name.startsWith("segment-") }?.forEach { it.delete() }
        drafts.removeLive(id)
    }

    /** Sessions left by a previous process: nothing on this phone can still send their segments. */
    fun unfinished(ownerId: String) =
        drafts.liveSessions().filter { it.ownerId == ownerId && senders[it.id]?.isActive != true }
}
