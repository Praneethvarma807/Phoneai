package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "command_history")
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commandText: String,
    val transcription: String,
    val inputLanguage: String,
    val engineUsed: String,
    val aiInterpretation: String,
    val selectedToolsJson: String,
    val executionPlanJson: String,
    val resultStatus: String,
    val resultSummary: String,
    val failureDetails: String? = null,
    val durationMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_notes")
data class LocalNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_reminders")
data class LocalReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val triggerAtMillis: Long,
    val notes: String = "",
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloaded_models")
data class DownloadedModelEntity(
    @PrimaryKey val modelId: String,
    val displayName: String,
    val fileName: String,
    val localPath: String,
    val expectedSizeBytes: Long,
    val downloadedBytes: Long,
    val sha256Checksum: String? = null,
    val status: String,
    val isActive: Boolean = false,
    val preferredBackend: String = "CPU",
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface CommandHistoryDao {
    @Query("SELECT * FROM command_history ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CommandHistoryEntity>>

    @Query("SELECT * FROM command_history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CommandHistoryEntity>>

    @Query(
        "SELECT * FROM command_history WHERE commandText LIKE '%' || :query || '%' " +
            "OR aiInterpretation LIKE '%' || :query || '%' " +
            "OR resultSummary LIKE '%' || :query || '%' ORDER BY timestamp DESC"
    )
    fun search(query: String): Flow<List<CommandHistoryEntity>>

    @Query("SELECT * FROM command_history ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<CommandHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CommandHistoryEntity): Long

    @Query("DELETE FROM command_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM command_history")
    suspend fun clearAll()
}

@Dao
interface LocalNoteDao {
    @Query("SELECT * FROM local_notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<LocalNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: LocalNoteEntity): Long

    @Query("DELETE FROM local_notes WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface LocalReminderDao {
    @Query("SELECT * FROM local_reminders ORDER BY triggerAtMillis ASC")
    fun observeAll(): Flow<List<LocalReminderEntity>>

    @Query("SELECT * FROM local_reminders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LocalReminderEntity?

    @Query("SELECT * FROM local_reminders WHERE isCompleted = 0 ORDER BY triggerAtMillis ASC")
    suspend fun getPendingOnce(): List<LocalReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: LocalReminderEntity): Long

    @androidx.room.Update
    suspend fun updateReminder(reminder: LocalReminderEntity)

    @Query("UPDATE local_reminders SET title = :title, triggerAtMillis = :triggerAtMillis, notes = :notes WHERE id = :id")
    suspend fun updateReminder(id: Long, title: String, triggerAtMillis: Long, notes: String)

    @Query("UPDATE local_reminders SET isCompleted = :completed WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean)

    @Query("DELETE FROM local_reminders WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface DownloadedModelDao {
    @Query("SELECT * FROM downloaded_models ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadedModelEntity>>

    @Query("SELECT * FROM downloaded_models WHERE modelId = :modelId LIMIT 1")
    suspend fun getById(modelId: String): DownloadedModelEntity?

    @Query("SELECT * FROM downloaded_models WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveModel(): DownloadedModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: DownloadedModelEntity)

    @Query("UPDATE downloaded_models SET isActive = (modelId = :modelId)")
    suspend fun setActiveModel(modelId: String)

    @Query("UPDATE downloaded_models SET isActive = 0")
    suspend fun clearActiveModel()

    @Query("DELETE FROM downloaded_models WHERE modelId = :modelId")
    suspend fun deleteById(modelId: String)
}

@Database(
    entities = [
        CommandHistoryEntity::class,
        LocalNoteEntity::class,
        LocalReminderEntity::class,
        DownloadedModelEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CommanderDatabase : RoomDatabase() {
    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun localNoteDao(): LocalNoteDao
    abstract fun localReminderDao(): LocalReminderDao
    abstract fun downloadedModelDao(): DownloadedModelDao

    companion object {
        @Volatile
        private var instance: CommanderDatabase? = null

        fun build(context: Context): CommanderDatabase {
            val current = instance
            if (current != null && current.isOpen) return current
            return synchronized(this) {
                val existing = instance
                if (existing != null && existing.isOpen) {
                    existing
                } else {
                    Room.databaseBuilder(
                        context.applicationContext,
                        CommanderDatabase::class.java,
                        "ai_phone_commander.db"
                    ).fallbackToDestructiveMigration(dropAllTables = true)
                        .build()
                        .also { instance = it }
                }
            }
        }
    }
}
