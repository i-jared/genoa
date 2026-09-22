package app.gather.media

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraPreviewTransformTest {
    @Test
    fun tallPortraitPreviewDoesNotRotateTheSensorTwiceOrMagnifyBothAxes() {
        for (sensor in listOf(90, 270)) {
            val correction = CameraPreviewTransform.calculate(1080, 2640, 1280, 720, sensor, Surface.ROTATION_0)
            assertEquals(0f, correction.rotationDegrees, 0.001f)
            assertEquals(1f, correction.scaleY, 0.001f)
            assertEquals(1.375f, correction.scaleX, 0.001f)
        }
    }

    @Test
    fun matchingPortraitFrameNeedsNoExtraScale() {
        val correction = CameraPreviewTransform.calculate(720, 1280, 1280, 720, 270, Surface.ROTATION_0)
        assertEquals(1f, correction.scaleX, 0.001f)
        assertEquals(1f, correction.scaleY, 0.001f)
    }

    @Test
    fun reversePortraitChangesRotationWithoutChangingFraming() {
        val upright = CameraPreviewTransform.calculate(1080, 2640, 1280, 720, 270, Surface.ROTATION_0)
        val reversed = CameraPreviewTransform.calculate(1080, 2640, 1280, 720, 270, Surface.ROTATION_180)
        assertEquals(upright.scaleX, reversed.scaleX, 0.001f)
        assertEquals(upright.scaleY, reversed.scaleY, 0.001f)
        assertEquals(-180f, reversed.rotationDegrees, 0.001f)
    }

    @Test
    fun allOrientationsPreserveSquarePixelsAndUseOnlyTheCropNeededToFillTheView() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (rotation in 0..3) {
                for ((width, height) in listOf(1080 to 2640, 2640 to 1080, 800 to 800, 600 to 900)) {
                    val correction = CameraPreviewTransform.calculate(width, height, 1280, 720, sensor, rotation)
                    // Model the platform's sensor orientation followed by its initial view stretch.
                    val nativeWidth = if (sensor % 180 == 0) 1280f else 720f
                    val nativeHeight = if (sensor % 180 == 0) 720f else 1280f
                    val pixelWidth = width / nativeWidth * correction.scaleX
                    val pixelHeight = height / nativeHeight * correction.scaleY
                    assertEquals("Preview distorts pixels", pixelWidth, pixelHeight, 0.001f)
                    val correctedWidth = width * correction.scaleX
                    val correctedHeight = height * correction.scaleY
                    val displayedWidth = if (rotation % 2 == 0) correctedWidth else correctedHeight
                    val displayedHeight = if (rotation % 2 == 0) correctedHeight else correctedWidth
                    assertTrue("Blank horizontal edge", displayedWidth >= width - 0.001f)
                    assertTrue("Blank vertical edge", displayedHeight >= height - 0.001f)
                    assertTrue("Unnecessary zoom", abs(displayedWidth-width) < 0.001f || abs(displayedHeight-height) < 0.001f)
                    assertEquals(-90f * rotation, correction.rotationDegrees, 0.001f)
                }
            }
        }
    }
}
