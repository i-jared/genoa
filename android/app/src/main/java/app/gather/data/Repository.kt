package app.gather.data

import android.content.Context
import android.net.Uri
import androidx.work.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray

class Repository(private val context: Context, val api: Api = Api(SessionStore(context))) {
    val session = api.session
    val drafts = DraftStore(context)
    private val transport =
        object : UploadTransport {
            override suspend fun call(method: String, path: String, body: org.json.JSONObject?) =
                api.call(method, path, body)

            override suspend fun putBytes(path: String, data: ByteArray) = api.putBytes(path, data)
        }
    val uploader = DraftUploader(drafts, transport, onSent = { enrich(it) })
    // Live delivery outlives the live screen; WorkManager finishes it after process death.
    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val live =
        LiveUplink(
            drafts,
            transport,
            liveScope,
            scheduleDrain = ::scheduleLiveDrain,
            deliverReplay = ::deliverLiveReplay,
        )
    private val progressWrites = Mutex()
    private val progressSync = Mutex()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val persistenceDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val persistenceScope = CoroutineScope(SupervisorJob() + persistenceDispatcher)
    val syncRequests =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val progressChanges =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val progressErrors =
        MutableSharedFlow<String>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val progressAccount: String
        get() = "${session.baseUrl}|${session.userId}"

    fun requestSync() {
        syncRequests.tryEmit(Unit)
    }

    fun queueProgress(
        message: Message,
        position: Long,
        watched: Boolean,
        account: String = progressAccount,
    ) {
        if (session.userId.isBlank()) return
        persistenceScope.launch {
            try {
                saveWatchProgress(message, position, watched, account)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                progressErrors.tryEmit(
                    "Couldn't save watch progress. Check available storage and try again."
                )
            }
        }
    }

