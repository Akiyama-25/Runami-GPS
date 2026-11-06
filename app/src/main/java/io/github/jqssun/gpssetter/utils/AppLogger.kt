package io.github.jqssun.gpssetter.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
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
            val intent = Intent(ACTION_REQUEST_LOGS)
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

    fun logApp(
        action: String,
        details: String,
        isSpoofed: Boolean = runCatching { PrefManager.isStarted }.getOrDefault(false)
    ) {
        runCatching {
            val entry = LogEntry(
                logUid = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                category = LogEntry.CATEGORY_APP,
                packageName = BuildConfig.APPLICATION_ID,
                action = action,
                details = details,
                isSpoofed = isSpoofed
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
                entries.add(
                    LogEntry(
                        logUid = uids[i],
                        timestamp = timestamps[i],
                        category = categories[i],
                        packageName = packages[i],
                        action = actions[i],
                        details = details[i],
                        isSpoofed = spoofedFlags[i]
                    )
                )
            }

            gsApp.globalScope.launch(Dispatchers.IO) {
                getLogRepository()?.insertLogsSync(entries)
            }
        }
    }

    class AppLogTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val fullMsg = if (t != null) "$message (${t.javaClass.simpleName}: ${t.message})" else message
            logApp(
                action = tag?.takeIf { it.isNotBlank() } ?: "System",
                details = fullMsg
            )
        }
    }
}
