package app.gather.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ApiException(val status: Int, message: String, val code: String? = null) :
    IOException(message) {
    /** API routes use `account_suspended`; Better Auth sign-in uses `ACCOUNT_SUSPENDED`. */
    val accountSuspended: Boolean
        get() = status == 403 && code.equals("account_suspended", ignoreCase = true)
}

const val SUSPENDED_FALLBACK = "This account is suspended. Contact support for help."

/** Reads the server's `{ error | message, code }` shape; non-JSON bodies get a generic message. */
fun apiError(status: Int, body: String): ApiException {
    val parsed = runCatching { JSONObject(body) }.getOrNull()
    val code = parsed?.optString("code")?.takeIf { it.isNotBlank() }
    val message =
        parsed?.let { it.optString("error").ifBlank { it.optString("message") } }
            ?.takeIf { it.isNotBlank() }
    val fallback =
        when {
            status == 403 && code.equals("account_suspended", ignoreCase = true) ->
                SUSPENDED_FALLBACK
            parsed == null -> "Unable to connect. Please try again."
            else -> "Request failed"
        }
    return ApiException(status, message ?: fallback, code)
}

class Api(
    val session: SessionStore,
    val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build(),
) {

    private val suspended = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Emits the server's message when any call learns the account is suspended. */
    val suspensions = suspended.asSharedFlow()

    // Centralized so uploads and other background callers also stop using a revoked session.
    // Drafts stay on the phone, scoped to the account, exactly as with session expiry.
    private fun endSuspendedSession(message: String) {
        session.token = null
        session.signedOutNotice = message
        suspended.tryEmit(message)
    }

    fun request(path: String): Request.Builder =
        Request.Builder().url(session.baseUrl + path).header("Origin", session.baseUrl).apply {
            session.token?.let { header("Authorization", "Bearer $it") }
        }

    suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        extra: Map<String, String> = emptyMap(),
    ): String =
        withContext(Dispatchers.IO) {
            val request =
                request(path)
                    .apply { extra.forEach { (k, v) -> header(k, v) } }
                    .method(
                        method,
                        if (method == "GET" || method == "HEAD") null
                        else
                            (body?.toString() ?: "{}").toRequestBody(
                                "application/json".toMediaType()
                            ),
                    )
                    .build()
            client.newCall(request).executeCancellable().use { response ->
                val value = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = apiError(response.code, value)
                    if (error.accountSuspended) endSuspendedSession(error.message!!)
                    throw error
                }
                response.header("set-auth-token")?.let { session.token = it }
                value
            }
        }

    suspend fun json(method: String, path: String, body: JSONObject? = null) =
        withContext(Dispatchers.IO) { JSONObject(call(method, path, body)) }

    suspend fun array(path: String) = withContext(Dispatchers.IO) { JSONArray(call("GET", path)) }

    suspend fun putBytes(path: String, data: ByteArray) =
        withContext(Dispatchers.IO) {
            client
                .newCall(
                    request(path)
                        .put(data.toRequestBody("application/octet-stream".toMediaType()))
                        .build()
                )
                .executeCancellable()
                .use {
                    if (!it.isSuccessful) {
                        val error = apiError(it.code, it.body?.string().orEmpty())
                        if (error.accountSuspended) {
                            endSuspendedSession(error.message!!)
                            throw error
                        }
                        throw ApiException(
                            it.code,
                            "Upload paused. Your recording is saved.",
                            error.code,
                        )
                    }
                }
        }
}

fun json(vararg pairs: Pair<String, Any?>) =
    JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
