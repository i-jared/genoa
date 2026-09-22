package app.gather.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/** Minimal MPEG-TS muxer for H.264/AAC. Segments share a continuous 90kHz clock. */
class TransportStream(
    private val directory: File,
    private val onSegment: (File, Int, Double) -> Unit,
) {
    private var output: FileOutputStream? = null
    private var file: File? = null
    private var number = 0
    private var segmentStart = 0L
    private var lastPts = 0L
    private val continuity = mutableMapOf<Int, Int>()
    private var config = ByteArray(0)

    fun videoConfig(bytes: ByteArray) {
        config = bytes
    }

    fun video(data: ByteArray, ptsUs: Long, key: Boolean) {
        if (output == null && !key) return
        if (key && (output == null || ptsUs - segmentStart >= 2_000_000)) {
            closeSegment(ptsUs)
            segmentStart = ptsUs
            file = File(directory, "segment-${number}.ts")
            output = FileOutputStream(file!!)
            tables()
        }
        val aud = byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte())
        pes(256, 0xE0, aud + (if (key) config else ByteArray(0)) + annexB(data), ptsUs, key)
        lastPts = ptsUs
    }

    fun audio(data: ByteArray, ptsUs: Long, sampleRate: Int = 44100, channels: Int = 1) {
        if (output == null) return
        val index =
            listOf(
                    96000,
                    88200,
                    64000,
                    48000,
                    44100,
                    32000,
                    24000,
                    22050,
                    16000,
                    12000,
                    11025,
                    8000,
                )
                .indexOf(sampleRate)
                .coerceAtLeast(0)
        val length = data.size + 7
        val adts =
            byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(),
                ((1 shl 6) or (index shl 2) or (channels shr 2)).toByte(),
                (((channels and 3) shl 6) or (length shr 11)).toByte(),
                (length shr 3).toByte(),
                (((length and 7) shl 5) or 0x1F).toByte(),
                0xFC.toByte(),
            )
        pes(257, 0xC0, adts + data, ptsUs, false)
    }

    private fun tables() {
        section(
            0,
            byteArrayOf(
                0x00,
                0xB0.toByte(),
                0x0D,
                0,
                1,
                0xC1.toByte(),
                0,
                0,
                0,
                1,
                0xF0.toByte(),
                0,
            ),
        )
        section(
            4096,
            byteArrayOf(
                0x02,
                0xB0.toByte(),
                0x17,
                0,
                1,
                0xC1.toByte(),
                0,
                0,
                0xE1.toByte(),
                0,
                0xF0.toByte(),
                0,
                0x1B,
                0xE1.toByte(),
                0,
                0xF0.toByte(),
                0,
                0x0F,
                0xE1.toByte(),
                1,
                0xF0.toByte(),
                0,
            ),
        )
    }

    private fun section(pid: Int, data: ByteArray) {
        var crc = -1
        for (b in data) {
            crc = crc xor ((b.toInt() and 255) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        packets(
            pid,
            byteArrayOf(0) +
                data +
                byteArrayOf(
                    (crc ushr 24).toByte(),
                    (crc ushr 16).toByte(),
                    (crc ushr 8).toByte(),
                    crc.toByte(),
                ),
            null,
            false,
        )
    }

    private fun pes(pid: Int, stream: Int, data: ByteArray, ptsUs: Long, key: Boolean) {
        val pts = (ptsUs * 90 / 1000) and 0x1FFFFFFFFL
        val length = if (pid == 256) 0 else data.size + 8
        val header =
            byteArrayOf(
                0,
                0,
                1,
                stream.toByte(),
                (length shr 8).toByte(),
                length.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                5,
                (0x21 or (((pts shr 30).toInt() and 7) shl 1)).toByte(),
                (pts shr 22).toByte(),
                ((((pts shr 15).toInt() and 127) shl 1) or 1).toByte(),
                (pts shr 7).toByte(),
                (((pts.toInt() and 127) shl 1) or 1).toByte(),
            )
        packets(pid, header + data, if (pid == 256) pts else null, key)
    }

    private fun packets(pid: Int, payload: ByteArray, pcr: Long?, key: Boolean) {
        var offset = 0
        while (offset < payload.size) {
            val first = offset == 0
            val hasPcr = first && pcr != null
            val minimum = if (hasPcr) 8 else 0
            val size = minOf(184 - minimum, payload.size - offset)
            val adaptation = 184 - size
            val packet = ByteArray(188) { 0xFF.toByte() }
            val cc = continuity.getOrDefault(pid, 0)
            continuity[pid] = (cc + 1) and 15
            packet[0] = 0x47
            packet[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            packet[2] = pid.toByte()
            packet[3] = ((if (adaptation > 0) 0x30 else 0x10) or cc).toByte()
            if (adaptation > 0) {
                packet[4] = (adaptation - 1).toByte()
                if (adaptation > 1) {
                    packet[5] =
                        ((if (hasPcr) 0x10 else 0) or (if (first && key) 0x40 else 0)).toByte()
                    if (hasPcr) {
                        val v = pcr!!
                        packet[6] = (v shr 25).toByte()
                        packet[7] = (v shr 17).toByte()
                        packet[8] = (v shr 9).toByte()
                        packet[9] = (v shr 1).toByte()
                        packet[10] = (((v.toInt() and 1) shl 7) or 0x7E).toByte()
                        packet[11] = 0
                    }
                }
            }
            payload.copyInto(packet, 4 + adaptation, offset, offset + size)
            output?.write(packet)
            offset += size
        }
    }

    private fun annexB(bytes: ByteArray): ByteArray {
        if (
            bytes.size >= 4 &&
                bytes[0] == 0.toByte() &&
                bytes[1] == 0.toByte() &&
                (bytes[2] == 1.toByte() || bytes[2] == 0.toByte() && bytes[3] == 1.toByte())
        )
            return bytes
        val out = ByteArrayOutputStream()
        var i = 0
        while (i + 4 <= bytes.size) {
            val n =
                ((bytes[i].toInt() and 255) shl 24) or
                    ((bytes[i + 1].toInt() and 255) shl 16) or
                    ((bytes[i + 2].toInt() and 255) shl 8) or
                    (bytes[i + 3].toInt() and 255)
            i += 4
            if (n <= 0 || i + n > bytes.size) return bytes
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(bytes, i, n)
            i += n
        }
        return out.toByteArray()
    }

    private fun closeSegment(end: Long) {
        output?.run {
            flush()
            close()
        }
        output = null
        file?.let {
            onSegment(it, number++, ((end - segmentStart) / 1_000_000.0).coerceAtLeast(.1))
        }
        file = null
    }

    fun close() {
        closeSegment(lastPts + 33_333)
    }
}
