package app.gather

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gather.data.DraftStore
import app.gather.data.LiveLimits
import app.gather.data.LiveOffer
import app.gather.data.LiveUplink
import app.gather.data.LiveUplinkState
import app.gather.data.LocalLiveSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveUplinkTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = "live-test-${UUID.randomUUID()}.db"
    private val id = UUID.randomUUID().toString()
    private val directory get() = File(context.cacheDir, "live-$id").apply { mkdirs() }
    private lateinit var store: DraftStore
    private lateinit var server: FakeUploadServer
    private val scopes = mutableListOf<CoroutineScope>()
    private val drains = mutableListOf<String>()
    private val replays = mutableListOf<Pair<String, Long>>()
    private var replayFailures = 0

    @Before
    fun setUp() {
        store = DraftStore(context, database)
        server = FakeUploadServer()
        server.messages[id] = FakeUploadServer.Message.LIVE
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        store.close()
        context.deleteDatabase(database)
        directory.deleteRecursively()
    }

    private fun uplink(
        limits: LiveLimits = LiveLimits(minFreeBytes = 0),
        free: Long = Long.MAX_VALUE,
        drafts: DraftStore = store,
    ): LiveUplink {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return LiveUplink(
            drafts,
            server,
            scope,
            scheduleDrain = { drains += it },
            deliverReplay = { messageId, _, _, duration ->
                if (replayFailures-- > 0) error("disk full")
                replays += messageId to duration
            },
            limits = limits,
            freeBytes = { free },
            retryDelayMs = { 20 },
        )
    }

    private fun segment(number: Int, bytes: Int = 10) =
        File(directory, "segment-$number.ts").apply { writeBytes(ByteArray(bytes)) }

    private fun session() = LocalLiveSession(id, "conversation", "owner", false, directory.absolutePath)

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun LiveUplink.awaitState(predicate: (LiveUplinkState?) -> Boolean) =
        states.first { predicate(it[id]) }[id]

    @Test
    fun deliversSegmentsInOrder() = test {
        val uplink = uplink()
        uplink.begin(session())
        (0..2).forEach { assertEquals(LiveOffer.ACCEPTED, uplink.offer(id, segment(it), it, 2.0)) }
        withTimeout(5_000) { while (server.segments.size < 3) delay(20) }
        assertEquals(listOf(0, 1, 2), server.segments.toList())
        assertTrue(store.liveSegments(id).isEmpty())
        assertFalse(File(directory, "segment-0.ts").exists())
    }

    @Test
    fun outageFillsABoundedBufferAndThenAsksCaptureToStop() = test {
        server.offline = true
        val uplink = uplink(LiveLimits(maxPendingSegments = 2, maxPendingBytes = 1_000, minFreeBytes = 0))
        uplink.begin(session())
        assertEquals(LiveOffer.ACCEPTED, uplink.offer(id, segment(0), 0, 2.0))
        assertEquals(LiveOffer.ACCEPTED, uplink.offer(id, segment(1), 1, 2.0))
        uplink.awaitState { it is LiveUplinkState.Paused }
        val rejected = segment(2)
        assertEquals(LiveOffer.BUFFER_FULL, uplink.offer(id, rejected, 2, 2.0))
        assertFalse("A rejected segment does not consume storage", rejected.exists())
        assertEquals(2, store.liveSegments(id).size)
    }

    @Test
    fun byteLimitAppliesIndependentlyOfCount() = test {
        server.offline = true
        val uplink = uplink(LiveLimits(maxPendingSegments = 100, maxPendingBytes = 1_000, minFreeBytes = 0))
        uplink.begin(session())
        assertEquals(LiveOffer.ACCEPTED, uplink.offer(id, segment(0, bytes = 600), 0, 2.0))
        assertEquals(LiveOffer.BUFFER_FULL, uplink.offer(id, segment(1, bytes = 600), 1, 2.0))
    }

    @Test
    fun lowStorageStopsCaptureWithoutBufferingMore() = test {
        val uplink = uplink(LiveLimits(minFreeBytes = 100), free = 50)
        uplink.begin(session())
        val file = segment(0)
        assertEquals(LiveOffer.LOW_STORAGE, uplink.offer(id, file, 0, 2.0))
        assertFalse(file.exists())
        assertTrue(store.liveSegments(id).isEmpty())
    }

    @Test
    fun revokedAccessStopsRetryingAndIsReported() = test {
        server.failWith = 403
        val uplink = uplink()
        uplink.begin(session())
        uplink.offer(id, segment(0), 0, 2.0)
        val state = uplink.awaitState { it is LiveUplinkState.Stopped }
        assertTrue((state as LiveUplinkState.Stopped).message.contains("saved"))
        val attempts = server.requests.size
        delay(300)
        assertEquals("Terminal errors are not retried", attempts, server.requests.size)
        assertEquals(LiveOffer.STOPPED, uplink.offer(id, segment(1), 1, 2.0))
        assertTrue(store.liveSegments(id).isEmpty())
    }

    @Test
    fun temporaryFailuresKeepRetryingUntilDelivered() = test {
        server.offline = true
        val uplink = uplink()
        uplink.begin(session())
        uplink.offer(id, segment(0), 0, 2.0)
        uplink.awaitState { it is LiveUplinkState.Paused }
        server.offline = false
        uplink.awaitState { it == LiveUplinkState.Sending }
        withTimeout(5_000) { while (server.segments.isEmpty()) delay(20) }
        assertEquals(listOf(0), server.segments.toList())
    }

    @Test
    fun finishingContinuesAfterTheScreenIsGone() = test {
        val uplink = uplink()
        uplink.begin(session())
        // The screen requests finishing and is then torn down, as when the person leaves.
        val screen = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        screen.launch(start = CoroutineStart.UNDISPATCHED) { uplink.finish(id, "conversation", File(directory, "recording.mp4"), 4_000).join() }
        screen.cancel()
        assertEquals(LiveUplinkState.Finalized(), uplink.awaitState { it is LiveUplinkState.Finalized })
        assertEquals(listOf(id to 4_000L), replays)
        assertEquals(listOf(id), drains)
        assertTrue(store.liveSession(id)!!.ending)
    }

    @Test
    fun failedReplaySaveIsVisibleAndRetryable() = test {
        replayFailures = 1
        val uplink = uplink()
        uplink.begin(session())
        uplink.finish(id, "conversation", File(directory, "recording.mp4"), 4_000)
        assertTrue(uplink.awaitState { it is LiveUplinkState.FinalizeFailed } is LiveUplinkState.FinalizeFailed)
        assertTrue(drains.isEmpty())
        uplink.retryFinish(id)
        uplink.awaitState { it is LiveUplinkState.Finalized }
        assertEquals(listOf(id to 4_000L), replays)
    }

    @Test
    fun stoppedReasonStaysVisibleAfterFinalizing() = test {
        server.failWith = 404
        val uplink = uplink()
        uplink.begin(session())
        uplink.offer(id, segment(0), 0, 2.0)
        uplink.awaitState { it is LiveUplinkState.Stopped }
        uplink.finish(id, "conversation", File(directory, "recording.mp4"), 1_000)
        val finalized = uplink.awaitState { it is LiveUplinkState.Finalized } as LiveUplinkState.Finalized
        assertTrue(finalized.notice!!.contains("no longer available"))
    }

    @Test
    fun anotherProcessDrainsPersistedSegmentsAndEndsTheLiveMessage() = test {
        server.offline = true
        val first = uplink()
        first.begin(session())
        first.offer(id, segment(0), 0, 2.0)
        first.offer(id, segment(1), 1, 2.0)
        scopes.forEach { it.cancel() } // process death
        store.close()

        store = DraftStore(context, database)
        server.offline = false
        val restarted = uplink(drafts = store)
        assertEquals(listOf(id), restarted.unfinished("owner").map { it.id })
        assertTrue(restarted.drain(id))
        assertEquals(listOf(0, 1), server.segments.toList())
        assertTrue(server.requests.last().endsWith("/api/live/$id/end"))
        assertEquals(FakeUploadServer.Message.READY, server.messages[id])
        assertNull(store.liveSession(id))
    }

    @Test
    fun drainRetriesLaterWhileOffline() = test {
        val uplink = uplink()
        uplink.begin(session())
        uplink.finish(id, "conversation", null, 0).join()
        server.offline = true
        assertFalse(uplink.drain(id))
        assertTrue(store.liveSession(id) != null)
        server.offline = false
        assertTrue(uplink.drain(id))
        assertNull(store.liveSession(id))
    }
}
