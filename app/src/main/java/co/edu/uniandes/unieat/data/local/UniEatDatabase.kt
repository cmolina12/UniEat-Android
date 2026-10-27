package co.edu.uniandes.unieat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's local relational database (SQLite through Room), stored privately in the app's data folder.
 * Version 1 has one table; a new table or column means raising the version and adding a migration.
 */
@Database(entities = [PendingReport::class], version = 1, exportSchema = false)
abstract class UniEatDatabase : RoomDatabase() {
    abstract fun pendingReports(): PendingReportDao

    companion object {
        fun build(context: Context): UniEatDatabase =
            Room.databaseBuilder(context.applicationContext, UniEatDatabase::class.java, "unieat.db").build()
    }
}
