package app.gather.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    var createStep by rememberSaveable { mutableIntStateOf(0) }
    var selectedPeople by remember { mutableStateOf(setOf<String>()) }
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
                                createStep = 0
                                selectedPeople = emptySet()
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
                        createStep = 0
                        selectedPeople = emptySet()
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
                            createStep = 0
                            selectedPeople = emptySet()
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
    if (dialog == "create")
        CreateConversationDialog(
            vm,
            step = createStep,
            name = input,
            selected = selectedPeople,
            onStep = { createStep = it },
            onName = { input = it },
            onSelected = { selectedPeople = it },
            onCreate = {
                vm.create(input, selectedPeople.toList())
                dialog = ""
            },
            onClose = { dialog = "" },
        )
    else if (dialog.isNotEmpty())
        AlertDialog(
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

@Composable
private fun CreateConversationDialog(
    vm: GenoaViewModel,
    step: Int,
    name: String,
    selected: Set<String>,
    onStep: (Int) -> Unit,
    onName: (String) -> Unit,
    onSelected: (Set<String>) -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
) {
    var people by remember { mutableStateOf<List<Contact>>(emptyList()) }
    LaunchedEffect(Unit) { vm.run { people = vm.repo.people() } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (step == 0) "Choose people" else "Name this conversation") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (step == 0) {
                    Text(
                        "Choose people you already talk with. You can invite someone new after you create it."
                    )
                    if (people.isEmpty())
                        Text(
                            "People from your other conversations will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    people.forEach { person ->
                        val chosen = person.id in selected
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                onSelected(
                                    if (chosen) selected - person.id else selected + person.id
                                )
                            },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(
                                chosen,
                                {
                                    onSelected(
                                        if (it) selected + person.id else selected - person.id
                                    )
                                },
                            )
                            Avatar(person.name, identity = person.id, size = 32.dp)
                            Text(person.name, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                } else {
                    Text("Give this conversation a name.")
                    OutlinedTextField(
                        name,
                        onName,
                        label = { Text("Conversation name") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            if (step == 0)
                TextButton({
                    if (name.isBlank())
                        onName(people.filter { it.id in selected }.joinToString { it.name })
                    onStep(1)
                }) {
                    Text("Continue")
                }
            else TextButton(onCreate, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = {
            if (step == 0) TextButton(onClose) { Text("Cancel") }
            else TextButton({ onStep(0) }) { Text("Back") }
        },
    )
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
