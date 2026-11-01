package com.neoplus.ai.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "activity_log")
data class ActivityLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val source: String,
    val message: String,
    val status: String // OK, FAILED, BLOCKED
)

/** Text is stored encrypted with the Android Keystore key. */
@Entity(tableName = "memory_item")
data class MemoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val encryptedText: String,
    val createdAt: Long
)

@Entity(tableName = "app_connection")
data class AppConnection(
    @PrimaryKey val packageName: String,
    val label: String,
    val connected: Boolean,
    val monitoring: Boolean,
    val updatedAt: Long
)

@Entity(tableName = "callback")
data class Callback(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerAt: Long,
    val repeatDaily: Boolean,
    val status: String, // PENDING, RINGING, ANSWERED, DECLINED, MISSED, CANCELLED, BLOCKED, FAILED
    val attempts: Int,
    val maxAttempts: Int,
    val retryMinutes: Int,
    val reason: String,
    val updatedAt: Long
)

@Entity(tableName = "message")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val encryptedText: String,
    val receivedAt: Long,
    val language: String,
    val urgency: String, // NORMAL, HIGH, SENSITIVE
    val status: String,  // NEW, ESCALATED, REPLIED_AUTO, SENT_APPROVED, DISMISSED
    val encryptedDraft: String? = null,
    val repliedAt: Long? = null,
    val dedupeKey: String
)

@Entity(tableName = "agent_run")
data class AgentRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val encryptedCommand: String,
    val createdAt: Long,
    val note: String
)

/** status: AWAITING_APPROVAL, QUEUED, RUNNING, DONE (verified), UNVERIFIED, FAILED, CANCELLED */
@Entity(tableName = "agent_task")
data class AgentTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val tool: String,
    val argsJson: String,
    val dependsOn: String,
    val priority: Int,
    val status: String,
    val attempts: Int,
    val encryptedOutput: String?,
    val verified: Boolean,
    val error: String?,
    val createdAt: Long,
    val finishedAt: Long?
)

@Dao
interface AgentDao {
    @Insert suspend fun insertRun(r: AgentRun): Long
    @Update suspend fun updateRun(r: AgentRun)
    @Insert suspend fun insertTask(t: AgentTask): Long
    @Update suspend fun updateTask(t: AgentTask)
    @Query("SELECT * FROM agent_task WHERE id = :id") suspend fun getTask(id: Long): AgentTask?
    @Query("SELECT * FROM agent_task WHERE status = 'QUEUED' ORDER BY priority, id") suspend fun queued(): List<AgentTask>
    @Query("SELECT * FROM agent_run ORDER BY createdAt DESC LIMIT 30") fun runs(): Flow<List<AgentRun>>
    @Query("SELECT * FROM agent_task ORDER BY id") fun tasks(): Flow<List<AgentTask>>
    @Query("UPDATE agent_task SET status = 'FAILED', error = 'Interrupted when the app closed' WHERE status = 'RUNNING'")
    suspend fun markInterrupted()
    @Query("UPDATE agent_task SET status = 'CANCELLED', error = 'Stopped' WHERE status IN ('QUEUED','RUNNING','AWAITING_APPROVAL')")
    suspend fun cancelOpen()
    @Query("DELETE FROM agent_task") suspend fun clearTasks()
    @Query("DELETE FROM agent_run") suspend fun clearRuns()
}

@Dao
interface MessageDao {
    @Insert suspend fun insert(m: Message): Long
    @Update suspend fun update(m: Message)
    @Query("SELECT * FROM message WHERE id = :id") suspend fun get(id: Long): Message?
    @Query("SELECT * FROM message ORDER BY receivedAt DESC LIMIT 300") fun all(): Flow<List<Message>>
    @Query("SELECT COUNT(*) FROM message WHERE dedupeKey = :k") suspend fun count(k: String): Int
    @Query("SELECT MAX(repliedAt) FROM message WHERE packageName = :p AND sender = :s") suspend fun lastReply(p: String, s: String): Long?
    @Query("DELETE FROM message") suspend fun clear()
}

@Dao
interface CallbackDao {
    @Insert suspend fun insert(c: Callback): Long
    @Update suspend fun update(c: Callback)
    @Query("SELECT * FROM callback WHERE id = :id") suspend fun get(id: Long): Callback?
    @Query("SELECT * FROM callback ORDER BY triggerAt DESC LIMIT 100") fun all(): Flow<List<Callback>>
    @Query("SELECT * FROM callback WHERE status IN ('PENDING','RINGING')") suspend fun open(): List<Callback>
    @Query("DELETE FROM callback") suspend fun clear()
}

@Dao
interface LogDao {
    @Insert suspend fun insert(log: ActivityLog)
    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT 200")
    fun recent(): Flow<List<ActivityLog>>
    @Query("DELETE FROM activity_log") suspend fun clear()
}

@Dao
interface MemoryDao {
    @Insert suspend fun insert(item: MemoryItem): Long
    @Query("SELECT * FROM memory_item ORDER BY createdAt DESC")
    fun all(): Flow<List<MemoryItem>>
    @Query("SELECT COUNT(*) FROM memory_item WHERE id = :id") suspend fun exists(id: Long): Int
    @Query("DELETE FROM memory_item WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM memory_item") suspend fun clear()
}

@Dao
interface ConnectionDao {
    @Upsert suspend fun upsert(c: AppConnection)
    @Query("SELECT * FROM app_connection WHERE packageName = :pkg")
    suspend fun get(pkg: String): AppConnection?
    @Query("SELECT * FROM app_connection")
    fun all(): Flow<List<AppConnection>>
    @Query("DELETE FROM app_connection") suspend fun clear()
}

@Database(
    entities = [ActivityLog::class, MemoryItem::class, AppConnection::class, Callback::class, Message::class, AgentRun::class, AgentTask::class],
    version = 4,
    exportSchema = false
)
abstract class NeoDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
    abstract fun memoryDao(): MemoryDao
    abstract fun connectionDao(): ConnectionDao
    abstract fun callbackDao(): CallbackDao
    abstract fun messageDao(): MessageDao
    abstract fun agentDao(): AgentDao

    companion object {
        @Volatile private var inst: NeoDatabase? = null
        fun get(ctx: Context): NeoDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, NeoDatabase::class.java, "neoplus.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { inst = it }
        }
    }
}

/** Keeps memories and logs intact when the callback table is added. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `callback` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`triggerAt` INTEGER NOT NULL, `repeatDaily` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "`attempts` INTEGER NOT NULL, `maxAttempts` INTEGER NOT NULL, `retryMinutes` INTEGER NOT NULL, " +
                "`reason` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)"
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `message` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`packageName` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `sender` TEXT NOT NULL, " +
                "`encryptedText` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, `language` TEXT NOT NULL, " +
                "`urgency` TEXT NOT NULL, `status` TEXT NOT NULL, `encryptedDraft` TEXT, " +
                "`repliedAt` INTEGER, `dedupeKey` TEXT NOT NULL)"
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `agent_run` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`encryptedCommand` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `note` TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `agent_task` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`runId` INTEGER NOT NULL, `tool` TEXT NOT NULL, `argsJson` TEXT NOT NULL, `dependsOn` TEXT NOT NULL, " +
            "`priority` INTEGER NOT NULL, `status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, " +
            "`encryptedOutput` TEXT, `verified` INTEGER NOT NULL, `error` TEXT, " +
            "`createdAt` INTEGER NOT NULL, `finishedAt` INTEGER)")
    }
}
