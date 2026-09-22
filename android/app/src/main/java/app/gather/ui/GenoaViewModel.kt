package app.gather.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gather.GenoaApplication
import app.gather.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject

data class AppState(
    val loading: Boolean = false,
    val person: Person? = null,
    val conversations: List<Conversation> = emptyList(),
    val selected: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val drafts: List<Draft> = emptyList(),
    val error: String? = null,
    val note: String = "",
    val connectionError: String? = null,
    val recordingPerson: String? = null,
    val openingConversation: Boolean = false,
    val openingFocusId: String? = null,
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false,
    val loadingOlder: Boolean = false,
    val loadingNewer: Boolean = false,
)

class GenoaViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as GenoaApplication).repository
    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()
    private var socket: WebSocket? = null
    private var generation = 0
    private var focusedSeq: Long? = null
    private val refreshLock = Mutex()

    fun focus(message: Message?) {
        focusedSeq = message?.seq
    }

    private fun applyPage(page: WatchPage) {
        _state.update { current ->
            current.copy(
                messages = mergeWatchPage(current.messages, page),
                hasOlder =
                    if (current.messages.isEmpty() || page.startSeq <= current.messages.first().seq)
                        page.hasOlder
                    else current.hasOlder,
                hasNewer =
                    if (current.messages.isEmpty() || page.endSeq >= current.messages.last().seq)
                        page.hasNewer
                    else current.hasNewer,
            )
        }
    }

    init {
        viewModelScope.launch {
            repo.progressErrors.collect { error -> _state.update { it.copy(error = error) } }
        }
        viewModelScope.launch {
            repo.progressChanges.collect {
                val account = repo.progressAccount
                val updated = withContext(Dispatchers.IO) {
                    repo.drafts.watchProgress(account) to repo.cachedConversations()
                }
                if (account == repo.progressAccount) _state.update {
                    it.copy(messages = applyWatchProgress(it.messages, updated.first, cached = true), conversations = updated.second)
                }
            }
        }
        viewModelScope.launch {
            repo.drafts.changes.collect {
                val drafts = withContext(Dispatchers.IO) { repo.drafts.all() }
                _state.update {
                    it.copy(drafts = drafts.filter { d -> d.ownerId == repo.session.userId })
                }
            }
        }
        if (repo.session.token != null)
            run {
                val cached =
                    withContext(Dispatchers.IO) {
                        repo.cachedPerson() to repo.cachedConversations()
                    }
                _state.update { it.copy(person = cached.first, conversations = cached.second) }
                val person =
                    try {
                        repo.me()
                    } catch (e: ApiException) {
                        if (e.status == 401) {
                            repo.session.token = null
                            _state.update { it.copy(person = null) }
                        }
                        throw e
                    }
                repo.recoverDrafts()
                val conversations = repo.conversations()
                _state.update { it.copy(person = person, conversations = conversations) }
            }
    }

    fun registerPush() {
        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance()
                .token
                .addOnSuccessListener { token ->
                    viewModelScope.launch {
                        runCatching { repo.api.json("PUT", "/api/devices", json("token" to token)) }
                    }
                }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun run(action: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = e.message ?: "Something went wrong. Please try again.")
                }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun signIn(email: String, password: String, name: String?, invite: String, url: String) = run {
        repo.session.baseUrl = url
        repo.signIn(email, password, name, invite)
        val person = repo.me()
        repo.recoverDrafts()
        val saved =
            withContext(Dispatchers.IO) { repo.drafts.all().filter { it.ownerId == person.id } }
        val conversations = repo.conversations()
        _state.update { it.copy(person = person, conversations = conversations, drafts = saved) }
    }

    fun refresh() = run { refreshData() }

    suspend fun synchronizeWhileVisible(): Unit = coroutineScope {
        suspend fun sync() {
            if (_state.value.person == null) return
            try {
                refreshData()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(connectionError = "Couldn't refresh messages. Try refreshing again.") }
            }
        }
        launch {
            repo.syncRequests.collect {
                sync()
            }
        }
        while (isActive) {
            sync()
            delay(5_000)
        }
    }

    suspend fun openNotification(conversationId: String, messageId: String?): Boolean {
        val current = generation
        return try {
            val conversations = repo.conversations()
            currentCoroutineContext().ensureActive()
            if (current != generation) return true
            _state.update { it.copy(conversations = conversations) }
            val conversation = conversations.find { it.id == conversationId }
            if (conversation == null) {
                _state.update { it.copy(error = "This conversation is no longer available.") }
            } else {
                var unavailable = false
                val page = try {
                    repo.watchPage(conversationId, messageId = messageId)
                } catch (error: ApiException) {
                    if (messageId == null || error.status != 404) throw error
                    unavailable = true
                    repo.watchPage(conversationId)
                }
                currentCoroutineContext().ensureActive()
                if (current != generation) return true
                open(conversation, initialPage = page, notice = if (unavailable) "This message is no longer available." else null)
            }
            true
        } catch (error: ApiException) {
            if (error.status == 401) {
                repo.session.token = null
                _state.update { it.copy(person = null, selected = null, messages = emptyList(), error = "Sign in again to open this message.") }
            }
            else _state.update { it.copy(connectionError = "Couldn't open this message yet. Retrying when connected.") }
            false
        } catch (error: java.io.IOException) {
            _state.update { it.copy(connectionError = "Waiting for a connection to open this message.") }
            false
        }
    }

    private suspend fun refreshData() =
        refreshLock.withLock {
            val current = generation
            try {
                val cs = repo.conversations()
                if (current != generation) return@withLock
                _state.update {
                    val selected = it.selected?.let { s -> cs.find { c -> c.id == s.id } }
                    it.copy(
                        conversations = cs,
                        selected = selected,
                        messages = if (selected == null) emptyList() else it.messages,
                    )
                }
                val snapshot = _state.value
                snapshot.selected
                    ?.takeUnless { snapshot.openingConversation }
                    ?.let { c ->
                        val page =
                            repo.watchPage(
                                c.id,
                                around = focusedSeq ?: snapshot.messages.lastOrNull()?.seq,
                            )
                        if (current == generation && _state.value.selected?.id == c.id)
                            applyPage(page)
                    }
                if (current == generation) _state.update { it.copy(connectionError = null) }
            } catch (e: ApiException) {
                if (current != generation) return@withLock
                if (e.status == 401) {
                    repo.session.token = null
                    _state.update { it.copy(person = null, selected = null, messages = emptyList(), error = "Sign in again to sync your messages.") }
                } else if (e.status in listOf(403, 404)) {
                    _state.update { it.copy(selected = null, messages = emptyList(), error = "This conversation is no longer available.") }
                } else {
                    _state.update { it.copy(connectionError = "Couldn't refresh messages. Retrying shortly.") }
                }
            } catch (e: java.io.IOException) {
                if (current == generation)
                    _state.update {
                        it.copy(
                            connectionError =
                                "Connection unavailable. Showing saved conversations and messages."
                        )
                    }
            }
        }

    fun open(c: Conversation, explicitMessage: Message? = null, messageId: String? = null, initialPage: WatchPage? = null, notice: String? = null) {
        generation++
        val current = generation
        socket?.close(1000, "Switching conversation")
        focusedSeq = null
        _state.update {
            it.copy(
                selected = c,
                messages = emptyList(),
                note = "",
                recordingPerson = null,
                openingConversation = true,
                openingFocusId = null,
                hasOlder = false,
                hasNewer = false,
                loadingOlder = false,
                loadingNewer = false,
            )
        }
        run {
            try {
                val page = initialPage ?: try {
                    repo.watchPage(c.id, around = explicitMessage?.seq, messageId = messageId)
                } catch (error: ApiException) {
                    if (messageId == null || error.status != 404) throw error
                    repo.watchPage(c.id).also {
                        if (current == generation) _state.update { it.copy(error = "This message is no longer available.") }
                    }
                }
                if (current != generation) return@run
                _state.update {
                    it.copy(
                        messages = page.messages,
                        openingFocusId = explicitMessage?.id ?: page.focusId,
                        hasOlder = page.hasOlder,
                        hasNewer = page.hasNewer,
                        openingConversation = false,
                        error = notice ?: it.error,
                    )
                }
                val detail = repo.api.json("GET", "/api/conversations/${c.id}")
                if (current == generation)
                    _state.update { it.copy(note = detail.optString("note")) }
            } catch (error: ApiException) {
                if (current == generation)
                    _state.update { it.copy(selected = null, messages = emptyList()) }
                throw error
            } catch (error: java.io.IOException) {
                val cached = withContext(Dispatchers.IO) { repo.cachedMessages(c.id) }
                if (current == generation)
                    _state.update {
                        it.copy(
                            messages = cached,
                            openingFocusId =
                                cached.getOrNull(startingWatchIndex(cached, it.person?.id))?.id,
                            connectionError = "Connection unavailable. Showing saved messages.",
                            hasOlder = cached.isNotEmpty(),
                        )
                    }
            } finally {
                if (current == generation) _state.update { it.copy(openingConversation = false) }
            }
        }
        val url =
            repo.session.baseUrl.replaceFirst("http", "ws") + "/api/conversations/${c.id}/events"
        socket =
            repo.api.client.newWebSocket(
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${repo.session.token}")
                    .build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        viewModelScope.launch {
                            if (current != generation) return@launch
                            val data = runCatching { JSONObject(text) }.getOrNull() ?: return@launch
                            when (data.optString("type")) {
                                "changed" -> runCatching { refreshData() }
                                "recording" -> {
                                    _state.update {
                                        it.copy(recordingPerson = "Someone is recording")
                                    }
                                    delay(15000)
                                    _state.update { it.copy(recordingPerson = null) }
                                }
                                "stopped" -> _state.update { it.copy(recordingPerson = null) }
                            }
                        }
                    }
                },
            )
    }

    fun back() {
        generation++
        socket?.close(1000, "Leaving conversation")
        _state.update { it.copy(selected = null, messages = emptyList(), recordingPerson = null) }
        refresh()
    }

    fun create(title: String, memberIds: List<String> = emptyList()) = run {
        val id = repo.createConversation(title, memberIds)
        val cs = repo.conversations()
        _state.update { it.copy(conversations = cs) }
        open(cs.first { it.id == id })
    }

    fun join(token: String) = run {
        val id = repo.join(token)
        val cs = repo.conversations()
        _state.update { it.copy(conversations = cs) }
        open(cs.first { it.id == id })
    }

    fun send(text: String, reply: String?, urgent: Boolean, onSaved: () -> Unit = {}) = run {
        repo.queueText(_state.value.selected!!.id, text, reply, urgent)
        onSaved()
        refreshData()
    }

    fun reaction(
        m: Message,
        emoji: String,
        positionMs: Long = 0,
        onAccepted: (String) -> Unit = {},
    ) = run {
        val id = java.util.UUID.randomUUID().toString()
        repo.api.json(
            "PUT",
            "/api/messages/${m.id}/reaction",
            json("id" to id, "emoji" to emoji, "position_ms" to positionMs),
        )
        onAccepted(id)
        refreshData()
    }

    fun bookmark(m: Message) = run {
        repo.api.json("PUT", "/api/messages/${m.id}/bookmark", json("enabled" to !m.bookmarked))
        refreshData()
    }

    fun delete(m: Message) = run {
        repo.api.call("DELETE", "/api/messages/${m.id}")
        refreshData()
    }

    fun reminder(m: Message, whenMs: Long) = run {
        repo.api.json(
            "POST",
            "/api/messages/${m.id}/reminder",
            json("id" to java.util.UUID.randomUUID().toString(), "due_at" to whenMs),
        )
    }

    fun forward(m: Message, target: Conversation) = run {
        repo.api.json(
            "POST",
            "/api/messages/${m.id}/forward",
            json("id" to java.util.UUID.randomUUID().toString(), "conversation_id" to target.id),
        )
        refreshData()
    }

    fun saveNote(text: String) = run {
        val c = _state.value.selected ?: return@run
        repo.api.json("PUT", "/api/conversations/${c.id}/note", json("text" to text))
        _state.update { it.copy(note = text) }
    }

    fun toggleConversation(favorite: Boolean? = null, muted: Boolean? = null) = run {
        val c = _state.value.selected ?: return@run
        repo.api.json(
            "PATCH",
            "/api/conversations/${c.id}",
            JSONObject().apply {
                favorite?.let { put("favorite", it) }
                muted?.let { put("muted", it) }
            },
        )
        refreshData()
    }

    fun progress(m: Message, position: Long, watched: Boolean) {
        if (_state.value.person?.id == repo.session.userId) repo.queueProgress(m, position, watched)
    }

    fun ai(m: Message) = run {
        repo.api.json("POST", "/api/messages/${m.id}/ai")
        refreshData()
    }

    fun profile(ai: Boolean, notifications: Boolean) = run {
        repo.api.json(
            "PATCH",
            "/api/me",
            json("ai_consent" to ai, "notifications" to notifications),
        )
        val p = repo.me()
        _state.update { it.copy(person = p) }
    }

    fun signOut() = run {
        repo.logout()
        socket?.close(1000, "Signed out")
        _state.value = AppState()
    }

    fun recording(active: Boolean) {
        socket?.send(json("type" to if (active) "recording" else "stopped").toString())
    }

    fun loadOlder() = loadPage(older = true)

    fun loadNewer() = loadPage(older = false)

    private fun loadPage(older: Boolean) {
        val snapshot = _state.value
        val c = snapshot.selected ?: return
        if (
            snapshot.openingConversation ||
                (if (older) snapshot.loadingOlder || !snapshot.hasOlder
                else snapshot.loadingNewer || !snapshot.hasNewer)
        )
            return
        val cursor =
            (if (older) snapshot.messages.firstOrNull() else snapshot.messages.lastOrNull())?.seq
                ?: return
        val current = generation
        _state.update { if (older) it.copy(loadingOlder = true) else it.copy(loadingNewer = true) }
        run {
            try {
                val page =
                    if (older) repo.watchPage(c.id, before = cursor)
                    else repo.watchPage(c.id, after = cursor)
                if (current == generation && _state.value.selected?.id == c.id) {
                    applyPage(page)
                    _state.update {
                        if (older) it.copy(hasOlder = page.hasOlder)
                        else it.copy(hasNewer = page.hasNewer)
                    }
                }
            } finally {
                if (current == generation)
                    _state.update {
                        if (older) it.copy(loadingOlder = false) else it.copy(loadingNewer = false)
                    }
            }
        }
    }

    override fun onCleared() {
        socket?.close(1000, "Closed")
        super.onCleared()
    }
}
