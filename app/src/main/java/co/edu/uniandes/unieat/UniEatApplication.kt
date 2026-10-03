package co.edu.uniandes.unieat

import android.app.Application

/** Holds the [AppContainer] for the process lifetime. */
class UniEatApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Events left from a previous run (e.g. closed while offline) upload once there is network.
        container.flushScheduler.schedule()
        // Same for BQ-01 load records, including loads abandoned before the app was closed.
        container.feedLoadScheduler.schedule()
    }
}
