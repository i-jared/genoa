package app.gather.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun AccountControls(vm: GenoaViewModel, onDeleted: () -> Unit) {
    val context = LocalContext.current
    var delete by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf("") }
    var reminders by remember { mutableStateOf<List<JSONObject>?>(null) }
    var notice by remember { mutableStateOf("") }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null)
                vm.run {
                    val data = vm.repo.api.call("GET", "/api/account/export")
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)!!.use {
                            it.write(data.toByteArray())
                        }
                    }
                    notice = "Account data exported. Save recordings from their playback screen."
                }
        }
    OutlinedButton(
        {
            vm.run {
                val rows = vm.repo.api.array("/api/reminders")
                reminders = (0 until rows.length()).map { rows.getJSONObject(it) }
            }
        },
        Modifier.fillMaxWidth(),
    ) {
        Text("Your reminders")
    }
    OutlinedButton({ export.launch("Genoa-account.json") }, Modifier.fillMaxWidth()) {
        Text("Export account data")
    }
    TextButton({ delete = true }, Modifier.fillMaxWidth()) {
        Text("Delete account", color = MaterialTheme.colorScheme.error)
    }
    if (notice.isNotEmpty()) Text(notice)
    reminders?.let { rows ->
        AlertDialog(
            onDismissRequest = { reminders = null },
            title = { Text("Your reminders") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (rows.isEmpty()) Text("No reminders yet. Add one from a message's options.")
                    rows.forEach { row ->
                        Text(row.getString("title"))
                        Text(
                            java.text.DateFormat.getDateTimeInstance()
                                .format(java.util.Date(row.getLong("due_at"))) +
                                if (row.optInt("sent") == 1) " · Sent" else ""
                        )
                        TextButton({
                            vm.run {
                                vm.repo.api.call("DELETE", "/api/reminders/${row.getString("id")}")
                                reminders =
                                    rows.filter { it.getString("id") != row.getString("id") }
                            }
                        }) {
                            Text("Remove reminder")
                        }
                    }
                }
            },
            confirmButton = { TextButton({ reminders = null }) { Text("Done") } },
        )
    }
    if (delete)
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("Delete your account?") },
            text = {
                Column {
                    Text(
                        "Your sign-in, messages, notes, and reminders will be deleted. Copies others already forwarded or downloaded cannot be recalled. Type DELETE to confirm."
                    )
                    OutlinedTextField(
                        confirmation,
                        { confirmation = it },
                        singleLine = true,
                        label = { Text("Confirmation") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        vm.run {
                            vm.repo.deleteAccount()
                            vm.signOut()
                            onDeleted()
                        }
                    },
                    enabled = confirmation == "DELETE",
                ) {
                    Text("Delete account")
                }
            },
            dismissButton = { TextButton({ delete = false }) { Text("Keep account") } },
        )
}
