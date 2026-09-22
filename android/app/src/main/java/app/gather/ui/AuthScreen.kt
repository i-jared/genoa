package app.gather.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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
            supportingText = { if (create) Text("At least 6 characters") },
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
            enabled =
                !state.loading &&
                    email.isNotBlank() &&
                    password.isNotBlank() &&
                    (!create || password.length >= 6),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text(if (create) "Create account" else "Sign in")
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
}
