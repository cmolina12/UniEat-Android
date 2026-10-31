package co.edu.uniandes.unieat.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "wait_records")
data class WaitRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val menuId: String,
    val minutes: Int,
    val measuredAt: Long,
)

@Dao
interface WaitRecordDao {
    @Insert
    suspend fun insert(record: WaitRecord)

    @Query("DELETE FROM wait_records")
    suspend fun deleteAll()
}

@Database(entities = [WaitRecord::class], version = 1, exportSchema = false)
abstract class WaitHistoryDatabase : RoomDatabase() {
    abstract fun records(): WaitRecordDao
}
