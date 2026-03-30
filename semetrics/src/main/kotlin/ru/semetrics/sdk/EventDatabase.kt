package ru.semetrics.sdk

import android.content.Context
import androidx.room.*

/** Сущность в базе данных — одно ожидающее событие. */
@Entity(tableName = "queued_events")
internal data class QueuedEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventName: String,
    val userId: String?,
    val anonymousId: String?,
    val sessionId: String?,
    val propertiesJson: String?,    // JSON-строка словаря свойств
    val clientTs: String,           // ISO-8601
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
internal interface EventDao {
    @Insert
    suspend fun insert(event: QueuedEvent)

    @Query("SELECT * FROM queued_events ORDER BY id ASC LIMIT :limit")
    suspend fun getBatch(limit: Int): List<QueuedEvent>

    @Query("DELETE FROM queued_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM queued_events")
    suspend fun count(): Int
}

@Database(entities = [QueuedEvent::class], version = 1, exportSchema = false)
internal abstract class EventDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile private var instance: EventDatabase? = null

        fun getInstance(context: Context): EventDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "semetrics_queue.db",
                ).build().also { instance = it }
            }
    }
}
