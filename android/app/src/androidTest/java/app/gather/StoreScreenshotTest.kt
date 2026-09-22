package app.gather

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit store capture, using scripts/release/seed-screenshots.py and a dedicated emulator. */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test fun captureStoreScreenshots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("storeScreenshots") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = (context.applicationContext as GenoaApplication).repository
        runBlocking {
            repo.session.baseUrl = "http://localhost:18888"
            repo.signIn("alex@store.example.test", "local-store-demo-password-123", null, "")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Family").fetchSemanticsNodes().isNotEmpty() }
            screenshot("01-home")
            compose.onNodeWithText("Family").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Photo from Maya Morgan").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Loading photo…").fetchSemanticsNodes().isEmpty()
            }
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(500)
            screenshot("02-watch")
            compose.onNodeWithContentDescription("Conversation options").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("Conversation details").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithText("Maya Morgan").fetchSemanticsNodes().isNotEmpty()
            }
            screenshot("03-people")
            compose.onNodeWithText("Done").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("Conversation options").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("Private scratchpad").performClick()
            compose.mainClock.advanceTimeBy(500)
            screenshot("04-scratchpad")
        }
    }

    private fun screenshot(name: String) {
        if (!compose.mainClock.autoAdvance) {
            compose.mainClock.advanceTimeBy(500)
            compose.mainClock.advanceTimeBy(500)
        }
        compose.waitForIdle()
        android.os.SystemClock.sleep(600)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "store-screenshots").apply { mkdirs() }
        check(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == instrumentation.targetContext.packageName) {
            "A system window is obscuring the app; do not publish this capture."
        }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
