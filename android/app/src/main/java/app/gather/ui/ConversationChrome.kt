package app.gather.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.gather.data.*

val QUICK_REACTION_EMOJIS = listOf("❤️", "😂", "🔥")

@Composable
fun ReactionRail(
    onReact: (String) -> Unit,
    onMore: () -> Unit,
    onMenu: () -> Unit,
    light: Boolean = false,
    onPickerPosition: (Offset) -> Unit = {},
) {
    val tint = if (light) Color(0xFFEDEFE9) else LocalContentColor.current
    val buttons: @Composable () -> Unit = {
        IconButton(onMenu) { Icon(Icons.Outlined.MoreVert, "Message options", tint = tint) }
        QUICK_REACTION_EMOJIS.forEach { emoji ->
            TextButton({ onReact(emoji) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Text(emoji, style = MaterialTheme.typography.headlineSmall)
            }
        }
        IconButton(
            onMore,
            Modifier.onGloballyPositioned { onPickerPosition(it.boundsInRoot().center) },
        ) {
            Icon(Icons.Outlined.AddReaction, "More emojis", tint = tint)
        }
    }
    if (LocalConfiguration.current.screenHeightDp < 480)
        Row(verticalAlignment = Alignment.CenterVertically) { buttons() }
    else Column(horizontalAlignment = Alignment.CenterHorizontally) { buttons() }
}

@Composable
fun CenteredRecordActions(
    onRecord: () -> Unit,
    enabled: Boolean = true,
    options: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag("record-actions"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.width(48.dp)) { options() }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onRecord, Modifier.size(56.dp), enabled = enabled) {
            GenoaIcon("video", "Record")
        }
        Spacer(Modifier.width(56.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        AndroidView(
            factory = { context ->
                androidx.emoji2.emojipicker.EmojiPickerView(context).apply {
                    setOnEmojiPickedListener { onPick(it.emoji) }
                }
            },
            modifier = Modifier.fillMaxWidth().height(360.dp).padding(bottom = 16.dp),
        )
    }
}

@Composable
fun ReplyComposer(
    vm: GenoaViewModel,
    state: AppState,
    onRecord: (Boolean, String?) -> Unit,
    onLive: (String?) -> Unit,
    light: Boolean = false,
    onComposing: (Boolean) -> Unit = {},
    recordEnabled: Boolean = true,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var composing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(composing) { onComposing(composing) }
    var attachmentMenu by remember { mutableStateOf(false) }
    var urgent by rememberSaveable { mutableStateOf(false) }
    val foreground = if (light) Color(0xFFEDEFE9) else Color.Unspecified
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val conversation = state.selected ?: return@rememberLauncherForActivityResult
            uri?.let {
                vm.run {
                    val draft = vm.repo.importFile(conversation.id, it, null)
                    vm.repo.enqueue(draft)
                }
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (composing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    text,
                    { text = it },
                    Modifier.weight(1f),
                    placeholder = { Text("Send a note…") },
                    maxLines = 4,
                    colors =
                        if (light)
                            OutlinedTextFieldDefaults.colors(
                                focusedTextColor = foreground,
                                unfocusedTextColor = foreground,
                                focusedBorderColor = foreground,
                                unfocusedBorderColor = foreground.copy(alpha = 0.6f),
                                cursorColor = foreground,
                                focusedPlaceholderColor = foreground.copy(alpha = 0.7f),
                                unfocusedPlaceholderColor = foreground.copy(alpha = 0.7f),
                            )
                        else OutlinedTextFieldDefaults.colors(),
                )
                FilledIconButton(
                    {
                        vm.send(text, null, urgent) {
                            text = ""
                            urgent = false
                        }
                    },
                    enabled = text.isNotBlank() && !state.loading,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, "Send note")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    urgent,
                    { urgent = !urgent },
                    label = { Text("Urgent note") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                Spacer(Modifier.weight(1f))
                TextButton({ composing = false }) { Text("Back to recording", color = foreground) }
            }
        } else
            CenteredRecordActions(onRecord = { onLive(null) }, enabled = recordEnabled) {
                Box {
                    IconButton({ attachmentMenu = true }) {
                        GenoaIcon("add", "Reply options", tint = foreground)
                    }
                    DropdownMenu(attachmentMenu, { attachmentMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Write a text message") },
                            onClick = {
                                composing = true
                                attachmentMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Record a private video") },
                            onClick = {
                                attachmentMenu = false
                                onRecord(false, null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Record a voice message") },
                            onClick = {
                                attachmentMenu = false
                                onRecord(true, null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Attach photo or video") },
                            onClick = {
                                attachmentMenu = false
                                importer.launch(
                                    arrayOf("video/mp4", "image/jpeg", "image/png", "audio/mp4")
                                )
                            },
                        )
                    }
                }
            }
    }
}

@Composable
fun ConversationMenu(
    conversation: Conversation,
    savedOnly: Boolean,
    onDetails: () -> Unit,
    onNote: () -> Unit,
    onToggleSaved: () -> Unit,
    onFavorite: () -> Unit,
    onMute: () -> Unit,
    tint: Color = LocalContentColor.current,
) {
    var menu by remember { mutableStateOf(false) }
    IconButton({ menu = true }) { GenoaIcon("more", "Conversation options", tint = tint) }
    DropdownMenu(menu, { menu = false }) {
        DropdownMenuItem(
            text = { Text("Conversation details") },
            onClick = {
                menu = false
                onDetails()
            },
        )
        DropdownMenuItem(
            text = { Text("Private scratchpad") },
            onClick = {
                menu = false
                onNote()
            },
        )
        DropdownMenuItem(
            text = { Text(if (savedOnly) "Show all messages" else "Show bookmarks") },
            onClick = {
                menu = false
                onToggleSaved()
            },
        )
        DropdownMenuItem(
            text = {
                Text(if (conversation.favorite) "Remove favorite" else "Favorite conversation")
            },
            onClick = {
                menu = false
                onFavorite()
            },
        )
        DropdownMenuItem(
            text = {
                Text(if (conversation.muted) "Unmute notifications" else "Mute notifications")
            },
            onClick = {
                menu = false
                onMute()
            },
        )
    }
}

@Composable
fun DraftStrip(
    drafts: List<Draft>,
    onPreview: (Draft) -> Unit,
    onSend: (Draft) -> Unit,
    onDiscard: (Draft) -> Unit,
    light: Boolean = false,
) {
    val color = if (light) Color(0xFFEDEFE9) else Color.Unspecified
    drafts.forEach { draft ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (draft.state != DraftState.PROCESSING)
                Text(
                    when (draft.state) {
                        DraftState.SAVED -> "Draft · Saved on this phone"
                        DraftState.QUEUED -> "Waiting to upload"
                        DraftState.PROCESSING -> "Processing your message…"
                        DraftState.BLOCKED -> "Can’t send to this conversation"
                        DraftState.UPLOADING -> "Uploading · ${draft.percent}%"
                        DraftState.FAILED -> "Upload paused · Recording saved"
                        else -> "Not sent yet"
                    },
                    color = color,
                    style = MaterialTheme.typography.labelLarge,
                )
            when (draft.state) {
                DraftState.UPLOADING ->
                    LinearProgressIndicator(
                        progress = { draft.percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                DraftState.PROCESSING -> MediaLoading("Preparing your message…", light = light)
                DraftState.QUEUED ->
                    Text(
                        "Your recording is saved. Upload will resume when connected.",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                    )
                else -> Unit
            }
            if (draft.state in listOf(DraftState.FAILED, DraftState.BLOCKED))
                draft.error?.let {
                    Text(it, color = color, style = MaterialTheme.typography.bodySmall)
                }
            Row {
                if (draft.kind in listOf("video", "audio") && draft.durationMs > 0)
                    TextButton({ onPreview(draft) }) { Text("Preview") }
                if (draft.state in listOf(DraftState.SAVED, DraftState.FAILED))
                    TextButton({ onSend(draft) }) {
                        Text(if (draft.state == DraftState.SAVED) "Send" else "Retry upload")
                    }
                TextButton({ onDiscard(draft) }) { Text("Discard") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSheets(
    vm: GenoaViewModel,
    state: AppState,
    dialog: String,
    selected: Message?,
    note: String,
    onNote: (String) -> Unit,
    onDialog: (String) -> Unit,
    previewDraft: Draft?,
    discardDraft: Draft?,
    onPreviewDraft: (Draft?) -> Unit,
    onDiscardDraft: (Draft?) -> Unit,
) {
    var forwardTarget by remember { mutableStateOf<Conversation?>(null) }
    if (dialog == "members") GroupDialog(vm, state) { onDialog("") }
    if (dialog == "note")
        GenoaDialog(
            onDismissRequest = { onDialog("") },
            title = { Text("Private note") },
            text = {
                Column {
                    Text("Only you can see this.")
                    OutlinedTextField(
                        note,
                        onNote,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                        placeholder = { Text("Things to remember when you reply…") },
                    )
                }
            },
            confirmButton = {
                TextButton({
                    vm.saveNote(note)
                    onDialog("")
                }) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton({ onDialog("") }) { Text("Cancel") } },
        )
    previewDraft?.let { DraftPreview(it) { onPreviewDraft(null) } }
    discardDraft?.let { draft ->
        GenoaDialog(
            onDismissRequest = { onDiscardDraft(null) },
            title = { Text("Discard this draft?") },
            text = { Text("This removes the recording from your phone and cancels sending.") },
            confirmButton = {
                TextButton({
                    vm.run {
                        vm.repo.discard(draft)
                        onDiscardDraft(null)
                    }
                }) {
                    Text("Discard")
                }
            },
            dismissButton = { TextButton({ onDiscardDraft(null) }) { Text("Keep draft") } },
        )
    }
    selected?.let { m ->
        if (dialog == "actions")
            ModalBottomSheet(onDismissRequest = { onDialog("") }) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Message from ${m.authorName}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    TextButton({
                        vm.bookmark(m)
                        onDialog("")
                    }) {
                        Icon(Icons.Outlined.BookmarkBorder, null)
                        Text(if (m.bookmarked) "  Remove bookmark" else "  Bookmark")
                    }
                    TextButton({ onDialog("forward") }) {
                        Icon(Icons.AutoMirrored.Outlined.Forward, null)
                        Text("  Forward to a conversation")
                    }
                    TextButton({ onDialog("reminder") }) {
                        GenoaIcon("clock", null)
                        Text("  Remind me to reply")
                    }
                    TextButton({
                        vm.progress(m, m.durationMs, true)
                        onDialog("")
                    }) {
                        GenoaIcon("check", null)
                        Text("  Mark watched")
                    }
                    if (m.assetId != null)
                        TextButton({
                            vm.run { vm.repo.export(m) }
                            onDialog("")
                        }) {
                            Icon(Icons.Outlined.Download, null)
                            Text("  Save to device")
                        }
                    if (m.authorId == state.person?.id)
                        TextButton({ onDialog("delete") }) {
                            Icon(Icons.Outlined.DeleteOutline, null)
                            Text("  Delete message")
                        }
                    else
                        TextButton({ onDialog("report") }) {
                            Icon(Icons.Outlined.Flag, null)
                            Text("  Report or block")
                        }
                }
            }
        if (dialog == "delete")
            GenoaDialog(
                onDismissRequest = { onDialog("") },
                title = { Text("Delete this message?") },
                text = {
                    Text(
                        "It will be removed from this conversation. Previously exported or forwarded copies cannot be recalled."
                    )
                },
                confirmButton = {
                    TextButton({
                        vm.delete(m)
                        onDialog("")
                    }) {
                        Text("Delete")
                    }
                },
                dismissButton = { TextButton({ onDialog("") }) { Text("Keep message") } },
            )
        if (dialog == "forward")
            GenoaDialog(
                onDismissRequest = { onDialog("") },
                title = { Text("Forward a copy") },
                text = {
                    Column {
                        state.conversations
                            .filter { it.id != m.conversationId }
                            .forEach { c ->
                                TextButton({
                                    forwardTarget = c
                                    onDialog("confirmForward")
                                }) {
                                    Text(c.title)
                                }
                            }
                        if (state.conversations.size < 2) Text("Create another conversation first.")
                    }
                },
                confirmButton = { TextButton({ onDialog("") }) { Text("Cancel") } },
            )
        if (dialog == "confirmForward" && forwardTarget != null)
            GenoaDialog(
                onDismissRequest = { onDialog("") },
                title = { Text("Forward to ${forwardTarget!!.title}?") },
                text = { Text("This creates a separate copy for everyone in that conversation.") },
                confirmButton = {
                    TextButton({
                        vm.forward(m, forwardTarget!!)
                        onDialog("")
                    }) {
                        Text("Forward copy")
                    }
                },
                dismissButton = { TextButton({ onDialog("forward") }) { Text("Back") } },
            )
        if (dialog == "reminder")
            GenoaDialog(
                onDismissRequest = { onDialog("") },
                title = { Text("Remind me to reply") },
                text = {
                    Column {
                        listOf(
                                "In one hour" to 3600000L,
                                "Tomorrow" to 86400000L,
                                "Next week" to 604800000L,
                            )
                            .forEach { (label, delay) ->
                                TextButton({
                                    vm.reminder(m, System.currentTimeMillis() + delay)
                                    onDialog("")
                                }) {
                                    Text(label)
                                }
                            }
                    }
                },
                confirmButton = { TextButton({ onDialog("") }) { Text("Cancel") } },
            )
        if (dialog == "report")
            GenoaDialog(
                onDismissRequest = { onDialog("") },
                title = { Text("Report or block") },
                text = {
                    Text(
                        "Reporting sends the message ID to the server administrator for review. Blocking hides this person's messages from you."
                    )
                },
                confirmButton = {
                    TextButton({
                        vm.run {
                            vm.repo.api.json(
                                "POST",
                                "/api/reports",
                                json("message_id" to m.id, "reason" to "Reported from Android"),
                            )
                        }
                        onDialog("")
                    }) {
                        Text("Report")
                    }
                },
                dismissButton = {
                    TextButton({
                        vm.run {
                            vm.repo.api.json("POST", "/api/blocks", json("user_id" to m.authorId))
                            vm.refresh()
                        }
                        onDialog("")
                    }) {
                        Text("Block person")
                    }
                },
            )
    }
}
