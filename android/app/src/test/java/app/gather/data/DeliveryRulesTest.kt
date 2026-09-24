package app.gather.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryRulesTest {
    @Test
    fun storageQuotaAndUnplayableMediaAreTerminalWithTheServerMessage() {
        val quota = classifyUploadFailure(ApiException(413, "Your Genoa storage is full.", "storage_quota"))
        assertFalse(quota.retry)
        assertEquals(DraftState.FAILED, quota.state)
        assertEquals("Your Genoa storage is full.", quota.message)
        val media = classifyUploadFailure(ApiException(422, "This file couldn't be played.", "unsupported_media"))
        assertFalse(media.retry)
        assertEquals("This file couldn't be played.", media.message)
    }

    @Test
    fun temporaryProblemsRetryAndRevokedAccessBlocks() {
        assertTrue(classifyUploadFailure(IOException("offline")).retry)
        assertTrue(classifyUploadFailure(ApiException(503, "unavailable")).retry)
        assertTrue(classifyUploadFailure(ApiException(429, "slow down")).retry)
        val blocked = classifyUploadFailure(ApiException(403, "no"))
        assertFalse(blocked.retry)
        assertEquals(DraftState.BLOCKED, blocked.state)
        assertFalse(classifyUploadFailure(ApiException(403, "suspended", "account_suspended")).retry)
    }

    @Test
    fun liveSegmentErrorsThatCannotRecoverStopSharing() {
        listOf(401, 403, 404, 409, 410, 413, 422).forEach {
            assertNotNull("$it is terminal", liveTerminalMessage(ApiException(it, "x")))
        }
        listOf(500, 502, 503, 429, 408).forEach {
            assertNull("$it is retried", liveTerminalMessage(ApiException(it, "x")))
        }
        assertNull(liveTerminalMessage(IOException("offline")))
    }

    @Test
    fun liveBufferIsBoundedByCountBytesAndFreeSpace() {
        val limits = LiveLimits(maxPendingSegments = 3, maxPendingBytes = 100, minFreeBytes = 50)
        assertEquals(LiveOffer.ACCEPTED, liveOffer(limits, 2, 50, 10, 1_000))
        assertEquals(LiveOffer.BUFFER_FULL, liveOffer(limits, 3, 50, 10, 1_000))
        assertEquals(LiveOffer.BUFFER_FULL, liveOffer(limits, 1, 95, 10, 1_000))
        assertEquals(LiveOffer.LOW_STORAGE, liveOffer(limits, 0, 0, 10, 49))
        assertNotNull(liveOfferMessage(LiveOffer.BUFFER_FULL))
        assertNotNull(liveOfferMessage(LiveOffer.LOW_STORAGE))
    }
}
