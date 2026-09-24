package app.gather.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(vm: GenoaViewModel, state: AppState) {
    var create by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { vm.loadLoginProviders() }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(vm.repo.session.baseUrl) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val pageBase =
        server.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: vm.repo.session.baseUrl
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GenoaBrandMark(Modifier.size(48.dp))
        Text("Welcome to Genoa", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Private video conversations, at your own pace.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.signInNotice?.let { SignInNotice(it) }
        Spacer(Modifier.height(12.dp))
        state.loginProviders.forEach { provider ->
            OutlinedButton(
                onClick = {
                    vm.socialSignIn(provider) { uri ->
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        )
                    }
                },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text("Continue with ${if (provider == "apple") "Apple" else "Google"}")
            }
        }
        if (state.loginProviders.isNotEmpty())
            Text(
                "Or use your email",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        if (create)
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("Your name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        OutlinedTextField(
            email,
            { email = it },
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Email
                ),
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password,
            { password = it },
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                ),
            label = { Text("Password") },
            supportingText = { if (create) Text("At least $MIN_PASSWORD_LENGTH characters") },
            visualTransformation =
                if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None
                else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton({ showPassword = !showPassword }) {
                    Text(if (showPassword) "Hide" else "Show")
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (create) {
            Text(
                "By creating an account you agree to the Terms and Privacy Policy.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExternalLink("Terms") { uriHandler.openUri(serverPage(pageBase, "terms")) }
                ExternalLink("Privacy Policy") {
                    uriHandler.openUri(serverPage(pageBase, "privacy"))
                }
            }
        }
        Button(
            onClick = {
                vm.signIn(
                    email.trim(),
                    password,
                    if (create) name.trim().ifBlank { email.substringBefore("@").take(80) }
                    else null,
                    "",
                    server,
                )
            },
            enabled = !state.loading && canSubmitCredentials(email, password, create),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text(if (create) "Create account" else "Sign in")
        }
        if (!create && state.passwordReset)
            TextButton(
                { resetting = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Forgot password?")
            }
        TextButton({ create = !create }, modifier = Modifier.fillMaxWidth()) {
            Text(if (create) "Already have an account? Sign in" else "New here? Create an account")
        }
        TextButton({ advanced = !advanced }) { Text("Server connection") }
        if (advanced)
            OutlinedTextField(
                server,
                { server = it },
                label = { Text("Server address") },
                supportingText = { Text("Use your Genoa server's HTTPS address.") },
                modifier = Modifier.fillMaxWidth(),
            )
    }
    if (resetting) PasswordResetDialog(vm, email) { resetting = false }
}

@Composable
private fun SignInNotice(message: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            GenoaIcon("alert", null, tint = MaterialTheme.colorScheme.error)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ExternalLink(label: String, open: () -> Unit) {
    TextButton(open, Modifier.heightIn(min = 48.dp)) {
        Text(label)
        Spacer(Modifier.width(8.dp))
        GenoaIcon("external", null)
    }
}

private sealed interface ResetStep {
    data object Editing : ResetStep

    data object Sending : ResetStep

    data object Sent : ResetStep

    data class Failed(val message: String) : ResetStep
}

@Composable
private fun PasswordResetDialog(vm: GenoaViewModel, initialEmail: String, close: () -> Unit) {
    var email by rememberSaveable { mutableStateOf(initialEmail.trim()) }
    var step by remember { mutableStateOf<ResetStep>(ResetStep.Editing) }
    val scope = rememberCoroutineScope()
    fun send() {
        step = ResetStep.Sending
        scope.launch {
            step = vm.requestPasswordReset(email)?.let { ResetStep.Failed(it) } ?: ResetStep.Sent
        }
    }
    GenoaDialog(
        onDismissRequest = close,
        title = { Text(if (step == ResetStep.Sent) "Check your email" else "Reset your password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step == ResetStep.Sent)
                    Text("If an account exists for that email, we sent a reset link.")
                else {
                    Text("We'll email a link for choosing a new password.")
                    OutlinedTextField(
                        email,
                        {
                            email = it
                            if (step is ResetStep.Failed) step = ResetStep.Editing
                        },
                        enabled = step != ResetStep.Sending,
                        keyboardOptions =
                            androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Email
                            ),
                        label = { Text("Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    (step as? ResetStep.Failed)?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error)
                    }
                    if (step == ResetStep.Sending) Text("Sending reset link…")
                }
            }
        },
        confirmButton = {
            if (step == ResetStep.Sent) TextButton(close) { Text("Done") }
            else
                TextButton(
                    ::send,
                    enabled = step != ResetStep.Sending && email.contains('@'),
                ) {
                    Text(if (step is ResetStep.Failed) "Try again" else "Send reset link")
                }
        },
        dismissButton =
            if (step == ResetStep.Sent) null else ({ TextButton(close) { Text("Cancel") } }),
    )
}
