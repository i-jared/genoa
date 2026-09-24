package app.gather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorTest {
    @Test
    fun apiSuspensionEndsTheSessionWithTheServerMessage() {
        val error =
            apiError(
                403,
                """{"error":"This account is suspended. Contact support for help.","code":"account_suspended"}""",
            )
        assertTrue(error.accountSuspended)
        assertEquals("This account is suspended. Contact support for help.", error.message)
    }

    @Test
    fun betterAuthSignInSuspensionUsesMessageField() {
        val error =
            apiError(403, """{"message":"This account is suspended.","code":"ACCOUNT_SUSPENDED"}""")
        assertTrue(error.accountSuspended)
        assertEquals("This account is suspended.", error.message)
    }

    @Test
    fun suspensionWithoutMessageStillExplainsItself() {
        val error = apiError(403, """{"code":"account_suspended"}""")
        assertTrue(error.accountSuspended)
        assertEquals(SUSPENDED_FALLBACK, error.message)
    }

    @Test
    fun ordinaryForbiddenIsNotASuspension() {
        // A 403 for a conversation the person left must not sign them out.
        assertFalse(apiError(403, """{"error":"Forbidden"}""").accountSuspended)
        assertFalse(apiError(401, """{"error":"x","code":"account_suspended"}""").accountSuspended)
    }

    @Test
    fun otherCodesArePreservedForCallers() {
        val error = apiError(413, """{"error":"Your Genoa storage is full.","code":"storage_quota"}""")
        assertEquals("storage_quota", error.code)
        assertEquals("Your Genoa storage is full.", error.message)
    }

    @Test
    fun nonJsonBodyGetsConnectionMessage() {
        val error = apiError(502, "<html>Bad gateway</html>")
        assertEquals("Unable to connect. Please try again.", error.message)
        assertEquals(null, error.code)
    }
}
