package app.gather.media

import android.content.Context
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.TextureView

/** Camera2 preview only. Decoder surfaces and MP4 orientation metadata have different transforms. */
class CameraPreviewTextureView(context: Context) : TextureView(context) {
    var onDisplayRotationChanged: (Int) -> Unit = {}
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (display?.displayId == displayId) updateTransform()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        updateTransform()
    }

    override fun onDetachedFromWindow() {
        displayManager.unregisterDisplayListener(displayListener)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }

    fun updateTransform() {
        val rotation = display?.rotation ?: return
        onDisplayRotationChanged(rotation)
        if (width == 0 || height == 0) return
        val correction = CameraPreviewTransform.calculate(
            width, height, 1280, 720, VideoRotation.frontSensor(context), rotation,
        )
        val centerX = width / 2f
        val centerY = height / 2f
        setTransform(Matrix().apply {
            setScale(correction.scaleX, correction.scaleY, centerX, centerY)
            postRotate(correction.rotationDegrees, centerX, centerY)
        })
    }
}
