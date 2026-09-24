package app.gather

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gather.data.Draft
import app.gather.data.DraftState
import app.gather.data.DraftStore
import app.gather.data.DraftUploader
import app.gather.data.UploadOutcome
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

/** Discard must win against every stage of an in-flight upload, and survive restart. */
@RunWith(AndroidJUnit4::class)
class DraftDiscardTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val owner = "owner-1"
    private val database = "discard-test-${UUID.randomUUID()}.db"
    private lateinit var store: DraftStore
    private lateinit var server: FakeUploadServer
    private var now = 1_000_000L
    private val sent = mutableListOf<String>()

    @Before
    fun setUp() {
        store = DraftStore(context, database)
        server = FakeUploadServer(chunkSize = 4)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(database)
    }

    private fun uploader(drafts: DraftStore = store) =
        DraftUploader(drafts, server, onSent = { sent += it.id }, now = { now })

    /** A 10-byte video uploads as three 4-byte parts. */
    private fun draft(kind: String = "video"): Draft {
        val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "draft-$id").apply {
            writeText(if (kind == "text") """{"text":"hello"}""" else "0123456789")
        }
        val draft =
            Draft(id, "conversation", file.absolutePath, kind, if (kind == "text") "application/json" else "video/mp4", 1000, null, DraftState.QUEUED, 0, null, now, owner)
        assertTrue(store.save(draft))
        return draft
    }

    private fun runBlockingTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(20_000) { block() } }

    private fun kotlinx.coroutines.CoroutineScope.startUpload(uploader: DraftUploader, draft: Draft): Deferred<UploadOutcome> =
        async(Dispatchers.IO) { uploader.upload(draft.id, owner) }

    private fun assertTombstoned(draft: Draft) {
        assertNull("A discarded draft must not be visible", store.get(draft.id))
        assertTrue(store.all().none { it.id == draft.id })
        assertTrue(store.discarded().any { it.id == draft.id })
        assertFalse("Local media is removed once work has stopped", File(draft.file).exists())
    }

    /** Reconciles until confirmed, moving the clock past the settle window. */
    private suspend fun reconcileAfterSettle(uploader: DraftUploader) {
        now += DraftUploader.SETTLE_MS
        assertTrue(uploader.reconcileDiscards(owner))
        assertTrue(store.discarded().isEmpty())
    }

    @Test
    fun discardDuringSetupStopsBeforeAnyPartAndDeletesTheServerMessage() = runBlockingTest {
        val uploader = uploader()
        val draft = draft()
        // The server has created the message, but its response has not arrived yet.
        val hold = server.hold("POST /api/uploads", afterApply = true)
        val upload = startUpload(uploader, draft)
        hold.reached.await()
        uploader.discard(draft.id)
        assertEquals(UploadOutcome.Stopped, upload.await())
        assertTombstoned(draft)
        assertTrue(server.requests.none { it.startsWith("PUT") || it.endsWith("/complete") })
        // A 200 before the settle window keeps the tombstone; a later confirmation removes it.
        assertFalse(uploader.reconcileDiscards(owner))
        assertTrue(store.discarded().any { it.id == draft.id })
        reconcileAfterSettle(uploader)
        assertFalse(server.live(draft.id))
    }

    @Test
    fun discardDuringPartTransferIsNotOverwrittenByProgress() = runBlockingTest {
        val uploader = uploader()
        val draft = draft()
        val hold = server.hold("PUT /api/uploads/${draft.id}/parts/2")
        val upload = startUpload(uploader, draft)
        hold.reached.await()
        uploader.discard(draft.id)
        assertEquals(UploadOutcome.Stopped, upload.await())
        assertTombstoned(draft)
        assertTrue(server.requests.none { it.endsWith("/parts/3") || it.endsWith("/complete") })
        assertEquals(setOf(1), server.parts[draft.id])
        reconcileAfterSettle(uploader)
        assertFalse(server.live(draft.id))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun discardAtFinalizationNeverReportsSent() = runBlockingTest {
        val uploader = uploader()
        val draft = draft()
        val hold = server.hold("POST /api/uploads/${draft.id}/complete")
        val upload = startUpload(uploader, draft)
        hold.reached.await()
        assertEquals(DraftState.PROCESSING, store.get(draft.id)!!.state)
        uploader.discard(draft.id)
        assertEquals(UploadOutcome.Stopped, upload.await())
        assertTombstoned(draft)
        assertTrue(sent.isEmpty())
        reconcileAfterSettle(uploader)
        assertFalse(server.live(draft.id))
    }

    @Test
    fun discardAfterLostFinalResponseDeletesThePublishedMessage() = runBlockingTest {
        val uploader = uploader()
        val draft = draft()
        server.loseResponse("POST /api/uploads/${draft.id}/complete")
        assertEquals(UploadOutcome.Retry, uploader.upload(draft.id, owner))
        // The server published it, but the phone must not claim it was sent.
        assertEquals(FakeUploadServer.Message.READY, server.messages[draft.id])
        assertEquals(DraftState.FAILED, store.get(draft.id)!!.state)
        uploader.discard(draft.id)
        server.requests.clear()
        // WorkManager's scheduled retry after the discard makes no request and writes nothing.
        assertEquals(UploadOutcome.Stopped, uploader.upload(draft.id, owner))
        assertTrue(server.requests.isEmpty())
        assertTombstoned(draft)
        reconcileAfterSettle(uploader)
        assertEquals(FakeUploadServer.Message.DELETED, server.messages[draft.id])
    }

    @Test
    fun lostFinalResponseRetriesToSentWithTheSameId() = runBlockingTest {
        val uploader = uploader()
        val draft = draft()
        server.loseResponse("POST /api/uploads/${draft.id}/complete")
        assertEquals(UploadOutcome.Retry, uploader.upload(draft.id, owner))
        assertEquals(UploadOutcome.Sent, uploader.upload(draft.id, owner))
        assertEquals(DraftState.SENT, store.get(draft.id)!!.state)
        assertEquals(listOf(draft.id), sent)
        assertEquals(1, server.messages.size)
    }

    @Test
    fun discardSurvivesProcessRestartUntilTheServerConfirms() = runBlockingTest {
        val draft = draft()
        server.loseResponse("POST /api/uploads/${draft.id}/complete")
        assertEquals(UploadOutcome.Retry, uploader().upload(draft.id, owner))
        server.offline = true
        uploader().discard(draft.id)
        assertFalse(uploader().reconcileDiscards(owner))
        store.close()

        // A new process: fresh store and uploader over the same database.
        store = DraftStore(context, database)
        val restarted = uploader(store)
        assertTrue(store.discarded().any { it.id == draft.id })
        assertEquals(UploadOutcome.Stopped, restarted.upload(draft.id, owner))
        assertNull(store.get(draft.id))
        server.offline = false
        reconcileAfterSettle(restarted)
        assertEquals(FakeUploadServer.Message.DELETED, server.messages[draft.id])
    }

    @Test
    fun earlyNotFoundIsNotProofTheMessageCannotAppear() = runBlockingTest {
        val uploader = uploader()
        val draft = draft()
        uploader.discard(draft.id)
        assertFalse("A 404 inside the settle window is not conclusive", uploader.reconcileDiscards(owner))
        // A setup request sent just before discard reaches the server late.
        server.messages[draft.id] = FakeUploadServer.Message.UPLOADING
        reconcileAfterSettle(uploader)
        assertEquals(FakeUploadServer.Message.DELETED, server.messages[draft.id])
    }

    @Test
    fun discardWinsAgainstAnAcceptedTextMessage() = runBlockingTest {
        val uploader = uploader()
        val draft = draft(kind = "text")
        val hold = server.hold("POST /api/conversations/conversation/messages", afterApply = true)
        val upload = startUpload(uploader, draft)
        hold.reached.await()
        uploader.discard(draft.id)
        assertEquals(UploadOutcome.Stopped, upload.await())
        assertTombstoned(draft)
        reconcileAfterSettle(uploader)
        assertEquals(FakeUploadServer.Message.DELETED, server.messages[draft.id])
    }

    @Test
    fun lateWritesCannotReviveOrRecreateADiscardedDraft() = runBlockingTest {
        val draft = draft()
        store.markDiscarded(draft.id)
        assertFalse(store.update(draft.copy(state = DraftState.UPLOADING, percent = 50)))
        assertFalse(store.save(draft.copy(state = DraftState.SAVED)))
        assertFalse(store.removeUnlessDiscarded(draft.id))
        assertTrue(store.discarded().any { it.id == draft.id })
        store.remove(draft.id)
        assertFalse("Workers only update; a removed row stays removed", store.update(draft.copy(state = DraftState.SENT)))
        assertNull(store.get(draft.id))
        assertTrue(store.discarded().isEmpty())
    }

    @Test
    fun reconciliationLeavesAnotherAccountsTombstones() = runBlockingTest {
        val uploader = uploader()
        val draft = draft()
        uploader.discard(draft.id)
        now += DraftUploader.SETTLE_MS
        assertTrue(uploader.reconcileDiscards("someone-else"))
        assertTrue(server.requests.none { it.startsWith("DELETE") })
        assertTrue(store.discarded().any { it.id == draft.id })
    }
}
