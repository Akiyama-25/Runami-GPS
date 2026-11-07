package io.github.jqssun.gpssetter.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC, id DESC LIMIT 1000")
    fun getAllLogs(): Flow<List<LogEntry>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLog(logEntry: LogEntry): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertLogSync(logEntry: LogEntry): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertLogsSync(logEntries: List<LogEntry>)

    @Query("DELETE FROM log_entries WHERE id NOT IN (SELECT id FROM log_entries ORDER BY timestamp DESC, id DESC LIMIT :maxCount)")
    suspend fun trimOldLogs(maxCount: Int = 1500)

    @Query("DELETE FROM log_entries WHERE id NOT IN (SELECT id FROM log_entries ORDER BY timestamp DESC, id DESC LIMIT :maxCount)")
    fun trimOldLogsSync(maxCount: Int = 1500)

    @Query("DELETE FROM log_entries WHERE timestamp < :minTimestamp")
    fun deleteLogsBeforeSync(minTimestamp: Long)

    @Query("DELETE FROM log_entries")
    suspend fun clearAllLogs()

    @Query("DELETE FROM log_entries")
    fun clearAllLogsSync()
}
