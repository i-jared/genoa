package app.gather.data

import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

class SocialSignIn(private val repository: Repository) {
    suspend fun start(provider: String): Uri {
        val verifier = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val response =
            repository.api.json(
                "POST",
                "/auth/mobile/start",
                json(
                    "provider" to provider,
                    "challenge" to
                        encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())),
                ),
            )
        val uri = Uri.parse(response.getString("url"))
        val server = Uri.parse(repository.session.baseUrl)
        require(uri.scheme == server.scheme && uri.authority == server.authority) {
            "Invalid sign-in address"
        }
        repository.session.pendingLogin =
            json(
                    "id" to response.getString("id"),
                    "verifier" to verifier,
                    "server" to repository.session.baseUrl,
                    "expires" to System.currentTimeMillis() + 600_000,
                )
                .toString()
        return uri
    }

    suspend fun complete(uri: Uri) {
        require(uri.scheme == "genoa" && uri.host == "auth")
        val pending =
            repository.session.pendingLogin?.let { org.json.JSONObject(it) }
                ?: throw ApiException(401, "Sign-in expired. Please try again.")
        val code = uri.getQueryParameter("code")
        require(
            code == pending.getString("id") &&
                pending.getString("server") == repository.session.baseUrl &&
                pending.getLong("expires") > System.currentTimeMillis()
        ) {
            "Sign-in cancelled or expired. Please try again."
        }
        val result =
            repository.api.json(
                "POST",
                "/auth/mobile/exchange",
                json("code" to code, "verifier" to pending.getString("verifier")),
            )
        repository.session.userId = result.getJSONObject("user").getString("id")
        repository.session.pendingLogin = null
    }

    private fun encode(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
