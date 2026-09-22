package app.gather.media

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRotationTest {
    @Test
    fun frontPortraitUsesTheSensorHint() {
        assertEquals(270, VideoRotation.captureHint(270, Surface.ROTATION_0, true))
        assertEquals(90, VideoRotation.captureHint(90, Surface.ROTATION_0, true))
    }

    @Test
    fun backPortraitSubtractsDisplayRotation() {
        assertEquals(90, VideoRotation.captureHint(90, Surface.ROTATION_0, false))
        assertEquals(0, VideoRotation.captureHint(90, Surface.ROTATION_90, false))
    }

    @Test
    fun sidewaysBufferNeedsCoverScaleOnAPortraitView() {
        assertEquals(1f, VideoRotation.coverScale(1080, 2640, 0), 0.001f)
        assertEquals(2640f / 1080f, VideoRotation.coverScale(1080, 2640, 270), 0.001f)
        assertEquals(2640f / 1080f, VideoRotation.coverScale(1080, 2640, 90), 0.001f)
    }
}
