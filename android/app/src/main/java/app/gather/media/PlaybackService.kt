package app.gather.media

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.gather.GenoaApplication
import app.gather.R
import app.gather.data.Message
import androidx.media3.common.Player
import android.os.Handler
import android.os.Looper

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private var persistProgress: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.genoa_notification)
            }
        )
        val repo = (application as GenoaApplication).repository
        val http =
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(
                    mapOf("Authorization" to "Bearer ${repo.session.token.orEmpty()}")
                )
        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val player =
            ExoPlayer.Builder(this).setMediaSourceFactory(source)
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    500,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                ).build())
                .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
            }
        val account = repo.progressAccount
        var playedId: String? = null
        fun save(item: androidx.media3.common.MediaItem?, position: Long, finished: Boolean = false) {
            if (item == null || item.mediaId != playedId) return
            @Suppress("DEPRECATION")
            val message = item.mediaMetadata.extras?.getSerializable("genoa_message") as? Message ?: return
            repo.queueProgress(message, position, finished, account)
        }
        persistProgress = { save(player.currentMediaItem, player.currentPosition, player.playbackState == Player.STATE_ENDED) }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) playedId = player.currentMediaItem?.mediaId
                else persistProgress?.invoke()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) persistProgress?.invoke()
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId) {
                    save(oldPosition.mediaItem, oldPosition.positionMs)
                }
            }
        })
        progressHandler.post(object : Runnable {
            override fun run() {
                persistProgress?.invoke()
                progressHandler.postDelayed(this, 5_000)
            }
        })
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.uid == android.os.Process.myUid()) session else null

    override fun onDestroy() {
        persistProgress?.invoke()
        persistProgress = null
        progressHandler.removeCallbacksAndMessages(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
