package app.gather.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.gather.BuildConfig
import app.gather.data.DraftState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: GenoaViewModel, state: AppState, onBack: () -> Unit) {
    val person = state.person ?: return
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var autoSave by remember { mutableStateOf(vm.repo.session.autoSave) }
    var notificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var signingOut by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Profile", style = MaterialTheme.typography.headlineMedium) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(person.name, identity = person.id, size = 80.dp)
                Text(person.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    person.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProfileGroup("Notifications", "bell") {
                Preference(
                    "Message notifications",
                    "Receive a notification when a message arrives.",
                    person.notifications,
                ) {
                    vm.profile(person.aiConsent, it)
                }
                if (!notificationsAllowed) {
                    Text(
                        "Notifications are blocked in Android settings.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton({
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }) {
                        Text("Open system settings")
                    }
                }
            }
            ProfileGroup("Privacy", "lock") {
                Text(
                    "Only conversation members can access your messages. Media is processed by the Genoa server; it is not end-to-end encrypted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProfileGroup("Media", "media") {
                Preference(
                    "Save my recordings",
                    "Keep a separate copy in your gallery after sending. Deleting a message does not delete gallery copies.",
                    autoSave,
                ) {
                    autoSave = it
                    vm.repo.session.autoSave = it
                }
            }
            ProfileGroup("Appearance", "appearance") {
                Text(
                    "Uses your device’s light or dark appearance.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ProfileGroup("Account", "profile") { AccountControls(vm, onBack) }
            ProfileGroup("Help and policies", "info") {
                val base = vm.repo.session.baseUrl
                listOf("Support" to "support", "Terms" to "terms", "Privacy Policy" to "privacy")
                    .forEach { (label, page) ->
                        TextButton(
                            { uriHandler.openUri(serverPage(base, page)) },
                            Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(label)
                            Spacer(Modifier.width(8.dp))
                            GenoaIcon("external", null)
                        }
                    }
            }
            ProfileGroup("About Genoa", "info") {
                GenoaBrand(size = 32.dp, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "JPEG/PNG photos and MP4/M4A recordings. Beta limit: 8 GB and 24 hours per message. Published history does not expire.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    { uriHandler.openUri("https://talkgenoa.com") },
                    Modifier.heightIn(min = 48.dp),
                ) {
                    Text("talkgenoa.com")
                    Spacer(Modifier.width(8.dp))
                    GenoaIcon("external", null)
                }
            }
            OutlinedButton(
                {
                    if (state.drafts.any { it.state != DraftState.SENT }) signingOut = true
                    else {
                        vm.signOut()
                        onBack()
                    }
                },
                Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text("Sign out")
            }
        }
    }
    if (signingOut)
        GenoaDialog(
            onDismissRequest = { signingOut = false },
            title = { Text("Sign out with saved drafts?") },
            text = {
                Text(
                    "Sending will pause. Your drafts stay on this phone and will be available when you sign back into this account."
                )
            },
            confirmButton = {
                TextButton({
                    signingOut = false
                    vm.signOut()
                    onBack()
                }) {
                    Text("Sign out")
                }
            },
            dismissButton = { TextButton({ signingOut = false }) { Text("Stay signed in") } },
        )
}

@Composable
private fun ProfileGroup(title: String, icon: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GenoaIcon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun Preference(
    title: String,
    description: String,
    checked: Boolean,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked, change, modifier = Modifier.semantics { contentDescription = title })
    }
}
