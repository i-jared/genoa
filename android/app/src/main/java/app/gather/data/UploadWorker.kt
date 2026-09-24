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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as GenoaApplication).repository
        val id = inputData.getString("draft_id") ?: return Result.failure()
        // A discarded or removed draft must not even show a notification.
        if (withContext(Dispatchers.IO) { repo.drafts.isStopped(id) }) return Result.success()
        if (repo.session.token == null) return Result.retry()
        try {
            setForeground(foreground(id))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Background foreground-start limits; the upload still runs as ordinary work.
        }
        return when (repo.uploader.upload(id, repo.session.userId)) {
            UploadOutcome.Sent,
            UploadOutcome.Stopped -> Result.success()
            UploadOutcome.Retry -> if (runAttemptCount < 8) Result.retry() else Result.failure()
            UploadOutcome.Terminal -> Result.failure()
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
