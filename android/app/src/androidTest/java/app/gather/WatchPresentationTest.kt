package app.gather

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.gather.data.ApiException
import app.gather.data.Repository
import app.gather.ui.GenoaViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the dedicated emulator with an isolated local Worker on port 18891. */
@RunWith(AndroidJUnit4::class)
class WatchPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule
    val permissions =
        GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    private var scenario: ActivityScenario<MainActivity>? = null
    private var repository: Repository? = null

    @After
    fun cleanup() {
        scenario?.close()
        repository?.let {
            runBlocking {
                it.api.json(
                    "POST",
                    "/api/account/delete",
                    app.gather.data.json("confirmation" to "DELETE"),
                )
            }
        }
    }

    @Test
    fun playbackControlsAndFilmstripStayAlignedThroughReplayAndRecordPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repo = (context.applicationContext as GenoaApplication).repository
        val cid = runBlocking {
            repo.session.baseUrl = "http://localhost:18891"
            withTimeout(90_000) {
                while (true) {
                    try {
                        repo.signIn(
                            "${UUID.randomUUID()}@example.test",
                            "android-test-password-123",
                            "Layout tester",
                            "local-genoa-beta",
                        )
                        break
                    } catch (e: ApiException) {
                        if (e.status != 429) throw e
                        delay(10_000)
                    }
                }
            }
            repository = repo
            repo.createConversation("Player layout check")
        }
        runBlocking {
            repeat(60) { index ->
                repo.api.json(
                    "POST",
                    "/api/conversations/$cid/messages",
                    app.gather.data.json(
                        "id" to UUID.randomUUID().toString(),
                        "text" to "Earlier note $index",
                    ),
                )
            }
        }
        val file = File(context.filesDir, "layout-test-${UUID.randomUUID()}.mp4")
        instrumentation.context.assets.open("portrait-test.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        runBlocking { repo.enqueue(repo.saveDraft(cid, file, "video", "video/mp4", 9_000, null)) }
        compose.waitUntil(30_000) { runBlocking { repo.messages(cid).any { it.state == "ready" } } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Player layout check").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("home-brand")
        compose.onNodeWithText("Player layout check").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("video-ready").fetchSemanticsNodes().isNotEmpty()
        }
        if (
            compose
                .onAllNodesWithContentDescription("Show player controls")
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            compose.onNodeWithContentDescription("Show player controls").performClick()
        }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithContentDescription("Pause message")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Pause message").performClick()
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(
                    hasContentDescription("Resume message") or hasContentDescription("Play message")
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Freeze Compose's animation clock so waiting for idle cannot consume the entire float.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("❤️").performClick()
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("sent-emoji").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("sent-emoji").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(350)
        screenshot("sent-emoji")
        compose.mainClock.autoAdvance = true
        var vm: GenoaViewModel? = null
        scenario!!.onActivity { vm = ViewModelProvider(it)[GenoaViewModel::class.java] }
        val initialCount = vm!!.state.value.messages.size
        compose.onNodeWithTag("message-filmstrip").performScrollToIndex(0)
        compose.waitUntil(10_000) { vm!!.state.value.messages.size > initialCount }
        compose.onNodeWithContentDescription("Video from Layout tester").assertIsNotDisplayed()
        // Loading history preserves the paused current video and the scroll anchor.
        compose.onNodeWithTag("video-ready").assertExists()
        compose
            .onNodeWithTag("message-filmstrip")
            .performScrollToNode(hasContentDescription("Video from Layout tester"))
        val play =
            compose
                .onNode(
                    hasContentDescription("Resume message") or hasContentDescription("Play message")
                )
                .fetchSemanticsNode()
                .boundsInRoot
        val picker =
            compose.onNodeWithContentDescription("More emojis").fetchSemanticsNode().boundsInRoot
        assertEquals(
            "Transport must line up with the bottom emoji button",
            picker.center.y,
            play.center.y,
            2f,
        )
        val slider = compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode()
        val strip = compose.onNodeWithTag("message-filmstrip").fetchSemanticsNode().boundsInRoot
        assertTrue("Filmstrip covers seek bar", slider.boundsInRoot.bottom <= strip.top)
        assertEquals(
            "Use decoder duration, not the deliberately stale 9s metadata",
            6_000f,
            slider.config[SemanticsProperties.ProgressBarRangeInfo].range.endInclusive,
            100f,
        )
        screenshot("playback-layout")
        scenario!!.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(5_000) {
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        val landscapePlay =
            compose
                .onNode(
                    hasContentDescription("Resume message") or hasContentDescription("Play message")
                )
                .fetchSemanticsNode()
                .boundsInRoot
        val landscapePicker =
            compose.onNodeWithContentDescription("More emojis").fetchSemanticsNode().boundsInRoot
        assertEquals(landscapePicker.center.y, landscapePlay.center.y, 2f)
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        compose.onNodeWithTag("message-filmstrip").assertIsDisplayed()
        screenshot("playback-landscape")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(5_000) {
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        compose.waitForIdle()
        compose
            .onNode(
                hasContentDescription("Resume message") or hasContentDescription("Play message")
            )
            .performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Record").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("message-filmstrip").assertIsDisplayed()
        assertRecordCentered()
        screenshot("camera-preview-filmstrip")
        // The final item remains selectable even when its ID is already focused.
        compose
            .onNodeWithTag("message-filmstrip")
            .performScrollToNode(hasContentDescription("Video from Layout tester"))
        compose.onNodeWithContentDescription("Video from Layout tester").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("video-ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Record").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Record").performClick()
        // The reply tap must survive the camera-screen handoff without a second tap.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Stop recording").fetchSemanticsNodes().isNotEmpty()
        }
        val liveId = runBlocking { repo.messages(cid).single { it.state == "live" }.id }
        compose.waitUntil(30_000) {
            runBlocking { repo.api.call("GET", "/api/live/$liveId/playlist.m3u8").contains("#EXTINF") }
        }
        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.waitUntil(45_000) {
            runBlocking { repo.messages(cid).any { it.id == liveId && it.state == "ready" && it.assetId != null } }
        }
    }

    private fun assertRecordCentered() {
        val row = compose.onNodeWithTag("record-actions").fetchSemanticsNode().boundsInRoot
        val record =
            compose.onNodeWithContentDescription("Record").fetchSemanticsNode().boundsInRoot
        val plus =
            compose.onNodeWithContentDescription("Reply options").fetchSemanticsNode().boundsInRoot
        assertEquals(row.center.x, record.center.x, 1f)
        assertTrue(plus.right < record.left)
    }

    private fun screenshot(name: String) {
        // Compose idle does not include the system rotation animation or screenshot composition.
        android.os.SystemClock.sleep(600)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory =
            File(instrumentation.targetContext.getExternalFilesDir(null), "watch-review").apply {
                mkdirs()
            }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
