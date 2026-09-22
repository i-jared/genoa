package app.gather.media

/** Correction after TextureView has applied the camera sensor orientation and stretched to fit. */
data class CameraPreviewTransform(val scaleX: Float, val scaleY: Float, val rotationDegrees: Float) {
    companion object {
        fun calculate(
            viewWidth: Int,
            viewHeight: Int,
            bufferWidth: Int,
            bufferHeight: Int,
            sensorDegrees: Int,
            displayRotation: Int,
        ): CameraPreviewTransform {
            require(viewWidth > 0 && viewHeight > 0 && bufferWidth > 0 && bufferHeight > 0)
            val displayDegrees = VideoRotation.displayDegrees(displayRotation)
            val sensorSwapsAxes = sensorDegrees % 180 != 0
            val naturalWidth = (if (sensorSwapsAxes) bufferHeight else bufferWidth).toFloat()
            val naturalHeight = (if (sensorSwapsAxes) bufferWidth else bufferHeight).toFloat()
            val displaySwapsAxes = displayDegrees % 180 != 0
            val orientedWidth = if (displaySwapsAxes) naturalHeight else naturalWidth
            val orientedHeight = if (displaySwapsAxes) naturalWidth else naturalHeight
            val cover = maxOf(viewWidth / orientedWidth, viewHeight / orientedHeight)
            // Undo TextureView's independent X/Y stretch before applying uniform center crop.
            // The camera Surface already compensates for sensor rotation; only undo display rotation.
            return CameraPreviewTransform(
                naturalWidth * cover / viewWidth,
                naturalHeight * cover / viewHeight,
                -displayDegrees.toFloat(),
            )
        }
    }
}
