package io.github.jqssun.gpssetter.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

@Entity(
    tableName = "log_entries",
    indices = [Index(value = ["log_uid"], unique = true)]
)
data class LogEntry(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "log_uid")
    val logUid: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "category")
    val category: String, // "APP" or "LOCATION"

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "action")
    val action: String,

    @ColumnInfo(name = "details")
    val details: String,

    @ColumnInfo(name = "is_spoofed")
    val isSpoofed: Boolean = false,

    @ColumnInfo(name = "level", defaultValue = "INFO")
    val level: String = LEVEL_INFO
) {
    companion object {
        const val CATEGORY_APP = "APP"
        const val CATEGORY_LOCATION = "LOCATION"

        const val LEVEL_DEBUG = "DEBUG"
        const val LEVEL_INFO = "INFO"
        const val LEVEL_WARN = "WARN"
        const val LEVEL_ERROR = "ERROR"

        fun normalizeLevel(raw: String?): String {
            return when (raw?.trim()?.uppercase(Locale.US)) {
                "DEBUG", "D", "VERBOSE", "V" -> LEVEL_DEBUG
                "INFO", "I" -> LEVEL_INFO
                "WARN", "WARNING", "W" -> LEVEL_WARN
                "ERROR", "ERR", "E", "ASSERT", "WTF" -> LEVEL_ERROR
                else -> LEVEL_INFO
            }
        }

        fun levelPriority(level: String?): Int {
            return when (normalizeLevel(level)) {
                LEVEL_DEBUG -> 10
                LEVEL_INFO -> 20
                LEVEL_WARN -> 30
                LEVEL_ERROR -> 40
                else -> 20
            }
        }

        fun shortTag(level: String?): String {
            return when (normalizeLevel(level)) {
                LEVEL_DEBUG -> "D"
                LEVEL_INFO -> "I"
                LEVEL_WARN -> "W"
                LEVEL_ERROR -> "E"
                else -> "I"
            }
        }
    }
}
