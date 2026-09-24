package app.gather

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.gather.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsabilityFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule
    val permissions =
        GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )

    @Test
    fun invitationAndRecordingStayLowFriction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repo = (instrumentation.targetContext.applicationContext as GenoaApplication).repository
        runBlocking {
            repo.session.baseUrl = "http://localhost:18888"
            repo.signIn("${UUID.randomUUID()}@example.test", "abcdef", "Rosa", "")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText("New conversation")
            compose.onAllNodesWithText("New conversation").onFirst().performClick()
            waitText("Copy invite link")
            compose.onNodeWithText("Conversation name").assertDoesNotExist()
            screenshot("new-conversation")
            compose.onNodeWithText("Copy invite link").performClick()
            waitText("Link copied")
            val conversation = runBlocking { repo.conversations().single() }
            assertEquals("New conversation", conversation.title)
            compose.onNodeWithText("Open conversation").performClick()
            waitDescription("Conversation options")
            compose.onNodeWithContentDescription("Conversation options").performClick()
            compose.onNodeWithText("Conversation details").performClick()
            waitText("Conversation name")
            screenshot("conversation-details")
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithContentDescription("Reply options").performClick()
            compose.onNodeWithText("Record a voice message").performClick()
            waitDescription("Start recording")
            compose.onNodeWithContentDescription("Start recording").performClick()
            waitDescription("Stop recording")
            Thread.sleep(2500)
            compose.onNodeWithContentDescription("Stop recording").performClick()
            waitText("Saved on this phone")
            compose.onNodeWithText("Send to New conversation").performClick()
            waitText("Message queued. Ready for another?")
            compose.onNodeWithContentDescription("Start recording").assertExists()
            compose.waitUntil(30000) {
                runBlocking {
                    repo.messages(conversation.id).any { it.kind == "audio" && it.state == "ready" }
                }
            }
            screenshot("after-recording")
            compose.onNodeWithContentDescription("Close recorder").performClick()
            waitDescription("Go live")
            compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        }
    }

    private fun waitText(text: String) =
        compose.waitUntil(20000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitDescription(text: String) =
        compose.waitUntil(20000) {
            compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory =
            File(instrumentation.targetContext.getExternalFilesDir(null), "usability").apply {
                mkdirs()
            }
        File(directory, "$name.png").outputStream().use { stream ->
            instrumentation.uiAutomation
                .takeScreenshot()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
