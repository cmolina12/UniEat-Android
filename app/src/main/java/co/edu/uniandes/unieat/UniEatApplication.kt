package co.edu.uniandes.unieat

import android.app.Application

/** Holds the [AppContainer] for the process lifetime. */
class UniEatApplication : Application() {
    val container: AppContainer by lazy { AppContainer() }
}
