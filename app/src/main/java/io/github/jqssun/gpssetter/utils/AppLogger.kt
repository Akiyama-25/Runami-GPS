package io.github.jqssun.gpssetter.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.gsApp
import io.github.jqssun.gpssetter.repository.LogRepository
import io.github.jqssun.gpssetter.room.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

object AppLogger {

    const val ACTION_LOG_BATCH = "${BuildConfig.APPLICATION_ID}.ACTION_LOG_BATCH"
    const val ACTION_REQUEST_LOGS = "${BuildConfig.APPLICATION_ID}.ACTION_REQUEST_LOGS"

    const val EXTRA_UIDS = "extra_uids"
    const val EXTRA_TIMESTAMPS = "extra_timestamps"
    const val EXTRA_CATEGORIES = "extra_categories"
    const val EXTRA_PACKAGES = "extra_packages"
    const val EXTRA_ACTIONS = "extra_actions"
    const val EXTRA_DETAILS = "extra_details"
    const val EXTRA_SPOOFED = "extra_spoofed"
    const val EXTRA_LEVELS = "extra_levels"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LoggerEntryPoint {
        fun logRepository(): LogRepository
    }

    private var isReceiverRegistered = false

    private val incomingLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_LOG_BATCH) {
                intent.extras?.let { handleIncomingLogBundle(it) }
            }
        }
    }

    fun init(context: Context) {
        if (!isReceiverRegistered) {
            isReceiverRegistered = true
            runCatching {
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    incomingLogReceiver,
                    IntentFilter(ACTION_LOG_BATCH),
                    ContextCompat.RECEIVER_EXPORTED
                )
            }
        }
    }

    fun requestFlushFromHookedApps(context: Context) {
        runCatching {
            val intent = Intent(ACTION_REQUEST_LOGS).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            }
            context.sendBroadcast(intent)
        }
    }

    private fun getLogRepository(): LogRepository? {
        return runCatching {
            EntryPointAccessors.fromApplication(
                gsApp.applicationContext,
                LoggerEntryPoint::class.java
            ).logRepository()
        }.getOrNull()
    }

    fun shouldShowEntry(entry: LogEntry, selectedMinLevel: String = runCatching { PrefManager.logLevel }.getOrDefault(LogEntry.LEVEL_ERROR)): Boolean {
        // Location request records are NEVER affected by log level
        if (entry.category == LogEntry.CATEGORY_LOCATION) {
            return true
        }
        return LogEntry.levelPriority(entry.level) >= LogEntry.levelPriority(selectedMinLevel)
    }

    private fun resolveCallerInfo(): String {
        return runCatching {
            val trace = Thread.currentThread().stackTrace
            val frame = trace.firstOrNull { element ->
                val cls = element.className
                !cls.startsWith("java.lang.Thread") &&
                    !cls.startsWith("dalvik.system.VMStack") &&
                    !cls.startsWith("io.github.jqssun.gpssetter.utils.AppLogger") &&
                    !cls.startsWith("timber.log.Timber")
            }
            if (frame != null) {
                val shortClass = frame.className.substringAfterLast('.')
                "$shortClass.${frame.methodName}(${frame.fileName ?: "Unknown"}:${frame.lineNumber})"
            } else {
                "UnknownCaller"
            }
        }.getOrDefault("UnknownCaller")
    }

    fun logApp(
        action: String,
        details: String,
        isSpoofed: Boolean = runCatching { PrefManager.isStarted }.getOrDefault(false),
        level: String = LogEntry.LEVEL_INFO,
        throwable: Throwable? = null
    ) {
        runCatching {
            val normalizedLevel = LogEntry.normalizeLevel(level)
            val pid = Process.myPid()
            val tid = Process.myTid()
            val threadName = Thread.currentThread().name
            val caller = resolveCallerInfo()
            val detailedBody = buildString {
                append("pid=").append(pid)
                append(" | tid=").append(tid).append(" (").append(threadName).append(")")
                append(" | caller=").append(caller)
                append(" | ").append(details)
                if (throwable != null) {
                    append("\nstacktrace=").append(Log.getStackTraceString(throwable).trim())
                }
            }

            val entry = LogEntry(
                logUid = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                category = LogEntry.CATEGORY_APP,
                packageName = BuildConfig.APPLICATION_ID,
                action = action,
                details = detailedBody,
                isSpoofed = isSpoofed,
                level = normalizedLevel
            )
            gsApp.globalScope.launch(Dispatchers.IO) {
                getLogRepository()?.insertLog(entry)
            }
        }
    }

    fun handleIncomingLogBundle(bundle: Bundle) {
        runCatching {
            val uids = bundle.getStringArrayList(EXTRA_UIDS) ?: return
            val timestamps = bundle.getLongArray(EXTRA_TIMESTAMPS) ?: return
            val categories = bundle.getStringArrayList(EXTRA_CATEGORIES) ?: return
            val packages = bundle.getStringArrayList(EXTRA_PACKAGES) ?: return
            val actions = bundle.getStringArrayList(EXTRA_ACTIONS) ?: return
            val details = bundle.getStringArrayList(EXTRA_DETAILS) ?: return
            val spoofedFlags = bundle.getBooleanArray(EXTRA_SPOOFED) ?: return
            val levels = bundle.getStringArrayList(EXTRA_LEVELS)

            val count = minOf(
                uids.size,
                timestamps.size,
                categories.size,
                packages.size,
                actions.size,
                details.size,
                spoofedFlags.size
            )
            if (count <= 0) return

            val entries = ArrayList<LogEntry>(count)
            for (i in 0 until count) {
                val cat = categories[i]
                val lvl = levels?.getOrNull(i)?.let { LogEntry.normalizeLevel(it) } ?: LogEntry.LEVEL_INFO
                // Location request records are never filtered by log level
                if (cat == LogEntry.CATEGORY_LOCATION || shouldShowEntry(
                        LogEntry(
                            logUid = uids[i],
                            timestamp = timestamps[i],
                            category = cat,
                            packageName = packages[i],
                            action = actions[i],
                            details = details[i],
                            isSpoofed = spoofedFlags[i],
                            level = lvl
                        )
                    )
                ) {
                    entries.add(
                        LogEntry(
                            logUid = uids[i],
                            timestamp = timestamps[i],
                            category = cat,
                            packageName = packages[i],
                            action = actions[i],
                            details = details[i],
                            isSpoofed = spoofedFlags[i],
                            level = lvl
                        )
                    )
                }
            }

            if (entries.isNotEmpty()) {
                gsApp.globalScope.launch(Dispatchers.IO) {
                    getLogRepository()?.insertLogsSync(entries)
                }
            }
        }
    }

    class AppLogTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val mappedLevel = when (priority) {
                Log.VERBOSE, Log.DEBUG -> LogEntry.LEVEL_DEBUG
                Log.INFO -> LogEntry.LEVEL_INFO
                Log.WARN -> LogEntry.LEVEL_WARN
                Log.ERROR, Log.ASSERT -> LogEntry.LEVEL_ERROR
                else -> LogEntry.LEVEL_INFO
            }
            logApp(
                action = tag?.takeIf { it.isNotBlank() } ?: "System",
                details = message,
                level = mappedLevel,
                throwable = t
            )
        }
    }
}
