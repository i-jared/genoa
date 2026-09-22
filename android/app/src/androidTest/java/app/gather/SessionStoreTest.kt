package app.gather

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import app.gather.data.SessionStore
import org.junit.Assert.*
import org.junit.Test

class SessionStoreTest {
    @Test fun cachedSessionObservesRotationLogoutAndEndpointChanges() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("session-cache-test", mode)
        }
        val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val first = SessionStore(context)
            val other = SessionStore(context)
            first.token = "synthetic-session-one"
            repeat(100) { assertEquals("synthetic-session-one", other.token) }
            assertNotEquals(first.token, preferences.getString("token", null))
            first.token = "synthetic-session-two"
            assertEquals("synthetic-session-two", other.token)
            first.token = null
            assertNull(other.token)
            first.token = "synthetic-session-three"
            assertEquals("synthetic-session-three", other.token)
            first.baseUrl = "http://localhost:18891"
            assertNull(first.token)
            assertNull(other.token)
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
