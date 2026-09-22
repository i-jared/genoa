package app.gather

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import app.gather.ui.GenoaTheme
import app.gather.ui.PreparedVideo
import org.junit.After
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreparedVideoTest {
    @get:Rule val compose = createComposeRule()
    private var player: ExoPlayer? = null

    @After
    fun cleanup() {
        compose.runOnIdle { player?.release() }
    }

    @Test
    fun failedPreparationNeverRevealsTheVideoAndOffersRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = "file://${context.cacheDir}/missing-test-video.mp4"
        compose.runOnUiThread {
            player =
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.Builder().setMediaId("missing").setUri(source).build())
                }
        }
        compose.setContent { GenoaTheme { PreparedVideo(player, "missing", source, null) } }
        compose.onNodeWithText("Loading video…").assertIsDisplayed()
        compose.onNodeWithTag("video-ready").assertDoesNotExist()
        compose.runOnIdle { player!!.prepare() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Retry video").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("video-ready").assertDoesNotExist()
        compose.onNodeWithText("Retry video").performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithText("Couldn’t load this video.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("video-ready").assertDoesNotExist()
    }
}
