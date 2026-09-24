package app.gather.ui

import app.gather.data.ApiException

/** Matches the server's sign-up rule. Existing shorter passwords still sign in. */
const val MIN_PASSWORD_LENGTH = 8

fun canSubmitCredentials(email: String, password: String, creating: Boolean) =
    email.isNotBlank() &&
        password.isNotBlank() &&
        (!creating || password.length >= MIN_PASSWORD_LENGTH)

fun passwordResetFailure(error: Throwable): String =
    when {
        error is ApiException && error.status == 429 ->
            "Too many reset requests. Wait a few minutes, then try again."
        error is ApiException -> error.message ?: "Couldn't send a reset link. Try again."
        else -> "Couldn't reach Genoa. Check your connection and try again."
    }

/** Worker-hosted public pages: terms, privacy, support, reset-password. */
fun serverPage(base: String, page: String) = base.trim().trimEnd('/') + "/" + page
