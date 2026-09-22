package app.gather.media

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/** Camera2 feeds one encoder; the same packets create a local MP4 and live TS segments. */
class LiveCapture(
    private val context: Context,
    private val preview: Surface,
    private val directory: File,
    private val onSegment: (File, Int, Double) -> Unit,
    private val onFinished: (File, Long) -> Unit,
    private val onError: (String) -> Unit,
    private val displayRotation: Int,
) {
    private val thread = HandlerThread("GenoaCapture").apply { start() }
    private val handler = Handler(thread.looper)
    private val microphoneThread = HandlerThread("GenoaMicrophone").apply { start() }
    private val microphoneHandler = Handler(microphoneThread.looper)
    private var audioFrames = 0L
    private var audioStartUs = 0L
    private val requestKeyFrame =
        object : Runnable {
            override fun run() {
                if (stopping || finished) return
                runCatching {
                    video?.setParameters(
                        android.os.Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        }
                    )
                }
                handler.postDelayed(this, 2000)
            }
        }
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var microphone: AudioRecord? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var ts: TransportStream? = null
    private var audioTrack = -1
    private var videoTrack = -1
    private var muxing = false
    @Volatile private var stopping = false
    @Volatile private var finished = false
    private var startUs = 0L
    private var lastUs = 0L
    private var eos = 0
    private val pending = mutableListOf<Sample>()

    private data class Sample(
        val video: Boolean,
        val bytes: ByteArray,
        val pts: Long,
        val flags: Int,
    )

    val output = File(directory, "recording.mp4")

    @SuppressLint("MissingPermission")
    fun start() {
        handler.post {
            try {
                directory.mkdirs()
                startUs = System.nanoTime() / 1000
                muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                ts = TransportStream(directory, onSegment)
                val manager = context.getSystemService(CameraManager::class.java)
                val cameraId =
                    manager.cameraIdList.firstOrNull {
                        manager
                            .getCameraCharacteristics(it)
                            .get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_FRONT
                    } ?: manager.cameraIdList.first()
                val sensor =
                    manager
                        .getCameraCharacteristics(cameraId)
                        .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val front =
                    manager
                        .getCameraCharacteristics(cameraId)
                        .get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
                muxer!!.setOrientationHint(VideoRotation.captureHint(sensor, displayRotation, front))
                val vf =
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    }
                video =
                    MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                        setCallback(callback(true), handler)
                        configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        inputSurface = createInputSurface()
                        start()
                    }
                val min =
                    AudioRecord.getMinBufferSize(
                        44100,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                microphone =
                    AudioRecord(
                            MediaRecorder.AudioSource.CAMCORDER,
                            44100,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            maxOf(min * 2, 8192),
                        )
                        .apply {
                            check(state == AudioRecord.STATE_INITIALIZED)
                            startRecording()
                        }
                val af =
                    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
                        setInteger(
                            MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, 96000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
                    }
                audio =
                    MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                        setCallback(callback(false), handler)
                        configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
                    }
                manager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(device: CameraDevice) {
                            if (stopping) {
                                device.close()
                                return
                            }
                            camera = device
                            @Suppress("DEPRECATION")
                            device.createCaptureSession(
                                listOf(preview, inputSurface!!),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(s: CameraCaptureSession) {
                                        if (stopping) {
                                            s.close()
                                            return
                                        }
                                        session = s
                                        val request =
                                            device
                                                .createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                                .apply {
                                                    addTarget(preview)
                                                    addTarget(inputSurface!!)
                                                    set(
                                                        CaptureRequest.CONTROL_AF_MODE,
                                                        CaptureRequest
                                                            .CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                                                    )
                                                }
                                                .build()
                                        s.setRepeatingRequest(request, null, handler)
                                        handler.postDelayed(requestKeyFrame, 2000)
                                    }

                                    override fun onConfigureFailed(s: CameraCaptureSession) {
                                        fail("This camera cannot start live recording.")
                                    }
                                },
                                handler,
                            )
                        }

                        override fun onDisconnected(device: CameraDevice) {
                            device.close()
                            stop()
                        }

                        override fun onError(device: CameraDevice, error: Int) {
                            device.close()
                            fail("Camera interrupted. Your recording is preserved.")
                        }
                    },
                    handler,
                )
            } catch (e: Exception) {
                fail("Unable to start live recording: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun callback(isVideo: Boolean) =
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                if (isVideo) return
                if (stopping) {
                    runCatching {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    return
                }
                microphoneHandler.post {
                    if (finished) return@post
                    try {
                        val buffer = codec.getInputBuffer(index) ?: return@post
                        buffer.clear()
                        val n =
                            microphone?.read(
                                buffer,
                                minOf(buffer.capacity(), 4096),
                                AudioRecord.READ_BLOCKING,
                            ) ?: 0
                        if (finished) return@post
                        check(n > 0 || stopping) { "Microphone read failed" }
                        if (audioStartUs == 0L) audioStartUs = System.nanoTime() / 1000
                        val timestamp = audioStartUs + audioFrames * 1_000_000 / 44100
                        audioFrames += maxOf(n, 0) / 2
                        codec.queueInputBuffer(
                            index,
                            0,
                            maxOf(n, 0),
                            timestamp,
                            if (stopping) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                        )
                    } catch (e: Exception) {
                        if (!finished)
                            handler.post {
                                android.util.Log.e("GenoaCapture", "Microphone failure", e)
                                fail("Microphone interrupted. Your recording is preserved.")
                            }
                    }
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (finished) return
                if (isVideo) {
                    videoTrack = muxer!!.addTrack(format)
                    fun copy(buffer: ByteBuffer?): ByteArray {
                        val duplicate = buffer?.duplicate() ?: return ByteArray(0)
                        return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
                    }
                    ts!!.videoConfig(
                        copy(format.getByteBuffer("csd-0")) + copy(format.getByteBuffer("csd-1"))
                    )
                } else audioTrack = muxer!!.addTrack(format)
                if (videoTrack >= 0 && audioTrack >= 0 && !muxing) {
                    muxer!!.start()
                    muxing = true
                    pending.sortedBy { it.pts }.forEach { write(it) }
                    pending.clear()
                }
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                try {
                    if (finished) return
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        val sample =
                            Sample(
                                isVideo,
                                bytes,
                                (info.presentationTimeUs - startUs).coerceAtLeast(0),
                                info.flags,
                            )
                        if (muxing) write(sample) else if (pending.size < 120) pending.add(sample)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        eos++
                        if (eos >= 2) finish()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GenoaCapture", "Encoder output failure", e)
                    fail("Recording interrupted. Your local file is preserved.")
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                android.util.Log.e("GenoaCapture", "Codec failure", error)
                fail("Media encoder interrupted. Your local file is preserved.")
            }
        }

    private fun write(sample: Sample) {
        if (finished) return
        val info =
            MediaCodec.BufferInfo().apply { set(0, sample.bytes.size, sample.pts, sample.flags) }
        muxer!!.writeSampleData(
            if (sample.video) videoTrack else audioTrack,
            ByteBuffer.wrap(sample.bytes),
            info,
        )
        if (sample.video)
            ts!!.video(
                sample.bytes,
                sample.pts,
                sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
            )
        else ts!!.audio(sample.bytes, sample.pts)
        lastUs = maxOf(lastUs, sample.pts)
    }

    fun stop() {
        if (finished) return
        handler.post {
            if (stopping || finished) return@post
            stopping = true
            runCatching { session?.stopRepeating() }
            session?.close()
            camera?.close()
            camera = null
            runCatching { video?.signalEndOfInputStream() }
            handler.postDelayed({ if (!finished) finish() }, 2500)
        }
    }

    private fun fail(message: String) {
        if (finished) return
        onError(message)
        stopping = true
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        stopping = true
        session?.close()
        camera?.close()
        runCatching { microphone?.stop() }
        microphone?.release()
        runCatching { video?.stop() }
        video?.release()
        runCatching { audio?.stop() }
        audio?.release()
        inputSurface?.release()
        ts?.close()
        if (muxing) runCatching { muxer?.stop() }
        muxer?.release()
        if (output.exists() && output.length() > 0 && muxing) onFinished(output, lastUs / 1000)
        microphoneThread.quitSafely()
        thread.quitSafely()
    }
}

/** Front-camera preview only. Stop it before LiveCapture takes the same camera. */
class LivePreview(
    private val context: Context,
    private val preview: Surface,
    private val onError: (String) -> Unit,
) {
    private val thread = HandlerThread("GenoaPreview").apply { start() }
    private val handler = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var stopped = false

    @SuppressLint("MissingPermission")
    fun start() {
        handler.post {
            if (stopped) return@post
            try {
                val manager = context.getSystemService(CameraManager::class.java)
                val cameraId =
                    manager.cameraIdList.firstOrNull {
                        manager
                            .getCameraCharacteristics(it)
                            .get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_FRONT
                    } ?: manager.cameraIdList.first()
                manager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(device: CameraDevice) {
                            if (stopped) {
                                device.close()
                                return
                            }
                            camera = device
                            @Suppress("DEPRECATION")
                            device.createCaptureSession(
                                listOf(preview),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(s: CameraCaptureSession) {
                                        if (stopped) {
                                            s.close()
                                            return
                                        }
                                        session = s
                                        val request =
                                            device
                                                .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                                .apply {
                                                    addTarget(preview)
                                                    set(
                                                        CaptureRequest.CONTROL_AF_MODE,
                                                        CaptureRequest
                                                            .CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                                    )
                                                }
                                                .build()
                                        s.setRepeatingRequest(request, null, handler)
                                    }

                                    override fun onConfigureFailed(s: CameraCaptureSession) {
                                        onError("Camera preview could not start.")
                                    }
                                },
                                handler,
                            )
                        }

                        override fun onDisconnected(device: CameraDevice) {
                            device.close()
                        }

                        override fun onError(device: CameraDevice, error: Int) {
                            device.close()
                            onError("Camera preview interrupted.")
                        }
                    },
                    handler,
                )
            } catch (e: Exception) {
                onError("Unable to open the camera preview.")
            }
        }
    }

    fun stop() {
        stopped = true
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            runCatching { session?.close() }
            session = null
            runCatching { camera?.close() }
            camera = null
            thread.quitSafely()
            done.countDown()
        }
        done.await(1, java.util.concurrent.TimeUnit.SECONDS)
    }
}
