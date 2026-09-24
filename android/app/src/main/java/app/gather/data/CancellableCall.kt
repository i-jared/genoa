package app.gather.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/**
 * Blocking OkHttp execution that is aborted when the calling coroutine is cancelled. Discard waits
 * for a stopped upload to finish, so an in-flight part must not keep transferring until timeout.
 */
suspend fun Call.executeCancellable(): Response = coroutineScope {
    val returned = AtomicBoolean(false)
    val watcher =
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!returned.get()) this@executeCancellable.cancel()
            }
        }
    try {
        execute()
    } finally {
        returned.set(true)
        watcher.cancel()
    }
}
