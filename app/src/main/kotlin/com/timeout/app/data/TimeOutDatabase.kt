package com.timeout.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rules")
data class RuleRecord(@PrimaryKey val id: String, val payload: String, val enabled: Boolean, val createdAt: Long)

@Entity(tableName = "history")
data class HistoryRecord(@PrimaryKey val id: String, val title: String, val detail: String, val at: Long)

@Entity(tableName = "changes")
data class PendingChange(
    @PrimaryKey val ruleId: String,
    val expectedRevision: Long,
    val replacement: String?,
    val readyAt: Long,
    val readyElapsed: Long,
    val bootCount: Int,
    val expiresAt: Long,
)

@Dao
interface TimeOutDao {
    @Query("SELECT * FROM rules ORDER BY createdAt DESC") fun observeRules(): Flow<List<RuleRecord>>
    @Query("SELECT * FROM history ORDER BY at DESC LIMIT 100") fun observeHistory(): Flow<List<HistoryRecord>>
    @Query("SELECT * FROM changes") fun observeChanges(): Flow<List<PendingChange>>
    @Query("SELECT * FROM rules WHERE id = :id") suspend fun rule(id: String): RuleRecord?
    @Query("SELECT * FROM rules") suspend fun rules(): List<RuleRecord>
    @Query("SELECT * FROM changes WHERE ruleId = :id") suspend fun change(id: String): PendingChange?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRule(record: RuleRecord)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun event(event: HistoryRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putChange(change: PendingChange)
    @Query("DELETE FROM changes WHERE ruleId = :id") suspend fun clearChange(id: String)
    @Query("DELETE FROM changes") suspend fun clearChanges()
    @Query("UPDATE rules SET enabled = 0") suspend fun disableAll()
    @Query("DELETE FROM rules") suspend fun clearRules()
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("DELETE FROM history WHERE at < :before") suspend fun pruneHistory(before: Long)
}

@Database(entities = [RuleRecord::class, HistoryRecord::class, PendingChange::class], version = 1, exportSchema = true)
abstract class TimeOutDatabase : RoomDatabase() {
    abstract fun dao(): TimeOutDao
}
