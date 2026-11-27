package co.edu.uniandes.unieat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import co.edu.uniandes.unieat.data.pilot.CachePilotDao
import co.edu.uniandes.unieat.data.pilot.CachePilotSession
import co.edu.uniandes.unieat.data.saved.BookmarkedMenu
import co.edu.uniandes.unieat.data.saved.BookmarkedMenuDao

/**
 * The app's local relational database (SQLite through Room), stored privately in the app's data folder.
 *
 * Version 1: `pending_reports` (Camilo, offline report queue).
 * Version 2: adds `cache_pilot_sessions` (Samuel, BQ-13 pilot visits). [CachePilotSession.MIGRATION_1_2]
 * creates the new table and keeps the reports that were still waiting for a connection on phones that
 * had version 1 installed. A new table or column means raising the version and adding a migration again.
 * Version 3: adds `bookmarked_menus` (Samuel, Sprint 4 "Guardados"). [BookmarkedMenu.MIGRATION_2_3] keeps the
 * reports and pilot visits that were already stored.
 */
@Database(
    entities = [PendingReport::class, CachePilotSession::class, BookmarkedMenu::class],
    version = 3,
    exportSchema = false,
)
abstract class UniEatDatabase : RoomDatabase() {
    abstract fun pendingReports(): PendingReportDao

    abstract fun cachePilotSessions(): CachePilotDao

    abstract fun bookmarkedMenus(): BookmarkedMenuDao

    companion object {
        fun build(context: Context): UniEatDatabase =
            Room.databaseBuilder(context.applicationContext, UniEatDatabase::class.java, "unieat.db")
                .addMigrations(CachePilotSession.MIGRATION_1_2, BookmarkedMenu.MIGRATION_2_3)
                .build()
    }
}
