package app.gather.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ApiException(val status: Int, message: String) : IOException(message)

class Api(
    val session: SessionStore,
    val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build(),
) {

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
            client.newCall(request).execute().use { response ->
                val value = response.body?.string().orEmpty()
                if (!response.isSuccessful)
                    throw ApiException(
                        response.code,
                        runCatching {
                                JSONObject(value)
                                    .optString(
                                        "error",
                                        JSONObject(value).optString("message", "Request failed"),
                                    )
                            }
                            .getOrDefault("Unable to connect. Please try again."),
                    )
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
                .execute()
                .use {
                    if (!it.isSuccessful)
                        throw ApiException(it.code, "Upload paused. Your recording is saved.")
                }
        }
}

fun json(vararg pairs: Pair<String, Any?>) =
    JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
