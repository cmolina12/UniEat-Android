package co.edu.uniandes.unieat

import android.app.Application

class UniEatApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.flushScheduler.schedule()
        // Same for BQ-01 load records, including loads abandoned before the app was closed.
        container.feedLoadScheduler.schedule()
    }
}
