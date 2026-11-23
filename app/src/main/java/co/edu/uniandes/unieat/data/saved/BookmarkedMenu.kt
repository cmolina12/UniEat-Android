package co.edu.uniandes.unieat.data.saved

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import co.edu.uniandes.unieat.core.model.DailyMenu
import kotlinx.coroutines.flow.Flow

/**
 * Samuel — Sprint 4, "Guardados". One row of `bookmarked_menus`: a menu the student saved to find it again.
 *
 * Only what the list needs is stored (restaurant, title, area, lowest price and when the menu stops
 * being valid), not the whole menu: opening a bookmark goes through the normal detail, which already
 * knows how to use the saved copies of the feed (Sprint 3) when there is no signal and the server when
 * there is. [validUntilMillis] is the server's own instant, so the list can say "posiblemente vencido"
 * without trusting the phone to know the menu's real state.
 *
 * Why Room (relational) and not a JSON file: the list is read live (a `Flow`) by two screens at once, the
 * detail button and the list, and a row is added, removed and counted by key. A table with a primary key
 * does each of those in one statement and tells every collector when it changes.
 */
@Entity(tableName = "bookmarked_menus")
data class BookmarkedMenu(
    /** Publication id: saving the same menu twice is the same row. */
    @PrimaryKey val menuId: String,
    val establishmentName: String,
    val title: String,
    val area: String,
    val lowestPriceCop: Int,
    val validUntilMillis: Long,
    val savedAtMillis: Long,
) {
    companion object {
        fun from(menu: DailyMenu, savedAtMillis: Long) = BookmarkedMenu(
            menuId = menu.id,
            establishmentName = menu.establishmentName,
            title = menu.title,
            area = menu.area,
            lowestPriceCop = menu.lowestPriceCop,
            validUntilMillis = menu.validUntil.toEpochMilli(),
            savedAtMillis = savedAtMillis,
        )

        /**
         * Database version 2 → 3. Adds the bookmarks table and keeps everything already in the database
         * (reports waiting for a connection and BQ-13 visits); a destructive migration would lose them.
         * The SQL is what Room generates for [BookmarkedMenu]; Room checks it when the database opens.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarked_menus` (" +
                        "`menuId` TEXT NOT NULL, " +
                        "`establishmentName` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`area` TEXT NOT NULL, " +
                        "`lowestPriceCop` INTEGER NOT NULL, " +
                        "`validUntilMillis` INTEGER NOT NULL, " +
                        "`savedAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`menuId`))",
                )
            }
        }
    }
}

/** The SQL the bookmarks need. Room generates the implementation at compile time (KSP). */
@Dao
interface BookmarkedMenuDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(menu: BookmarkedMenu)

    /** Newest first. Emits again every time the table changes. */
    @Query("SELECT * FROM bookmarked_menus ORDER BY savedAtMillis DESC")
    fun observeAll(): Flow<List<BookmarkedMenu>>

    /** Live 0 or 1 for the button in the detail. */
    @Query("SELECT COUNT(*) FROM bookmarked_menus WHERE menuId = :menuId")
    fun observeCount(menuId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM bookmarked_menus WHERE menuId = :menuId")
    suspend fun count(menuId: String): Int

    @Query("DELETE FROM bookmarked_menus WHERE menuId = :menuId")
    suspend fun delete(menuId: String)

    /** Keeps the [keep] newest rows so the table cannot grow forever. */
    @Query(
        "DELETE FROM bookmarked_menus WHERE menuId NOT IN " +
            "(SELECT menuId FROM bookmarked_menus ORDER BY savedAtMillis DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)

    /** Bookmarks whose menu stopped being valid before [cutoffMillis]. */
    @Query("DELETE FROM bookmarked_menus WHERE validUntilMillis < :cutoffMillis")
    suspend fun deleteValidUntilBefore(cutoffMillis: Long)

    @Query("DELETE FROM bookmarked_menus")
    suspend fun clear()
}