    suspend fun saveWatchProgress(
        message: Message,
        position: Long,
        watched: Boolean,
        account: String = progressAccount,
    ) =
        withContext(persistenceDispatcher) {
            progressWrites.withLock {
                if (account != progressAccount || session.userId.isBlank()) return@withLock
                val all = drafts.watchProgress(account)
                val previous = all.find { it.messageId == message.id }
                val finished = watched || message.watched || previous?.watched == true
                val boundedPosition = position.coerceIn(0, 86_400_000)
                if (previous?.positionMs == boundedPosition && previous.watched == finished)
                    return@withLock
                val progress =
                    LocalWatchProgress(
                        account,
                        message.id,
                        message.conversationId,
                        boundedPosition,
                        finished,
                        UUID.randomUUID().toString(),
                    )
                val db = drafts.writableDatabase
                db.beginTransaction()
                try {
                    drafts.saveWatchProgress(progress)
                    if (
                        finished &&
                            !message.watched &&
                            previous?.watched != true &&
                            message.authorId != session.userId &&
                            message.state == "ready"
                    ) {
                        drafts.cached("conversations")?.let { raw ->
                            val rows = JSONArray(raw)
                            for (i in 0 until rows.length()) {
                                val row = rows.getJSONObject(i)
                                if (row.getString("id") == message.conversationId) {
                                    row.put("unread", (row.optInt("unread") - 1).coerceAtLeast(0))
                                    if (message.kind in listOf("video", "audio"))
                                        row.put(
                                            "unwatched",
                                            (row.optInt("unwatched") - 1).coerceAtLeast(0),
                                        )
                                }
                            }
                            drafts.cache("conversations", rows.toString())
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                if (all.none { it.dirty }) scheduleWatchProgress(account)
                progressChanges.tryEmit(Unit)
                requestSync()
            }
        }

    fun scheduleWatchProgress(account: String = progressAccount) {
        if (session.userId.isBlank()) return
        val work =
            OneTimeWorkRequestBuilder<WatchProgressWorker>()
                .setInputData(workDataOf("account" to account))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    java.util.concurrent.TimeUnit.SECONDS,
                )
                .build()
        // Append only when the durable queue becomes dirty; this also covers a worker finishing.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "watch-progress:$account",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                work,
            )
    }

    suspend fun syncWatchProgress() =
        withContext(Dispatchers.IO) {
            progressSync.withLock {
                val account = progressAccount
                val base = session.baseUrl
                val token = session.token ?: return@withLock
                while (account == progressAccount && session.token != null) {
                    currentCoroutineContext().ensureActive()
                    val pending = drafts.watchProgress(account).filter { it.dirty }.take(100)
                    if (pending.isEmpty()) break
                    for (progress in pending) {
                        if (account != progressAccount || session.token == null) return@withLock
                        val request =
                            Request.Builder()
                                .url("$base/api/messages/${progress.messageId}/progress")
                                .header("Authorization", "Bearer $token")
                                .header("Origin", base)
                                .put(
                                    json(
                                            "position_ms" to progress.positionMs,
                                            "watched" to progress.watched,
                                        )
                                        .toString()
                                        .toRequestBody("application/json".toMediaType())
                                )
                                .build()
                        api.client.newCall(request).execute().use { response ->
                            if (response.code in listOf(403, 404)) {
                                progressWrites.withLock {
                                    drafts.removeWatchProgress(account, progress.messageId)
                                }
                            } else {
                                if (!response.isSuccessful)
                                    throw ApiException(
                                        response.code,
                                        "Watch progress is waiting to sync.",
                                    )
                                progressWrites.withLock {
                                    drafts.acknowledgeWatchProgress(progress)
                                }
                            }
                        }
                    }
                }
            }
        }

    fun localProgress(messages: List<Message>, cached: Boolean = false): List<Message> =
        applyWatchProgress(messages, drafts.watchProgress(progressAccount), cached)

    suspend fun recoverDrafts() =
        withContext(Dispatchers.IO) {
            if (drafts.watchProgress(progressAccount).any { it.dirty }) scheduleWatchProgress()
            if (drafts.discarded().any { it.ownerId == session.userId }) scheduleDiscardReconcile()
            live.unfinished(session.userId).forEach { scheduleLiveDrain(it.id) }
            drafts
                .all()
                .filter { it.ownerId == session.userId }
                .forEach {
                    if (it.state == DraftState.SENT) enrich(it)
                    else if (
                        it.state in
                            listOf(DraftState.QUEUED, DraftState.UPLOADING, DraftState.PROCESSING)
                    )
                        enqueue(it)
                }
            for (draft in
                drafts.all().filter {
                    it.ownerId == session.userId &&
                        it.state != DraftState.SENT &&
                        it.kind in listOf("video", "audio") &&
                        it.durationMs == 0L
                }) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(draft.file)
                    val duration =
                        retriever
                            .extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                            )
                            ?.toLongOrNull() ?: 0
                    drafts.update(
                        draft.copy(durationMs = duration, state = DraftState.SAVED, error = null)
                    )
                } catch (_: Exception) {
                    drafts.update(
                        draft.copy(
                            state = DraftState.FAILED,
                            error =
                                "Recording was interrupted before it could finish. The local file is preserved.",
                        )
                    )
                } finally {
                    retriever.release()
                }
            }
        }

    suspend fun signIn(email: String, password: String, name: String?, invite: String) {
        context.stopService(
            android.content.Intent(context, app.gather.media.PlaybackService::class.java)
        )
        val result =
            api.call(
                "POST",
                if (name == null) "/auth/sign-in/email" else "/auth/sign-up/email",
                json("email" to email, "password" to password, "name" to name),
            )
        session.userId = org.json.JSONObject(result).getJSONObject("user").getString("id")
        withContext(Dispatchers.IO) { drafts.clearCache() }
        check(session.token != null) { "The server did not return a session" }
    }

    suspend fun me(): Person {
        val j = api.json("GET", "/api/me")
        session.userId = j.getString("id")
        withContext(Dispatchers.IO) { drafts.cache("person", j.toString()) }
        return parsePerson(j)
    }

    fun cachedPerson() = drafts.cached("person")?.let { parsePerson(org.json.JSONObject(it)) }

    private fun parsePerson(j: org.json.JSONObject) =
        Person(
            j.getString("id"),
            j.getString("name"),
            j.getString("email"),
            j.optInt("ai_consent") == 1,
            j.optInt("notifications", 1) == 1,
            j.optJSONObject("capabilities")?.optBoolean("ai") == true,
        )

    suspend fun conversations(): List<Conversation> {
        syncWatchProgress()
        val a = api.array("/api/conversations")
        withContext(Dispatchers.IO) { drafts.cache("conversations", a.toString()) }
        return parseConversations(a)
    }

    fun cachedConversations() =
        drafts.cached("conversations")?.let { parseConversations(JSONArray(it)) } ?: emptyList()

    private fun parseConversations(a: JSONArray) =
        (0 until a.length()).map {
            a.getJSONObject(it).let { j ->
                Conversation(
                    j.getString("id"),
                    j.getString("title"),
                    j.optInt("member_count"),
                    j.optInt("unread"),
                    j.optInt("favorite") == 1,
                    j.optInt("muted") == 1,
                    conversationPreview(j),
                    j.optLong("last_activity", j.optLong("created_at")),
                    j.optInt("unwatched"),
                    j.optInt("named") == 1,
                    j.optJSONArray("member_names")?.let { names ->
                        (0 until names.length()).map { names.getString(it) }
                    } ?: emptyList(),
                )
            }
        }

    suspend fun messages(
        cid: String,
        before: Long? = null,
        bookmarked: Boolean = false,
    ): List<Message> {
        val a =
            api.array(
                "/api/conversations/$cid/messages?bookmarked=$bookmarked" +
                    (before?.let { "&before=$it" } ?: "")
            )
        if (before == null && !bookmarked)
            withContext(Dispatchers.IO) { drafts.cache("messages:$cid", a.toString()) }
        return withContext(Dispatchers.IO) {
            localProgress((0 until a.length()).map { a.getJSONObject(it).message() })
        }
    }

    suspend fun watchPage(
        cid: String,
        before: Long? = null,
        after: Long? = null,
        around: Long? = null,
        messageId: String? = null,
    ): WatchPage {
        val query =
            when {
                messageId != null -> "?message=${java.net.URLEncoder.encode(messageId, "UTF-8")}"
                before != null -> "?before=$before"
                after != null -> "?after=$after"
                around != null -> "?around=$around"
                else -> ""
            }
        if (query.isEmpty()) syncWatchProgress()
        val response = api.json("GET", "/api/conversations/$cid/watch$query")
        val rows = response.getJSONArray("messages")
        if (query.isEmpty())
            withContext(Dispatchers.IO) { drafts.cache("messages:$cid", rows.toString()) }
        return WatchPage(
            withContext(Dispatchers.IO) {
                localProgress((0 until rows.length()).map { rows.getJSONObject(it).message() })
            },
            response.nullable("focus_id"),
            response.getBoolean("has_older"),
            response.getBoolean("has_newer"),
            response.getLong("start_seq"),
            response.getLong("end_seq"),
        )
    }

    fun cachedMessages(cid: String) =
        drafts.cached("messages:$cid")?.let { value ->
            val a = JSONArray(value)
            localProgress((0 until a.length()).map { a.getJSONObject(it).message() }, cached = true)
        } ?: emptyList()

    suspend fun people(): List<Contact> {
        val rows = api.array("/api/people")
        return (0 until rows.length()).map { index ->
            val person = rows.getJSONObject(index)
            Contact(person.getString("id"), person.getString("name"))
        }
    }

    suspend fun createConversation(
        title: String,
        memberIds: List<String> = emptyList(),
        id: String = UUID.randomUUID().toString(),
        inviteId: String? = null,
    ): String {
        api.json(
            "POST",
            "/api/conversations",
            json("id" to id, "title" to title).apply {
                if (inviteId != null) put("invite_id", inviteId)
                if (memberIds.isNotEmpty()) put("member_ids", JSONArray(memberIds))
            },
        )
        return id
    }

    suspend fun queueText(cid: String, text: String, reply: String?, urgent: Boolean) =
        withContext(Dispatchers.IO) {
            val file =
                File(
                    File(context.filesDir, "drafts").apply { mkdirs() },
                    "${UUID.randomUUID()}.json",
                )
            file.writeText(
                json("text" to text, "urgent" to urgent)
                    .apply { reply?.let { put("reply_to", it) } }
                    .toString()
            )
            val draft = saveDraft(cid, file, "text", "application/json", 0, reply)
            enqueue(draft)
        }

    suspend fun sendText(cid: String, text: String, reply: String?, urgent: Boolean) {
        api.json(
            "POST",
            "/api/conversations/$cid/messages",
            json("id" to UUID.randomUUID().toString(), "text" to text, "urgent" to urgent).apply {
                reply?.let { put("reply_to", it) }
            },
        )
    }

    suspend fun join(token: String) =
        api.json("POST", "/api/join", json("token" to token.substringAfterLast('/')))
            .getString("id")

    suspend fun saveDraft(
        cid: String,
        file: File,
        kind: String,
        mime: String,
        duration: Long,
        reply: String?,
    ): Draft =
        withContext(Dispatchers.IO) {
            val existing =
                drafts.all().firstOrNull {
                    it.file == file.absolutePath && it.ownerId == session.userId
                }
            val draft =
                Draft(
                    existing?.id ?: UUID.randomUUID().toString(),
                    cid,
                    file.absolutePath,
                    kind,
                    mime,
                    duration,
                    reply,
                    DraftState.SAVED,
                    0,
                    null,
                    System.currentTimeMillis(),
                    session.userId,
                )
            drafts.save(draft)
            draft
        }

    suspend fun importFile(cid: String, uri: Uri, reply: String?): Draft =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(uri) ?: error("Unsupported file")
            require(mime in listOf("video/mp4", "image/jpeg", "image/png", "audio/mp4")) {
                "Please choose an MP4 video, M4A audio, JPEG, or PNG photo"
            }
            val kind =
                if (mime.startsWith("image")) "photo"
                else if (mime.startsWith("audio")) "audio" else "video"
            val directory = File(context.filesDir, "drafts").apply { mkdirs() }
            ImportLimits.checkDeclaredSize(declaredSize(uri), directory.usableSpace)
            // Reject unplayable media from the provider before copying gigabytes of it.
            val duration = if (kind == "photo") 0L else checkPlayable(uri, kind)
            if (kind == "photo") checkImage(uri)
            val file = File(directory, UUID.randomUUID().toString())
            try {
                val input =
                    context.contentResolver.openInputStream(uri)
                        ?: throw ImportRejected("This file is no longer available.")
                input.use { source ->
                    file.outputStream().use { copyWithinLimits(source, it, { directory.usableSpace }) }
                }
                saveDraft(cid, file, kind, mime, duration, reply)
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }

    private fun declaredSize(uri: Uri): Long? =
        runCatching {
                context.contentResolver
                    .query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                    }
            }
            .getOrNull()

    /** Returns the duration, or throws [ImportRejected] with the reason it can't be sent. */
    private fun checkPlayable(uri: Uri, kind: String): Long {
        val extractor = android.media.MediaExtractor()
        val tracks =
            try {
                extractor.setDataSource(context, uri, null)
                (0 until extractor.trackCount).mapNotNull {
                    extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)
                }
            } catch (_: Exception) {
                emptyList()
            } finally {
                extractor.release()
            }
        val retriever = android.media.MediaMetadataRetriever()
        val duration =
            try {
                retriever.setDataSource(context, uri)
                retriever
                    .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
            }
        importProblem(kind, tracks, duration)?.let { throw ImportRejected(it) }
        return duration!!
    }

    private fun checkImage(uri: Uri) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw ImportRejected("Genoa can’t open this photo. Choose a JPEG or PNG image.")
    }

    suspend fun enqueue(draft: Draft) =
        withContext(Dispatchers.IO) {
            val current = drafts.get(draft.id) ?: return@withContext
            if (current.state in listOf(DraftState.SAVED, DraftState.FAILED))
                if (!drafts.update(current.copy(state = DraftState.QUEUED, error = null)))
                    return@withContext
            val request =
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setInputData(workDataOf("draft_id" to draft.id))
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        java.util.concurrent.TimeUnit.SECONDS,
                    )
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("upload:${draft.id}", ExistingWorkPolicy.KEEP, request)
        }

    fun enrich(draft: Draft) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "enrich:${draft.id}",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<EnrichmentWorker>()
                    .setInputData(
                        workDataOf("draft_id" to draft.id, "auto_save" to session.autoSave)
                    )
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build(),
            )
    }

    /**
     * Discard is durable before anything else happens: the tombstone stops the worker and every
     * late write, and [reconcileDiscards] keeps deleting the server copy until it is confirmed.
     */
    suspend fun discard(draft: Draft) {
        uploader.discard(draft.id) {
            WorkManager.getInstance(context).cancelUniqueWork("upload:${draft.id}").await()
        }
        scheduleDiscardReconcile()
    }

    suspend fun reconcileDiscards(): Boolean =
        session.userId.isBlank() || session.token == null || uploader.reconcileDiscards(session.userId)

    fun scheduleDiscardReconcile() {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "discard-reconcile",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DiscardWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        java.util.concurrent.TimeUnit.SECONDS,
                    )
                    .build(),
            )
    }

    fun scheduleLiveDrain(id: String) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "live-end:$id",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LiveEndWorker>()
                    .setInputData(workDataOf("message_id" to id))
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        15,
                        java.util.concurrent.TimeUnit.SECONDS,
                    )
                    .build(),
            )
    }

    /** Saves the live replay under the live message's stable ID and queues its upload. */
    private suspend fun deliverLiveReplay(id: String, cid: String, file: File, durationMs: Long) {
        val existing = withContext(Dispatchers.IO) { drafts.get(id) }
        val draft =
            Draft(
                id,
                cid,
                file.absolutePath,
                "video",
                "video/mp4",
                durationMs,
                null,
                DraftState.SAVED,
                0,
                null,
                existing?.createdAt ?: System.currentTimeMillis(),
                session.userId,
            )
        // save() refuses a discard tombstone, so a replay the person discarded stays discarded.
        if (withContext(Dispatchers.IO) { drafts.save(draft) }) enqueue(draft)
    }

    suspend fun export(message: Message) =
        withContext(Dispatchers.IO) {
            require(message.assetId != null) { "Wait until the full recording is ready to save." }
            api.client
                .newCall(api.request("/api/media/${message.assetId}").build())
                .execute()
                .use { response ->
                    check(response.isSuccessful) { "Unable to download this message" }
                    val extension =
                        if (message.mime == "image/png") "png"
                        else if (message.kind == "photo") "jpg" else "mp4"
                    response.body!!.byteStream().use {
                        MediaExport.save(
                            context,
                            it,
                            "Genoa-${message.id}.$extension",
                            message.mime,
                            message.kind,
                        )
                    }
                }
        }

    suspend fun deleteAccount() {
        api.json("POST", "/api/account/delete", json("confirmation" to "DELETE"))
        WorkManager.getInstance(context).cancelAllWork()
        withContext(Dispatchers.IO) {
            drafts.removeWatchProgress(progressAccount)
            drafts
                .all()
                .filter { it.ownerId == session.userId }
                .forEach {
                    deleteDraftMedia(it.file)
                    drafts.remove(it.id)
                }
            // The server deleted the account's messages; its tombstones need no reconciliation.
            drafts
                .discarded()
                .filter { it.ownerId == session.userId }
                .forEach {
                    deleteDraftMedia(it.file)
                    drafts.remove(it.id)
                }
        }
    }

    suspend fun logout() {
        runCatching { api.call("DELETE", "/api/devices") }
        runCatching { api.call("POST", "/auth/sign-out") }
        context.stopService(
            android.content.Intent(context, app.gather.media.PlaybackService::class.java)
        )
        WorkManager.getInstance(context).cancelAllWork()
        session.token = null
        session.userId = ""
        withContext(Dispatchers.IO) { drafts.clearCache() }
    }
}

private fun conversationPreview(value: org.json.JSONObject): String {
    val raw =
        value.nullable("preview_data")
            ?: return value.nullable("preview")?.ifBlank { "Message" } ?: "No messages yet"
    val preview = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return "Message"
    val kind = preview.optString("kind")
    val position = preview.optLong("position_ms")
    val duration = preview.optLong("duration_ms")
    fun time(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
    if (kind in listOf("audio", "video") && position > 0 && preview.optInt("watched") == 0)
        return "Continue · ${time(position)} of ${time(duration)}"
    val description =
        when (kind) {
            "text" -> preview.optString("text")
            "audio" -> "Voice · ${time(duration)}"
            "video" -> "Video · ${time(duration)}"
            "photo" -> "Photo"
            else -> "Message"
        }
    return "${preview.optString("author")} · $description"
}
