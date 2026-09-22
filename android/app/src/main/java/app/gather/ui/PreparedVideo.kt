package app.gather.ui

import android.view.LayoutInflater
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnPreDraw
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.gather.R
import app.gather.media.VideoRotation
import kotlinx.coroutines.delay

/** Each source gets a fresh decoder view; its first frame stays covered until layout is ready. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PreparedVideo(player: Player?, mediaId: String, source: String, liveRotation: Int?, onReady: () -> Unit = {}) {
    val context = LocalContext.current
    val notifyReady by rememberUpdatedState(onReady)
    val view =
        remember(mediaId, source) {
            (LayoutInflater.from(context).inflate(R.layout.genoa_player, null) as PlayerView)
                .apply {
                    useController = false
                    setKeepContentOnPlayerReset(false)
                    setShutterBackgroundColor(android.graphics.Color.rgb(23, 37, 31))
                    resizeMode =
                        if (liveRotation == null) AspectRatioFrameLayout.RESIZE_MODE_FIT
                        else AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
        }
    var revealed by remember(view) { mutableStateOf(false) }
    var buffering by remember(view) { mutableStateOf(true) }
    var failed by remember(view) { mutableStateOf(false) }
    var timedOut by remember(view) { mutableStateOf(false) }
    var retry by remember(view) { mutableIntStateOf(0) }
    DisposableEffect(view, player, liveRotation, retry) {
        var disposed = false
        var firstFrame = false
        fun matches() =
            player?.currentMediaItem?.let {
                it.mediaId == mediaId && it.localConfiguration?.uri?.toString() == source
            } == true
        fun transform() {
            if (liveRotation != null)
                (view.videoSurfaceView as? TextureView)?.let {
                    VideoRotation.fit(it, 1280, 720, liveRotation)
                }
        }
        fun revealAfterLayout() {
            if (
                revealed || !firstFrame || !matches() || player?.playbackState != Player.STATE_READY
            )
                return
            view.post {
                if (!disposed) {
                    transform()
                    view.doOnPreDraw {
                        if (
                            !disposed &&
                                matches() &&
                                firstFrame &&
                                player?.videoSize?.width != 0 &&
                                player?.videoSize?.height != 0 &&
                                player?.playbackState == Player.STATE_READY &&
                                view.width > 0
                        ) {
                            revealed = true
                            notifyReady()
                            timedOut = false
                        }
                    }
                    view.invalidate()
                }
            }
        }
        fun synchronize() {
            if (!matches()) return
            if (view.player !== player) view.player = player
            buffering =
                player?.playbackState != Player.STATE_READY &&
                    player?.playbackState != Player.STATE_ENDED
            failed = player?.playerError != null
            if (revealed && !buffering && !failed) timedOut = false
            revealAfterLayout()
        }
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = synchronize()

                override fun onRenderedFirstFrame() {
                    if (matches()) {
                        firstFrame = true
                        revealAfterLayout()
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) = revealAfterLayout()
            }
        val layout =
            android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                transform()
                revealAfterLayout()
            }
        view.addOnLayoutChangeListener(layout)
        player?.addListener(listener)
        synchronize()
        onDispose {
            disposed = true
            player?.removeListener(listener)
            view.removeOnLayoutChangeListener(layout)
            view.player = null
        }
    }
    LaunchedEffect(revealed, buffering, failed, retry) {
        if ((!revealed || buffering) && !failed) {
            delay(15_000)
            timedOut = true
        }
    }
    Box(Modifier.fillMaxSize().testTag(if (revealed) "video-ready" else "video-preparing")) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (!revealed || buffering || failed || timedOut) {
            Box(
                Modifier.fillMaxSize()
                    .then(if (!revealed) Modifier.background(CameraBackdrop) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (failed || timedOut) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (failed) "Couldn’t load this video."
                            else "This video is taking longer to load.",
                            color = Color(0xFFEDEFE9),
                        )
                        TextButton(
                            onClick = {
                                failed = false
                                timedOut = false
                                retry++
                                revealed = false
                                player?.prepare()
                                player?.play()
                            }
                        ) {
                            Text("Retry video")
                        }
                    }
                } else MediaLoading(if (revealed) "Buffering video…" else "Loading video…")
            }
        }
    }
}
