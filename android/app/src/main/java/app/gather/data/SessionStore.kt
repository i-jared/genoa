package app.gather.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private var cachedCiphertext: String? = null
    private var cachedToken: String? = null

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("gather_session", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "gather_session",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    var token: String?
        get() =
            synchronized(this) {
                val ciphertext = prefs.getString("token", null)
                // Recheck persisted identity so another SessionStore's rotation/logout invalidates
                // this cache.
                if (ciphertext != cachedCiphertext) {
                    cachedToken =
                        ciphertext?.let { value ->
                            runCatching {
                                    val bytes = Base64.decode(value, Base64.NO_WRAP)
                                    Cipher.getInstance("AES/GCM/NoPadding").run {
                                        init(
                                            Cipher.DECRYPT_MODE,
                                            key(),
                                            GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                                        )
                                        String(doFinal(bytes.copyOfRange(12, bytes.size)))
                                    }
                                }
                                .getOrNull()
                        }
                    // A temporary Keystore failure must be retryable on the next read.
                    cachedCiphertext = if (cachedToken != null) ciphertext else null
                }
                cachedToken
            }
        set(value) =
            synchronized(this) {
                val ciphertext =
                    value?.let {
                        val cipher =
                            Cipher.getInstance("AES/GCM/NoPadding").apply {
                                init(Cipher.ENCRYPT_MODE, key())
                            }
                        Base64.encodeToString(
                            cipher.iv + cipher.doFinal(it.toByteArray()),
                            Base64.NO_WRAP,
                        )
                    }
                prefs.edit().putString("token", ciphertext).apply()
                cachedCiphertext = ciphertext
                cachedToken = value
            }

    var pendingInvite: String?
        get() = prefs.getString("pending_invite", null)
        set(value) {
            prefs.edit().putString("pending_invite", value).apply()
        }

    var pendingLogin: String?
        get() =
            prefs.getString("oauth", null)?.let { value ->
                runCatching {
                        val bytes = Base64.decode(value, Base64.NO_WRAP)
                        Cipher.getInstance("AES/GCM/NoPadding").run {
                            init(
                                Cipher.DECRYPT_MODE,
                                key(),
                                GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                            )
                            String(doFinal(bytes.copyOfRange(12, bytes.size)))
                        }
                    }
                    .getOrNull()
            }
        set(value) {
            val encrypted =
                value?.let {
                    val cipher =
                        Cipher.getInstance("AES/GCM/NoPadding").apply {
                            init(Cipher.ENCRYPT_MODE, key())
                        }
                    Base64.encodeToString(
                        cipher.iv + cipher.doFinal(it.toByteArray()),
                        Base64.NO_WRAP,
                    )
                }
            check(prefs.edit().putString("oauth", encrypted).commit()) {
                "Could not save sign-in. Try again."
            }
        }

    var userId: String
        get() = prefs.getString("user_id", "")!!
        set(value) {
            prefs.edit().putString("user_id", value).apply()
        }

    /**
     * Server-provided reason the session ended (for example a suspension). Persisted so a
     * background upload that learns of it still explains the sign-in screen on next launch.
     */
    var signedOutNotice: String?
        get() = prefs.getString("signed_out_notice", null)
        set(value) {
            prefs.edit().putString("signed_out_notice", value?.take(500)).apply()
        }

    var autoSave: Boolean
        get() = prefs.getBoolean("auto_save", false)
        set(value) {
            prefs.edit().putBoolean("auto_save", value).apply()
        }

    var baseUrl: String
        get() {
            val current = prefs.getString("base_url", "https://genoa-api.hp-accounts.workers.dev")!!
            // The renamed service keeps the same accounts and session-signing secret.
            return if (current == "https://gather-api.hp-accounts.workers.dev")
                "https://genoa-api.hp-accounts.workers.dev"
            else current
        }
        set(value) {
            require(
                value.startsWith("https://") ||
                    value.startsWith("http://10.0.2.2:") ||
                    value.startsWith("http://localhost:")
            )
            val next = value.trimEnd('/')
            if (next != baseUrl) {
                token = null
                userId = ""
            }
            prefs.edit().putString("base_url", next).apply()
        }
}
