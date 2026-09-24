package app.gather

import android.app.Application
import app.gather.data.Repository
import com.google.firebase.crashlytics.FirebaseCrashlytics

class GenoaApplication : Application() {
    val repository by lazy { Repository(this) }

    override fun onCreate() {
        super.onCreate()
        // Only non-identifying build facts. Never attach user IDs, emails, tokens, URLs,
        // recordings, or transcripts to crash reports.
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("version_name", BuildConfig.VERSION_NAME)
                setCustomKey("version_code", BuildConfig.VERSION_CODE)
                setCustomKey("build_type", BuildConfig.BUILD_TYPE)
            }
        }
    }
}
