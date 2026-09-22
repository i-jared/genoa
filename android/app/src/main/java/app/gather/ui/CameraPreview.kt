package app.gather.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.gather.media.CameraPreviewTextureView
import app.gather.media.LivePreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FrontCameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var surface by remember { mutableStateOf<Surface?>(null) }
    var preview by remember { mutableStateOf<LivePreview?>(null) }
    var frameReady by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(surface, retry) {
        preview?.stop()
        preview = null
        val target = surface ?: return@LaunchedEffect
        frameReady = false
        error = null
        val next = LivePreview(context, target) { message -> scope.launch { error = message } }
        preview = next
        next.start()
    }
    LaunchedEffect(surface, retry, frameReady) {
        if (surface != null && !frameReady) {
            delay(15_000)
            error = "Camera preview is taking longer to start."
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            preview?.stop()
            preview = null
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = {
                CameraPreviewTextureView(it).apply {
                    surfaceTextureListener =
                        object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                texture: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                texture.setDefaultBufferSize(1280, 720)
                                surface = Surface(texture)
                                updateTransform()
                            }

                            override fun onSurfaceTextureSizeChanged(
                                texture: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                updateTransform()
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                                frameReady = true
                                error = null
                            }

                            override fun onSurfaceTextureDestroyed(
                                texture: SurfaceTexture
                            ): Boolean {
                                surface?.release()
                                surface = null
                                return true
                            }
                        }
                }
            },
            update = { view -> view.updateTransform() },
            modifier = Modifier.fillMaxSize(),
        )
        if (error != null)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error.orEmpty(), color = Color(0xFFEDEFE9))
                TextButton({ retry++ }) { Text("Retry camera") }
            }
        else if (!frameReady) MediaLoading("Starting camera…")
    }
}
