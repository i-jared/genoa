package app.gather

import android.Manifest
import android.app.PendingIntent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.gather.data.*
import app.gather.ui.GenoaViewModel
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable accounts on the isolated localhost:18891 Worker; never run on an owner's device. */
@RunWith(AndroidJUnit4::class)
class SyncFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo get() = (context.applicationContext as GenoaApplication).repository
    private val base = "http://localhost:18891"
    private var scenario: ActivityScenario<MainActivity>? = null
    private var launchIntent: android.content.Intent? = null
    private val tokens = mutableListOf<String>()
    private var sender = ""
    private lateinit var cid: String

    private fun request(token: String, path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val request = Request.Builder().url(base + path).header("Origin", base).header("Authorization", "Bearer $token")
            .method(method, if (method == "GET") null else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())).build()
        return OkHttpClient().newCall(request).execute().use {
            val text = it.body!!.string()
            assertTrue("${it.code}: $text", it.isSuccessful)
            JSONObject(text)
        }
    }

    private fun setup(messages: Int = 0): List<String> = runBlocking {
        repo.session.baseUrl = base
        signUp("Sync sender")
        sender = repo.session.token!!
        tokens += sender
        cid = repo.createConversation("Sync regression")
        val invite = repo.api.json("POST", "/api/conversations/$cid/invite").getString("token")
        signUp("Sync viewer")
        tokens += repo.session.token!!
        repo.join(invite)
        repo.me()
        repo.conversations()
        (0 until messages).map { send("Note $it") }
    }

    private suspend fun signUp(name: String) = withTimeout(90_000) {
        val email = "${UUID.randomUUID()}@example.test"
        while (true) {
            try {
                repo.signIn(email, "android-test-password-123", name, "local-genoa-beta")
                break
            } catch (error: ApiException) {
                if (error.status != 429) throw error
                delay(10_000)
            }
        }
    }

    private fun send(text: String): String {
        val id = UUID.randomUUID().toString()
        request(sender, "/api/conversations/$cid/messages", "POST", json("id" to id, "text" to text))
        return id
    }

    private fun viewModel(): GenoaViewModel {
        lateinit var vm: GenoaViewModel
        scenario!!.onActivity { vm = ViewModelProvider(it)[GenoaViewModel::class.java] }
        return vm
    }

    @After fun cleanup() {
        // ActivityScenario matches lifecycle callbacks by the original launch Intent's data.
        // Production onNewIntent updates it when another notification is opened.
        launchIntent?.let { original -> scenario?.onActivity { it.intent = original } }
        scenario?.close()
        for (token in tokens) request(token, "/api/account/delete", "POST", json("confirmation" to "DELETE"))
        repo.session.token = null
        repo.session.userId = ""
        repo.drafts.clearCache()
    }

    @Test fun homeUnreadRefreshesWhileVisibleAndOnResume() {
        setup()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15_000) { viewModel().state.value.conversations.any { it.id == cid } }
        send("Arrived while Home is open")
        compose.waitUntil(12_000) { viewModel().state.value.conversations.first { it.id == cid }.unread == 1 }
        compose.onNodeWithText("1 new").assertIsDisplayed()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        send("Arrived while backgrounded")
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(8_000) { viewModel().state.value.conversations.first { it.id == cid }.unread == 2 }
        compose.onNodeWithText("2 new").assertIsDisplayed()
    }

    @Test fun remindersOpenExactMessagesOnColdAndWarmLaunchAndExplainDeletion() {
        val ids = setup(4)
        repo.drafts.clearCache()
        launchIntent = notificationIntent(context, cid, ids[1], "cold-reminder")
        scenario = ActivityScenario.launch(launchIntent!!)
        compose.waitUntil(15_000) { viewModel().state.value.openingFocusId == ids[1] }
        compose.onAllNodesWithText("Note 1").onFirst().assertExists()
        val first = PendingIntent.getActivity(context, 0, notificationIntent(context, cid, ids[0], "first-reminder"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val second = PendingIntent.getActivity(context, 0, notificationIntent(context, cid, ids[2], "second-reminder"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        assertNotEquals(first, second)
        first.send()
        compose.waitUntil(15_000) { viewModel().state.value.openingFocusId == ids[0] }
        second.send()
        compose.waitUntil(15_000) { viewModel().state.value.openingFocusId == ids[2] }
        request(sender, "/api/messages/${ids[2]}", "DELETE")
        second.send()
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithText("This message is no longer available.").fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            val state = viewModel().state.value
            throw AssertionError("Missing deletion notice: focus=${state.openingFocusId}, error=${state.error}, connection=${state.connectionError}, containsDeleted=${state.messages.any { it.id == ids[2] }}", error)
        }
        assertEquals(cid, viewModel().state.value.selected?.id)
        assertFalse(viewModel().state.value.messages.any { it.id == ids[2] })
        first.cancel()
        second.cancel()
    }

    @Test fun offlineProgressSurvivesStoreReopenCoalescesRetriesAndStaysAccountScoped() {
        val ids = setup(1)
        val message = runBlocking { repo.watchPage(cid).messages.single() }
        val account = repo.progressAccount
        val original = LocalWatchProgress(account, ids[0], cid, 1200, false, UUID.randomUUID().toString())
        repo.drafts.saveWatchProgress(original)
        DraftStore(context).use { reopened ->
            assertEquals(1200L, reopened.watchProgress(account).single().positionMs)
        }
        val disconnected = Repository(context, Api(repo.session, OkHttpClient.Builder().addInterceptor { throw IOException("Synthetic offline connection") }.build()))
        try { runBlocking { disconnected.syncWatchProgress() }; fail("Offline sync should fail") } catch (_: IOException) { }
        assertTrue(repo.drafts.watchProgress(account).single().dirty)
        assertEquals(1200L, repo.cachedMessages(cid).single().positionMs)
        var writes = 0
        val reconnecting = Repository(context, Api(repo.session, OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().method == "PUT" && ++writes == 1) {
                // A newer local completion arrives before the earlier server response is acknowledged.
                repo.drafts.saveWatchProgress(original.copy(positionMs = 4300, watched = true, revision = UUID.randomUUID().toString()))
            }
            response
        }.build()))
        repo.drafts.saveWatchProgress(original.copy(account = "another-account"))
        runBlocking { reconnecting.syncWatchProgress() }
        assertEquals(2, writes)
        assertFalse(repo.drafts.watchProgress(account).single().dirty)
        assertTrue(repo.drafts.watchProgress("another-account").single().dirty)
        val synced = request(repo.session.token!!, "/api/conversations/$cid/watch?message=${ids[0]}").getJSONArray("messages").getJSONObject(0)
        assertEquals(4300L, synced.getLong("position_ms"))
        assertEquals(1, synced.getInt("watched"))
        runBlocking { repo.saveWatchProgress(message, 2000, false) }
        assertTrue(repo.drafts.watchProgress(account).single().watched)
        runBlocking { repo.syncWatchProgress() }
        assertEquals(0, runBlocking { repo.conversations().single().unread })
        request(sender, "/api/messages/${ids[0]}", "DELETE")
        repo.drafts.saveWatchProgress(original)
        runBlocking { reconnecting.syncWatchProgress() }
        assertTrue(repo.drafts.watchProgress(account).isEmpty())
        repo.drafts.removeWatchProgress("another-account")
    }

    @Test fun backgroundPlaybackSavesProgressAfterWatchScreenCloses() {
        setup()
        val file = java.io.File(context.filesDir, "sync-video-${UUID.randomUUID()}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("portrait-test.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val draft = runBlocking { repo.saveDraft(cid, file, "video", "video/mp4", 6000, null).also { repo.enqueue(it) } }
        compose.waitUntil(30_000) { runBlocking { repo.messages(cid).any { it.id == draft.id && it.state == "ready" } } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Sync regression").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Sync regression").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("video-ready").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithContentDescription("Show player controls").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Show player controls").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(15_000) { repo.drafts.watchProgress(repo.progressAccount).any { it.messageId == draft.id && it.watched } }
        runBlocking { repo.syncWatchProgress() }
        val watched = request(repo.session.token!!, "/api/conversations/$cid/watch?message=${draft.id}").getJSONArray("messages").getJSONObject(0)
        assertEquals(1, watched.getInt("watched"))
        assertTrue(watched.getLong("position_ms") >= 5000)
    }
}
