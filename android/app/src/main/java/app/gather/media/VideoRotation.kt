package app.gather.media

import android.content.Context
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Surface
import android.view.TextureView
import android.view.View

/** MPEG-TS has no MP4 rotation metadata, so live display applies the capture hint. */
object VideoRotation {
    fun displayDegrees(rotation: Int): Int =
        when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

    fun captureHint(sensor: Int, displayRotation: Int, front: Boolean): Int {
        val degrees = displayDegrees(displayRotation)
        return if (front) (sensor + degrees) % 360 else (sensor - degrees + 360) % 360
    }

    fun frontSensor(context: Context): Int {
        val manager = context.getSystemService(CameraManager::class.java)
        val id =
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.first()
        return manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: 0
    }

    fun frontHint(context: Context, displayRotation: Int): Int =
        captureHint(frontSensor(context), displayRotation, true)

    fun coverScale(viewWidth: Int, viewHeight: Int, rotation: Int): Float {
        if (viewWidth == 0 || viewHeight == 0 || rotation % 180 == 0) return 1f
        return maxOf(viewWidth.toFloat() / viewHeight, viewHeight.toFloat() / viewWidth)
    }

    fun fit(view: TextureView, videoWidth: Int, videoHeight: Int, rotation: Int) {
        if (videoWidth == 0 || videoHeight == 0) return
        if (view.width == 0 || view.height == 0) {
            view.addOnLayoutChangeListener(
                object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View,
                        left: Int,
                        top: Int,
                        right: Int,
                        bottom: Int,
                        oldLeft: Int,
                        oldTop: Int,
                        oldRight: Int,
                        oldBottom: Int,
                    ) {
                        if (view.width == 0 || view.height == 0) return
                        view.removeOnLayoutChangeListener(this)
                        fit(view, videoWidth, videoHeight, rotation)
                    }
                }
            )
            return
        }
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat(), centerX, centerY)
        val scale = coverScale(view.width, view.height, rotation)
        if (scale != 1f) matrix.postScale(scale, scale, centerX, centerY)
        view.setTransform(matrix)
    }
}
