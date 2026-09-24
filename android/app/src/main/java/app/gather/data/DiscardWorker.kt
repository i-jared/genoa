package app.gather.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.gather.GenoaApplication

/** Keeps deleting discarded drafts' server copies until the server confirms, across restarts. */
class DiscardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as GenoaApplication).repository
        return if (repo.reconcileDiscards()) Result.success() else Result.retry()
    }
}
