package app.gather.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gather.data.Contact
import app.gather.data.Conversation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    vm: GenoaViewModel,
    state: AppState,
    onSettings: () -> Unit,
    onRecord: (Conversation) -> Unit,
    chooseRecipient: Boolean = false,
    onDismissSelector: () -> Unit = {},
) {
    var dialog by rememberSaveable { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                title = { GenoaBrand() },
                actions = {
                    IconButton({ searching = !searching }) {
                        GenoaIcon("search", "Search conversations")
                    }
                    IconButton(onSettings) {
                        Avatar(
                            state.person?.name.orEmpty(),
                            identity = state.person?.id.orEmpty(),
                            size = 32.dp,
                            modifier = Modifier.semantics { contentDescription = "Open profile" },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (searching)
                item {
                    OutlinedTextField(
                        query,
                        { query = it },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        label = { Text("Search loaded conversations") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton({
                                query = ""
                                searching = false
                            }) {
                                GenoaIcon("close", "Close search")
                            }
                        },
                    )
                }
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        !filter,
                        { filter = false },
                        label = { Text("All") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    FilterChip(
                        filter,
                        { filter = true },
                        label = { Text("Unwatched") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton({ vm.refresh() }) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Outlined.Refresh,
                            "Refresh conversations",
                        )
                    }
                }
            }
            state.connectionError?.let { message ->
                item { ConnectionNotice(message, vm::refresh) }
            }
            val conversations =
                state.conversations.filter {
                    (!filter || it.unwatched > 0) && it.title.contains(query, ignoreCase = true)
                }
            if (conversations.isEmpty())
                item {
                    EmptyState(
                        when {
                            query.isNotBlank() -> "No conversations match ‘$query’."
                            filter -> "You’re all caught up."
                            else -> "Your conversations start here."
                        },
                        if (query.isNotBlank()) "Search the names of your loaded conversations."
                        else if (filter) "There are no unwatched videos or voice messages."
                        else "Create a conversation, then invite someone to join.",
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (query.isNotBlank()) TextButton({ query = "" }) { Text("Clear search") }
                        else if (filter)
                            TextButton({ filter = false }) { Text("View all conversations") }
                        else
                            TextButton({
                                dialog = "create"
                                input = ""
                            }) {
                                Text("New conversation")
                            }
                    }
                }
            items(conversations, key = { it.id }) { c ->
                ConversationRow(c) { vm.open(c) }
                HorizontalDivider(
                    Modifier.padding(start = 76.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            item {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton({
                        dialog = "create"
                        input = ""
                    }) {
                        GenoaIcon("add", null)
                        Spacer(Modifier.width(8.dp))
                        Text("New conversation")
                    }
                    TextButton({
                        dialog = "join"
                        input = ""
                    }) {
                        Text("Join with invitation")
                    }
                }
            }
        }
    }
    if (chooseRecipient)
        ModalBottomSheet(onDismissRequest = onDismissSelector) {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(
                        "Choose a conversation",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                items(state.conversations, key = { it.id }) { c ->
                    ConversationRow(c) {
                        onDismissSelector()
                        onRecord(c)
                    }
                }
                item {
                    FlowRow(Modifier.padding(16.dp)) {
                        TextButton({
                            onDismissSelector()
                            dialog = "create"
                            input = ""
                        }) {
                            Text("New conversation")
                        }
                        TextButton({
                            onDismissSelector()
                            dialog = "join"
                            input = ""
                        }) {
                            Text("Join with invitation")
                        }
                    }
                }
            }
        }
    if (dialog == "create") CreateConversationSheet(vm, onClose = { dialog = "" })
    else if (dialog.isNotEmpty())
        GenoaDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Join with invitation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste the invitation link or code.")
                    OutlinedTextField(
                        input,
                        { input = it },
                        label = { Text("Invitation") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        vm.join(input)
                        dialog = ""
                    },
                    enabled = input.isNotBlank(),
                ) {
                    Text("Join")
                }
            },
            dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } },
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateConversationSheet(vm: GenoaViewModel, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var people by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val conversationId = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val inviteId = rememberSaveable { java.util.UUID.randomUUID().toString() }
    var link by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loadingPeople by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.loadContacts {
            people = it
            loadingPeople = false
        }
    }
    fun start(action: String) {
        if (busy) return
        busy = true
        vm.run {
            try {
                val url = vm.startConversation(conversationId, selected, inviteId, action == "open")
                link = url
                when (action) {
                    "copy" -> {
                        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager)
                            .setPrimaryClip(
                                android.content.ClipData.newPlainText("Genoa invitation", url)
                            )
                        copied = true
                    }
                    "share" ->
                        context.startActivity(
                            android.content.Intent.createChooser(
                                android.content
                                    .Intent(android.content.Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "Join me on Genoa: $url",
                                    ),
                                "Invite to Genoa",
                            )
                        )
                    else -> onClose()
                }
            } finally {
                busy = false
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Start a conversation", style = MaterialTheme.typography.titleLarge)
            Text(
                "Choose your people, or send a link. You can name it later.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    { start("copy") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(if (copied) "Link copied" else "Copy invite link")
                }
                OutlinedButton(
                    { start("share") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("Share link")
                }
            }
            Text("Your contacts", style = MaterialTheme.typography.titleMedium)
            if (loadingPeople) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (people.isEmpty())
                Text(
                    "People you talk with will appear here. Share a link to invite someone new.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 320.dp)) {
                items(people, key = { it.id }) { person ->
                    val chosen = person.id in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(
                            enabled = !busy && link == null
                        ) {
                            selected = if (chosen) selected - person.id else selected + person.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(person.name, identity = person.id, size = 40.dp)
                        Text(
                            person.name,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Checkbox(chosen, onCheckedChange = null)
                    }
                }
            }
            if (link != null)
                Text(
                    "Your conversation is ready. The invitation link works for 7 days.",
                    style = MaterialTheme.typography.bodySmall,
                )
            Button(
                { start("open") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text(
                    if (busy) "Preparing…"
                    else if (link != null) "Open conversation"
                    else if (selected.isEmpty()) "Start conversation"
                    else
                        "Start with ${selected.size} ${if (selected.size == 1) "person" else "people"}"
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(c: Conversation, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 80.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(c.title, identity = c.id)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                c.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                c.preview,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (c.muted) Text("Notifications muted", style = MaterialTheme.typography.labelMedium)
        }
        Column(
            Modifier.widthIn(min = 48.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                timeLabel(c.activity),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (c.unread > 0)
                Text(
                    "${c.unread} new",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "${c.unread} new messages, including ${c.unwatched} unwatched videos or voice messages"
                        },
                )
            else if (c.favorite) Text("Favorite", style = MaterialTheme.typography.labelMedium)
        }
    }
}
