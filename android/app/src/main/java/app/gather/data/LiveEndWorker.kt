package app.gather.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.gather.GenoaApplication

/** Sends a live message's remaining segments and ends it, even after the live screen is gone. */
class LiveEndWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as GenoaApplication).repository
        val id = inputData.getString("message_id") ?: return Result.failure()
        val owner = repo.drafts.liveSession(id)?.ownerId ?: return Result.success()
        if (repo.session.token != null && owner == repo.session.userId && repo.live.drain(id)) return Result.success()
        // The replay upload carries delivery; stop spending battery on ending a stale live view.
        if (runAttemptCount >= 12) {
            repo.live.abandon(id)
            return Result.failure()
        }
        return Result.retry()
    }
}
