package co.edu.uniandes.unieat

import android.app.Application

class UniEatApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // BQ-02: first thing, so a crash during start-up is also recorded.
        container.crashTelemetry.install()
        container.flushScheduler.schedule()
        // Same for BQ-01 load records, including loads abandoned before the app was closed.
        container.feedLoadScheduler.schedule()
        // Crashes from a previous run (the process died before it could upload them).
        container.crashScheduler.schedule()
        // Reports saved without internet in a previous session.
        container.reportScheduler.schedule()
        // BQ-13: pilot visits left open by the previous launch end now (no network needed) and are sent
        // by WorkManager when there is network.
        container.startCachePilot()
    }
}
