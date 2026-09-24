package app.gather.data

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A file that cannot be imported; the message is shown to the person as is. */
class ImportRejected(message: String) : IOException(message)

object ImportLimits {
    /** Matches the server's per-message limit. */
    const val MAX_BYTES = 8L * 1024 * 1024 * 1024
    /** Importing must never leave the phone unable to record or run. */
    const val MIN_FREE_BYTES = 512L * 1024 * 1024
    const val MAX_DURATION_MS = 24L * 60 * 60 * 1000

    fun tooLarge() = ImportRejected("This file is larger than Genoa’s 8 GB limit. Choose a shorter video.")

    fun lowStorage() =
        ImportRejected(
            "There isn’t enough free space on this phone to import this file. Free up space and try again."
        )

    /** Rejects before copying when the provider reports a size. */
    fun checkDeclaredSize(declaredBytes: Long?, freeBytes: Long) {
        if (declaredBytes == null || declaredBytes < 0) return
        if (declaredBytes > MAX_BYTES) throw tooLarge()
        if (freeBytes - declaredBytes < MIN_FREE_BYTES) throw lowStorage()
    }
}

/** Why a video or audio file can't be sent, from its track formats and duration; null if usable. */
fun importProblem(kind: String, trackMimes: List<String>, durationMs: Long?): String? {
    val video = trackMimes.filter { it.startsWith("video/") }
    val audio = trackMimes.filter { it.startsWith("audio/") }
    val problem =
        when (kind) {
            "video" ->
                if (video.none { it in PLAYABLE_VIDEO })
                    "Genoa can’t play this video. Choose an MP4 with H.264 or HEVC video."
                else null
            "audio" ->
                if (audio.none { it in PLAYABLE_AUDIO })
                    "Genoa can’t play this audio file. Choose an M4A with AAC audio."
                else null
            else -> null
        }
    if (problem != null) return problem
    if (durationMs == null || durationMs <= 0)
        return "This file has no playable length. It may be damaged or still downloading."
    if (durationMs > ImportLimits.MAX_DURATION_MS)
        return "This recording is longer than Genoa’s 24-hour limit."
    return null
}

private val PLAYABLE_VIDEO = setOf("video/avc", "video/hevc")
private val PLAYABLE_AUDIO = setOf("audio/mp4a-latm")

/**
 * Copies at most [maxBytes], checking free space as it goes. Throws [ImportRejected] as soon as a
 * limit is crossed; the caller deletes the partial copy.
 */
fun copyWithinLimits(
    input: InputStream,
    output: OutputStream,
    freeBytes: () -> Long,
    maxBytes: Long = ImportLimits.MAX_BYTES,
    minFreeBytes: Long = ImportLimits.MIN_FREE_BYTES,
    checkEvery: Long = 8L * 1024 * 1024,
): Long {
    if (freeBytes() < minFreeBytes) throw ImportLimits.lowStorage()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    var sinceCheck = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw ImportLimits.tooLarge()
        output.write(buffer, 0, read)
        sinceCheck += read
        if (sinceCheck >= checkEvery) {
            sinceCheck = 0
            if (freeBytes() < minFreeBytes) throw ImportLimits.lowStorage()
        }
    }
    return total
}
