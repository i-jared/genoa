package app.gather.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gather.data.json
import org.json.JSONObject

@Composable
fun GroupDialog(vm: GenoaViewModel, state: AppState, onClose: () -> Unit) {
    val conversation = state.selected ?: return
    val context = LocalContext.current
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var title by remember { mutableStateOf(conversation.title) }
    var revision by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    LaunchedEffect(revision) {
        vm.run {
            val rows =
                vm.repo.api
                    .json("GET", "/api/conversations/${conversation.id}")
                    .getJSONArray("members")
            members = (0 until rows.length()).map { rows.getJSONObject(it) }
        }
    }
    val owner =
        members.find { it.getString("id") == state.person?.id }?.optString("role") == "owner"
    GenoaDialog(
        onDismissRequest = onClose,
        title = { Text("Conversation details") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (owner) {
                    OutlinedTextField(title, { title = it }, label = { Text("Conversation name") })
                    TextButton(
                        {
                            vm.run {
                                vm.repo.api.json(
                                    "PATCH",
                                    "/api/conversations/${conversation.id}",
                                    json("title" to title),
                                )
                                vm.refresh()
                            }
                        },
                        enabled = title.isNotBlank(),
                    ) {
                        Text("Save name")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Notifications muted", Modifier.weight(1f))
                    Switch(conversation.muted, { vm.toggleConversation(muted = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Favorite conversation", Modifier.weight(1f))
                    Switch(conversation.favorite, { vm.toggleConversation(favorite = it) })
                }
                if (owner)
                    TextButton({
                        vm.run {
                            val invite =
                                vm.repo.api.json(
                                    "POST",
                                    "/api/conversations/${conversation.id}/invite",
                                )
                            context.startActivity(
                                android.content.Intent.createChooser(
                                    android.content
                                        .Intent(android.content.Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(
                                            android.content.Intent.EXTRA_TEXT,
                                            "Join our conversation on Genoa: ${invite.getString("url")}",
                                        ),
                                    "Share invitation",
                                )
                            )
                        }
                    }) {
                        Text("Invite someone")
                    }
                members.forEach { person ->
                    Text(
                        "${person.getString("name")}${if(person.optString("role")=="owner") " · Owner" else ""}"
                    )
                    if (owner && person.getString("id") != state.person?.id) {
                        FlowRow {
                            TextButton({ confirm = "remove" to person }) { Text("Remove") }
                            TextButton({ confirm = "owner" to person }) { Text("Make owner") }
                        }
                    }
                }
                if (owner)
                    Text(
                        "To leave, first make another member the owner.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                if (!owner)
                    TextButton({
                        members
                            .find { it.getString("id") == state.person?.id }
                            ?.let { confirm = "leave" to it }
                    }) {
                        Text("Leave conversation")
                    }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Done") } },
    )
    confirm?.let { (action, person) ->
        GenoaDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    if (action == "owner") "Transfer ownership?"
                    else if (action == "leave") "Leave conversation?"
                    else "Remove ${person.getString("name") }?"
                )
            },
            text = {
                Text(
                    if (action == "owner") "This person will manage invitations and membership."
                    else "Conversation and media access will end immediately."
                )
            },
            confirmButton = {
                TextButton({
                    vm.run {
                        if (action == "owner")
                            vm.repo.api.json(
                                "POST",
                                "/api/conversations/${conversation.id}/owner",
                                json("user_id" to person.getString("id")),
                            )
                        else
                            vm.repo.api.call(
                                "DELETE",
                                "/api/conversations/${conversation.id}/members/${person.getString("id")}",
                            )
                        confirm = null
                        revision++
                        vm.refresh()
                        if (action == "leave") {
                            vm.back()
                            onClose()
                        }
                    }
                }) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}
