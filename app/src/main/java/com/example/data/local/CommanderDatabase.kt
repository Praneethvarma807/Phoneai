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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

@Entity(tableName = "personal_memories")
data class PersonalMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoryKey: String,
    val category: String,
    val summaryLabel: String,
    val encryptedValue: String,
    val isEncrypted: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "automation_routines")
data class AutomationRoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val voiceTriggers: String,
    val stepsJson: String,
    val scheduleHour: Int? = null,
    val scheduleMinute: Int? = null,
    val isEnabled: Boolean = true,
    val requiresConfirmation: Boolean = false,
    val isPreset: Boolean = false,
    val lastExecutedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "routine_execution_logs")
data class RoutineExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val routineName: String,
    val triggerSource: String,
    val status: String,
    val summary: String,
    val executedAt: Long = System.currentTimeMillis()
)

@Dao
interface PersonalMemoryDao {
    @Query("SELECT * FROM personal_memories ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PersonalMemoryEntity>>

    @Query("SELECT * FROM personal_memories ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<PersonalMemoryEntity>

    @Query("SELECT * FROM personal_memories WHERE memoryKey = :key LIMIT 1")
    suspend fun getByKey(key: String): PersonalMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: PersonalMemoryEntity): Long

    @Query("DELETE FROM personal_memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM personal_memories WHERE memoryKey = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM personal_memories")
    suspend fun clearAll()
}

@Dao
interface AutomationRoutineDao {
    @Query("SELECT * FROM automation_routines ORDER BY isPreset DESC, id ASC")
    fun observeAll(): Flow<List<AutomationRoutineEntity>>

    @Query("SELECT * FROM automation_routines ORDER BY isPreset DESC, id ASC")
    suspend fun getAllOnce(): List<AutomationRoutineEntity>

    @Query("SELECT * FROM automation_routines WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AutomationRoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(routine: AutomationRoutineEntity): Long

    @androidx.room.Update
    suspend fun update(routine: AutomationRoutineEntity)

    @Query("UPDATE automation_routines SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE automation_routines SET lastExecutedAt = :executedAt WHERE id = :id")
    suspend fun markExecuted(id: Long, executedAt: Long)

    @Query("DELETE FROM automation_routines WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface RoutineExecutionLogDao {
    @Query("SELECT * FROM routine_execution_logs ORDER BY executedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<RoutineExecutionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: RoutineExecutionLogEntity): Long

    @Query("DELETE FROM routine_execution_logs")
    suspend fun clearAll()
}

@Database(
    entities = [
        CommandHistoryEntity::class,
        LocalNoteEntity::class,
        LocalReminderEntity::class,
        DownloadedModelEntity::class,
        PersonalMemoryEntity::class,
        AutomationRoutineEntity::class,
        RoutineExecutionLogEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class CommanderDatabase : RoomDatabase() {
    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun localNoteDao(): LocalNoteDao
    abstract fun localReminderDao(): LocalReminderDao
    abstract fun downloadedModelDao(): DownloadedModelDao
    abstract fun personalMemoryDao(): PersonalMemoryDao
    abstract fun automationRoutineDao(): AutomationRoutineDao
    abstract fun routineExecutionLogDao(): RoutineExecutionLogDao

    companion object {
        @Volatile
        private var instance: CommanderDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `personal_memories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `memoryKey` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `summaryLabel` TEXT NOT NULL,
                        `encryptedValue` TEXT NOT NULL,
                        `isEncrypted` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `automation_routines` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `voiceTriggers` TEXT NOT NULL,
                        `stepsJson` TEXT NOT NULL,
                        `scheduleHour` INTEGER,
                        `scheduleMinute` INTEGER,
                        `isEnabled` INTEGER NOT NULL,
                        `requiresConfirmation` INTEGER NOT NULL,
                        `isPreset` INTEGER NOT NULL,
                        `lastExecutedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `routine_execution_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `routineId` INTEGER NOT NULL,
                        `routineName` TEXT NOT NULL,
                        `triggerSource` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `executedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

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
                    )
                        .addMigrations(MIGRATION_1_2)
                        .build()
                        .also { instance = it }
                }
            }
        }
    }
}
