package app.gather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.gather.data.Reaction
import app.gather.ui.*
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Rule
import org.junit.Test

class ReactionPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun namedCardsStackAndExpireWhileOwnEmojiFloats() {
        var sent by mutableStateOf(true)
        compose.setContent {
            GenoaTheme {
                Box(Modifier.fillMaxSize().background(CameraBackdrop)) {
                    ReactionCards(
                        "video",
                        listOf(
                            Reaction("me", "🔥", 0, "own", "Me"),
                            Reaction("rosa", "❤️", 0, "a", "Rosa"),
                            Reaction("ben", "😂", 0, "b", "Ben"),
                        ),
                        "me",
                        0,
                        false,
                    )
                    if (sent)
                        FloatingSentEmoji(SentEmoji("sent", "🔥", Offset(700f, 1200f))) {
                            sent = false
                        }
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("reaction-card").fetchSemanticsNodes().size == 2
        }
        compose.onNodeWithText("Rosa").assertIsDisplayed()
        compose.onNodeWithText("Ben").assertIsDisplayed()
        compose.onNodeWithText("Me").assertDoesNotExist()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "watch-review").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "reaction-cards.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.waitUntil(6_000) {
            compose.onAllNodesWithTag("reaction-card").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("sent-emoji").assertDoesNotExist()
    }
}
