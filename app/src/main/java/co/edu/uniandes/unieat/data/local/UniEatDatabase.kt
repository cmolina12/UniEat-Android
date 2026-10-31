package co.edu.uniandes.unieat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import co.edu.uniandes.unieat.data.pilot.CachePilotDao
import co.edu.uniandes.unieat.data.pilot.CachePilotSession

/**
 * The app's local relational database (SQLite through Room), stored privately in the app's data folder.
 *
 * Version 1: `pending_reports` (Camilo, offline report queue).
 * Version 2: adds `cache_pilot_sessions` (Samuel, BQ-13 pilot visits). [CachePilotSession.MIGRATION_1_2]
 * creates the new table and keeps the reports that were still waiting for a connection on phones that
 * had version 1 installed. A new table or column means raising the version and adding a migration again.
 */
@Database(entities = [PendingReport::class, CachePilotSession::class], version = 2, exportSchema = false)
abstract class UniEatDatabase : RoomDatabase() {
    abstract fun pendingReports(): PendingReportDao

    abstract fun cachePilotSessions(): CachePilotDao

    companion object {
        fun build(context: Context): UniEatDatabase =
            Room.databaseBuilder(context.applicationContext, UniEatDatabase::class.java, "unieat.db")
                .addMigrations(CachePilotSession.MIGRATION_1_2)
                .build()
    }
}
