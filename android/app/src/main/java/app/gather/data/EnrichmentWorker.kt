package app.gather.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.gather.GenoaApplication
import app.gather.media.AudioTracks
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Delivery is committed before these optional derivatives run. */
class EnrichmentWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val repo = (applicationContext as GenoaApplication).repository
            val id = inputData.getString("draft_id") ?: return@withContext Result.failure()
            val draft = repo.drafts.get(id) ?: return@withContext Result.success()
            if (repo.session.token == null) return@withContext Result.retry()
            if (draft.ownerId != repo.session.userId) return@withContext Result.failure()
            val file = File(draft.file)
            if (!file.exists()) return@withContext Result.success()
            try {
                if (draft.kind in listOf("video", "audio")) {
                    val chunks =
                        AudioTracks.extract(file, File(applicationContext.cacheDir, "audio-$id"))
                    for ((number, chunk) in chunks.withIndex()) {
                        repo.api.putBytes(
                            "/api/messages/$id/audio/$number?offset_ms=${chunk.offsetMs}",
                            chunk.file.readBytes(),
                        )
                        chunk.file.delete()
                    }
                }
                val marker = File(file.parentFile, "$id.exported")
                if (inputData.getBoolean("auto_save", false) && !marker.exists()) {
                    val extension =
                        if (draft.mime == "image/png") "png"
                        else if (draft.kind == "photo") "jpg" else "mp4"
                    file.inputStream().use {
                        MediaExport.save(
                            applicationContext,
                            it,
                            "Genoa-$id.$extension",
                            draft.mime,
                            draft.kind,
                        )
                    }
                    marker.writeText("saved")
                }
                file.delete()
                marker.delete()
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiException && e.status in listOf(403, 404)) {
                    file.delete()
                    return@withContext Result.success()
                }
                repo.drafts.save(
                    draft.copy(
                        error = "Message sent. Caption preparation or gallery saving will retry."
                    )
                )
                if (runAttemptCount < 8) Result.retry() else Result.failure()
            }
        }
}
