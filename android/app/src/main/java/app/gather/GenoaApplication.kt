package app.gather

import android.app.Application
import app.gather.data.Repository

class GenoaApplication : Application() {
    val repository by lazy { Repository(this) }
}
