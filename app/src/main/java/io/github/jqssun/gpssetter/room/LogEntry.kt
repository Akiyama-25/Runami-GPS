package io.github.jqssun.gpssetter.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val isSpoofed: Boolean = false
) {
    companion object {
        const val CATEGORY_APP = "APP"
        const val CATEGORY_LOCATION = "LOCATION"
    }
}
