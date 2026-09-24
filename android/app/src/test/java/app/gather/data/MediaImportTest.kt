package app.gather.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaImportTest {
    /** A provider stream that never ends, like a misbehaving or enormous document. */
    private class Endless : InputStream() {
        var read = 0L
        override fun read() = 0.also { read++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            read += len
            return len
        }
    }

    @Test
    fun copyStopsAtTheByteCapInsteadOfFillingTheDisk() {
        val source = Endless()
        val output = ByteArrayOutputStream()
        val error =
            assertThrows(ImportRejected::class.java) {
                copyWithinLimits(source, output, { Long.MAX_VALUE }, maxBytes = 1_000_000, minFreeBytes = 0)
            }
        assertTrue(error.message!!.contains("8 GB"))
        assertTrue("Reading stops promptly past the cap", source.read < 1_100_000)
        assertTrue(output.size() <= 1_000_000)
    }

    @Test
    fun copyStopsWhenFreeSpaceRunsLowPartWay() {
        var free = 10_000_000L
        val output = ByteArrayOutputStream()
        val source =
            object : InputStream() {
                override fun read() = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    free -= len
                    return len
                }
            }
        assertThrows(ImportRejected::class.java) {
            copyWithinLimits(source, output, { free }, minFreeBytes = 5_000_000, checkEvery = 1_000_000)
        }
        assertTrue(output.size() < 7_000_000)
    }

    @Test
    fun copyWithinLimitsKeepsEveryByte() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), copyWithinLimits(ByteArrayInputStream(bytes), output, { Long.MAX_VALUE }))
        assertTrue(bytes.contentEquals(output.toByteArray()))
    }

    @Test
    fun declaredSizeRejectsBeforeCopying() {
        assertThrows(ImportRejected::class.java) {
            ImportLimits.checkDeclaredSize(ImportLimits.MAX_BYTES + 1, Long.MAX_VALUE)
        }
        assertThrows(ImportRejected::class.java) {
            ImportLimits.checkDeclaredSize(1_000, ImportLimits.MIN_FREE_BYTES)
        }
        ImportLimits.checkDeclaredSize(null, Long.MAX_VALUE)
        ImportLimits.checkDeclaredSize(1_000, Long.MAX_VALUE)
    }

    @Test
    fun onlyPlayableTracksAndLengthsAreImported() {
        assertNull(importProblem("video", listOf("video/avc", "audio/mp4a-latm"), 5_000))
        assertNull(importProblem("video", listOf("video/hevc"), 5_000))
        assertNull(importProblem("audio", listOf("audio/mp4a-latm"), 5_000))
        assertNotNull(importProblem("video", listOf("video/x-vnd.on2.vp9", "audio/opus"), 5_000))
        assertNotNull("An audio-only MP4 is not a video", importProblem("video", listOf("audio/mp4a-latm"), 5_000))
        assertNotNull(importProblem("audio", listOf("audio/opus"), 5_000))
        assertNotNull("Unreadable files have no tracks", importProblem("video", emptyList(), null))
        assertNotNull(importProblem("video", listOf("video/avc"), 0))
        assertNotNull(importProblem("video", listOf("video/avc"), ImportLimits.MAX_DURATION_MS + 1))
    }
}
