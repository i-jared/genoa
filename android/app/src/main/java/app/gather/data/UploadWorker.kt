package app.gather.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.gather.GenoaApplication
import app.gather.R
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as GenoaApplication).repository
        val id = inputData.getString("draft_id") ?: return Result.failure()
        val draft = withContext(Dispatchers.IO) { repo.drafts.get(id) } ?: return Result.success()
        if (draft.state == DraftState.SENT) {
            repo.enrich(draft)
            return Result.success()
        }
        if (repo.session.token == null) return Result.retry()
        if (draft.ownerId != repo.session.userId) return Result.failure()
        val file = File(draft.file)
        try {
            setForeground(foreground(id))
            if (draft.kind == "text") {
                val payload =
                    withContext(Dispatchers.IO) {
                        org.json.JSONObject(file.readText()).put("id", id)
                    }
                repo.api.json(
                    "POST",
                    "/api/conversations/${draft.conversationId}/messages",
                    payload,
                )
                withContext(Dispatchers.IO) {
                    repo.drafts.save(
                        draft.copy(state = DraftState.SENT, percent = 100, error = null)
                    )
                    file.delete()
                    repo.drafts.remove(draft.id)
                }
                return Result.success()
            }
            withContext(Dispatchers.IO) {
                repo.drafts.save(draft.copy(state = DraftState.UPLOADING, error = null))
            }
            val setup =
                repo.api.json(
                    "POST",
                    "/api/uploads",
                    json(
                            "id" to id,
                            "conversation_id" to draft.conversationId,
                            "kind" to draft.kind,
                            "mime" to draft.mime,
                            "bytes" to file.length(),
                            "duration_ms" to draft.durationMs,
                        )
                        .apply { draft.replyTo?.let { put("reply_to", it) } },
                )
            if (setup.optString("state") != "complete") {
                val status = repo.api.json("GET", "/api/uploads/$id")
                val done = status.getJSONArray("parts")
                val numbers =
                    (0 until done.length()).map { done.getJSONObject(it).getInt("number") }.toSet()
                val chunk = setup.getInt("chunk_size")
                withContext(Dispatchers.IO) {
                    RandomAccessFile(file, "r").use { input ->
                        var part = 1
                        while (input.filePointer < input.length()) {
                            val length =
                                minOf(chunk.toLong(), input.length() - input.filePointer).toInt()
                            val bytes = ByteArray(length)
                            input.readFully(bytes)
                            if (part !in numbers)
                                repo.api.putBytes("/api/uploads/$id/parts/$part", bytes)
                            repo.drafts.save(
                                draft.copy(
                                    state = DraftState.UPLOADING,
                                    percent = (input.filePointer * 100 / input.length()).toInt(),
                                )
                            )
                            part++
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    repo.drafts.save(
                        draft.copy(state = DraftState.PROCESSING, percent = 100, error = null)
                    )
                }
                repo.api.json("POST", "/api/uploads/$id/complete")
            }
            withContext(Dispatchers.IO) {
                repo.drafts.save(draft.copy(state = DraftState.SENT, percent = 100, error = null))
            }
            repo.enrich(draft)
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val retry = e !is ApiException || e.status >= 500 || e.status == 429
            withContext(Dispatchers.IO) {
                repo.drafts.save(
                    draft.copy(
                        state =
                            if (e is ApiException && e.status in listOf(403, 404))
                                DraftState.BLOCKED
                            else DraftState.FAILED,
                        error =
                            if (e is ApiException && e.status in listOf(403, 404))
                                "You can’t send to this conversation anymore. Your recording is saved."
                            else e.message ?: "Upload paused. Your recording is saved.",
                    )
                )
            }
            return if (retry && runAttemptCount < 8) Result.retry() else Result.failure()
        }
    }

    private fun foreground(id: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("uploads", "Sending messages", NotificationManager.IMPORTANCE_LOW)
        )
        val notification =
            NotificationCompat.Builder(applicationContext, "uploads")
                .setSmallIcon(R.drawable.genoa_notification)
                .setContentTitle("Sending your message")
                .setContentText("Your recording is saved on this device.")
                .setOngoing(true)
                .build()
        return if (android.os.Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(
                id.hashCode(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        else ForegroundInfo(id.hashCode(), notification)
    }
}
