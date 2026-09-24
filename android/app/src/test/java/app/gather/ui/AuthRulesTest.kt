package app.gather.ui

import app.gather.data.ApiException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRulesTest {
    @Test
    fun signUpRequiresEightCharacters() {
        assertFalse(canSubmitCredentials("a@b.co", "1234567", creating = true))
        assertTrue(canSubmitCredentials("a@b.co", "12345678", creating = true))
    }

    @Test
    fun existingShortPasswordsCanStillSignIn() {
        assertTrue(canSubmitCredentials("a@b.co", "123456", creating = false))
        assertFalse(canSubmitCredentials(" ", "123456", creating = false))
    }

    @Test
    fun rateLimitedResetExplainsWaiting() {
        assertEquals(
            "Too many reset requests. Wait a few minutes, then try again.",
            passwordResetFailure(ApiException(429, "Too many requests")),
        )
    }

    @Test
    fun networkFailureAsksToCheckConnection() {
        assertEquals(
            "Couldn't reach Genoa. Check your connection and try again.",
            passwordResetFailure(IOException("timeout")),
        )
    }

    @Test
    fun serverPagesIgnoreTrailingSlash() {
        assertEquals("https://genoa.test/terms", serverPage("https://genoa.test/", "terms"))
    }
}
