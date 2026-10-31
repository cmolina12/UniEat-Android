package co.edu.uniandes.unieat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Local SQLite database through Room. Version 1, one table. A new table or column = new version + migration. */
@Database(entities = [PendingReport::class], version = 1, exportSchema = false)
abstract class UniEatDatabase : RoomDatabase() {
    abstract fun pendingReports(): PendingReportDao

    companion object {
        fun build(context: Context): UniEatDatabase =
            Room.databaseBuilder(context.applicationContext, UniEatDatabase::class.java, "unieat.db").build()
    }
}
