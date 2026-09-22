package app.gather.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.gather.GenoaApplication
import kotlinx.coroutines.CancellationException
import java.io.IOException

class WatchProgressWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as GenoaApplication).repository
        if (inputData.getString("account") != repo.progressAccount || repo.session.token == null) return Result.success()
        return try {
            repo.syncWatchProgress()
            repo.requestSync()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            if (error.status == 401) Result.success() else Result.retry()
        } catch (error: IOException) {
            Result.retry()
        }
    }
}
