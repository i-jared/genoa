package app.gather

import android.Manifest
import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.gather.data.*
import app.gather.media.PlaybackService
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenoaFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun cleanup() {
        scenario?.close()
    }

    private fun setup(): Pair<Repository, String> {
        val repo =
            (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                    as GenoaApplication)
                .repository
        val cid = runBlocking {
            repo.session.baseUrl = "http://10.0.2.2:8787"
            repo.signIn(
                "${UUID.randomUUID()}@example.test",
                "android-test-password-123",
                "Android tester",
                "local-genoa-beta",
            )
            repo.createConversation("The weekend crew")
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("The weekend crew").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("The weekend crew").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Record").fetchSemanticsNodes().isNotEmpty()
        }
        return repo to cid
    }

    @Test
    fun textNotesAndVoiceSurviveRealDelivery() {
        val (repo, cid) = setup()
        compose.onNodeWithContentDescription("Reply options").performClick()
        compose.onNodeWithText("Write a text message").performClick()
        compose.onNodeWithText("Send a note…").performTextInput("Looking forward to Saturday!")
        compose.onNodeWithContentDescription("Send note").performClick()
        compose.waitUntil(20_000) {
            runBlocking { repo.messages(cid).any { it.text == "Looking forward to Saturday!" } }
        }
        compose.onNodeWithContentDescription("Conversation options").performClick()
        compose.onNodeWithText("Private scratchpad").performClick()
        compose
            .onNodeWithText("Things to remember when you reply…")
            .performTextInput("Ask about the hike")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) {
            runBlocking {
                repo.api.json("GET", "/api/conversations/$cid").optString("note") ==
                    "Ask about the hike"
            }
        }
        compose.onNodeWithText("Back to recording").performClick()
        compose.onNodeWithContentDescription("Reply options").performClick()
        compose.onNodeWithText("Record a voice message").performClick()
        compose.onNodeWithContentDescription("Start recording").performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithContentDescription("Stop recording")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        Thread.sleep(2500)
        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Saved on this phone").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Send to The weekend crew").performClick()
        compose.waitUntil(30_000) {
            runBlocking {
                repo.messages(cid).any {
                    it.kind == "audio" && it.state == "ready" && it.assetId != null
                }
            }
        }
        val message = runBlocking { repo.messages(cid).first { it.kind == "audio" } }
        assertTrue(message.durationMs > 1000)
        assertNotNull(message.assetId)
        verifyPlayback(repo, "/api/media/${message.assetId}", screenOff = true)
        screenshot("messages")
        compose.onNodeWithContentDescription("Voice message from Android tester").performClick()
        waitForPlayerChrome()
        screenshot("player")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithContentDescription("Close playback")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("A message from Android tester").performClick()
        waitForPlayerChrome()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Close playback").performClick()
    }

    @Test
    fun liveCapturePublishesSegmentsAndKeepsReplay() {
        val (repo, cid) = setup()
        compose.onNodeWithContentDescription("Record").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Stop recording").fetchSemanticsNodes().isNotEmpty()
        }
        val mid = runBlocking { repo.messages(cid).first { it.state == "live" }.id }
        assertEquals("One tap must create exactly one live message", 1, runBlocking { repo.messages(cid).size })
        compose.waitUntil(30_000) {
            runBlocking { repo.api.call("GET", "/api/live/$mid/playlist.m3u8").contains("#EXTINF") }
        }
        val playlist = runBlocking { repo.api.call("GET", "/api/live/$mid/playlist.m3u8") }
        assertTrue(playlist.contains("segments/0.ts"))
        verifyPlayback(repo, "/api/live/$mid/playlist.m3u8")
        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.waitUntil(45_000) {
            runBlocking {
                repo.messages(cid).any { it.id == mid && it.state == "ready" && it.assetId != null }
            }
        }
        assertTrue(
            runBlocking { repo.api.call("GET", "/api/live/$mid/playlist.m3u8") }
                .contains("#EXT-X-ENDLIST")
        )
    }

    @Test
    fun homeSearchProfileAndRecipientSelection() {
        val (repo, cid) = setup()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription("Home").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("home")
        compose.onNodeWithContentDescription("Search conversations").performClick()
        compose
            .onNodeWithText("Search loaded conversations")
            .performTextInput("missing conversation")
        compose.onNodeWithText("No conversations match ‘missing conversation’.").assertExists()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithContentDescription("Profile").performClick()
        compose.onNodeWithText("Message notifications").assertExists()
        screenshot("profile")
        compose.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Record", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Choose a conversation").assertExists()
        compose.onAllNodesWithText("The weekend crew").onLast().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription("Record").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("To: The weekend crew").assertExists()
        compose.onNodeWithContentDescription("Record").assertExists()
        assertTrue("Choosing a recipient must not start sharing", runBlocking { repo.messages(cid).isEmpty() })
        screenshot("recorder")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithContentDescription("Record").assertExists()
        screenshot("thread")
    }

    @Test
    fun privateVideoStopsForReviewAndKeepsDraft() {
        val (repo, cid) = setup()
        compose.onNodeWithContentDescription("Reply options").performClick()
        compose.onNodeWithText("Record a private video").performClick()
        compose.waitUntil(20000) {
            runCatching {
                    compose.onNodeWithContentDescription("Start recording").assertIsEnabled()
                }
                .isSuccess
        }
        compose.onNodeWithContentDescription("Start recording").performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithContentDescription("Stop recording")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Synthetic cameras may deliver their first encoded frame slowly under host load.
        compose.waitUntil(45000) {
            repo.drafts.all().any {
                it.conversationId == cid && java.io.File(it.file).length() > 4096
            }
        }
        Thread.sleep(2000)
        assertTrue(runBlocking { repo.messages(cid).isEmpty() })
        compose.onNodeWithContentDescription("Close recorder").performClick()
        compose.onNodeWithText("Keep recording").assertExists()
        compose.onNodeWithText("Stop and review", useUnmergedTree = true).performClick()
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("Saved on this phone").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("review")
        compose.onNodeWithText("Keep draft").performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithText("Draft · Saved on this phone")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(runBlocking { repo.messages(cid).isEmpty() })
        assertTrue(
            repo.drafts.all().any {
                it.conversationId == cid && it.kind == "video" && it.durationMs > 1000
            }
        )
    }

    private fun waitForPlayerChrome() {
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithContentDescription("Message player")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        if (
            compose
                .onAllNodesWithContentDescription("Show player controls")
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            compose.onNodeWithContentDescription("Show player controls").performClick()
        }
        val transportLabels = listOf("Play message", "Pause message", "Resume message")
        compose.waitUntil(10_000) {
            transportLabels.any {
                compose.onAllNodesWithContentDescription(it).fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.onNodeWithContentDescription("Back").assertExists()
        assertTrue(
            transportLabels.any {
                compose.onAllNodesWithContentDescription(it).fetchSemanticsNodes().isNotEmpty()
            }
        )
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val mode =
            context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val scale = context.resources.configuration.fontScale
        val directory = java.io.File(context.getExternalFilesDir(null), "design").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(directory, "$name-$mode-$scale.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun verifyPlayback(repo: Repository, path: String, screenOff: Boolean = false) =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val future =
                withContext(Dispatchers.Main) {
                    MediaController.Builder(
                            context,
                            SessionToken(
                                context,
                                ComponentName(context, PlaybackService::class.java),
                            ),
                        )
                        .buildAsync()
                }
            val controller =
                withContext(Dispatchers.IO) {
                    future.get(15, java.util.concurrent.TimeUnit.SECONDS)
                }
            try {
                withContext(Dispatchers.Main) {
                    controller.setMediaItem(MediaItem.fromUri(repo.session.baseUrl + path))
                    controller.prepare()
                    controller.play()
                }
                withTimeout(25000) {
                    while (withContext(Dispatchers.Main) { controller.currentPosition < 250 }) {
                        withContext(Dispatchers.Main) { controller.playerError?.let { throw it } }
                        delay(100)
                    }
                }
                if (screenOff) {
                    val before = withContext(Dispatchers.Main) { controller.currentPosition }
                    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                    automation.executeShellCommand("input keyevent 223").close()
                    delay(1000)
                    val after = withContext(Dispatchers.Main) { controller.currentPosition }
                    automation.executeShellCommand("input keyevent 224").close()
                    automation.executeShellCommand("wm dismiss-keyguard").close()
                    assertTrue("Audio should continue with the screen off", after > before)
                }
                withContext(Dispatchers.Main) {
                    controller.setPlaybackSpeed(2f)
                    assertEquals(2f, controller.playbackParameters.speed)
                    controller.pause()
                }
            } finally {
                withContext(Dispatchers.Main) { MediaController.releaseFuture(future) }
            }
        }
}
