package io.github.jqssun.gpssetter.repository

import androidx.annotation.WorkerThread
import io.github.jqssun.gpssetter.room.LogDao
import io.github.jqssun.gpssetter.room.LogEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogRepository @Inject constructor(
    private val logDao: LogDao
) {
    val allLogs: Flow<List<LogEntry>>
        get() = logDao.getAllLogs()

    @WorkerThread
    suspend fun insertLog(logEntry: LogEntry): Long {
        val id = logDao.insertLog(logEntry)
        logDao.trimOldLogs(1500)
        return id
    }

    fun insertLogSync(logEntry: LogEntry): Long {
        val id = logDao.insertLogSync(logEntry)
        logDao.trimOldLogsSync(1500)
        return id
    }

    fun insertLogsSync(logEntries: List<LogEntry>) {
        if (logEntries.isEmpty()) return
        logDao.insertLogsSync(logEntries)
        logDao.trimOldLogsSync(1500)
    }

    @WorkerThread
    suspend fun clearAllLogs() {
        logDao.clearAllLogs()
    }
}
