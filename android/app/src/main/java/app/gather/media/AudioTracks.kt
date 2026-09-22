package app.gather.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Copy AAC packets without decoding. Bound each AI request to two minutes of audio. */
object AudioTracks {
    data class Chunk(val file: File, val offsetMs: Long)

    fun extract(source: File, output: File): List<Chunk> {
        output.mkdirs()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor
                        .getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: return emptyList()
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val chunks = mutableListOf<Chunk>()
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var muxer: MediaMuxer? = null
            var target = 0
            var start = 0L
            try {
                while (extractor.sampleTime >= 0) {
                    val timestamp = extractor.sampleTime
                    if (muxer == null || timestamp - start >= 120_000_000) {
                        muxer?.run {
                            stop()
                            release()
                        }
                        start = timestamp
                        val file = File(output, "audio-${chunks.size}.m4a")
                        muxer =
                            MediaMuxer(
                                file.absolutePath,
                                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                            )
                        target = muxer.addTrack(format)
                        muxer.start()
                        chunks.add(Chunk(file, start / 1000))
                    }
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(
                        0,
                        size,
                        timestamp - start,
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        else 0,
                    )
                    muxer.writeSampleData(target, buffer, info)
                    extractor.advance()
                }
            } finally {
                muxer?.run {
                    runCatching { stop() }
                    release()
                }
            }
            return chunks
        } finally {
            extractor.release()
        }
    }
}
