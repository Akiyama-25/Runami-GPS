package io.github.jqssun.gpssetter.xposed

import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import de.robv.android.xposed.XposedBridge
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.room.LogEntry
import io.github.jqssun.gpssetter.utils.AppLogger
import io.github.jqssun.gpssetter.utils.LogContentProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object HookLogReporter {

    private data class QueuedLog(
        val uid: String,
        val timestamp: Long,
        val category: String,
        val packageName: String,
        val action: String,
        val details: String,
        val isSpoofed: Boolean
    )

    private const val MAX_BUFFER_SIZE = 250
    private val logQueue = ConcurrentLinkedDeque<QueuedLog>()
    private val lastLogTimeByMethod = ConcurrentHashMap<String, Long>()
    private val isFlushReceiverRegistered = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RunamiGPS-HookLog").apply { isDaemon = true }
    }

    private val providerUri by lazy {
        Uri.parse("content://${BuildConfig.APPLICATION_ID}.logprovider")
    }

    fun recordLocationRequest(
        packageName: String,
        action: String,
        details: String,
        isSpoofed: Boolean,
        throttleMs: Long = 1500L
    ) {
        val now = System.currentTimeMillis()
        if (throttleMs > 0L) {
            val key = "$packageName:$action:$isSpoofed"
            val prev = lastLogTimeByMethod[key] ?: 0L
            if (now - prev < throttleMs) {
                return
            }
            lastLogTimeByMethod[key] = now
        }

        val statusLabel = if (isSpoofed) "SPOOFED" else "PASSTHROUGH"
        runCatching {
            XposedBridge.log("[RunamiGPS][$statusLabel][$packageName] $action | $details")
        }

        val item = QueuedLog(
            uid = UUID.randomUUID().toString(),
            timestamp = now,
            category = LogEntry.CATEGORY_LOCATION,
            packageName = packageName,
            action = action,
            details = details,
            isSpoofed = isSpoofed
        )

        logQueue.addLast(item)
        while (logQueue.size > MAX_BUFFER_SIZE) {
            logQueue.pollFirst()
        }

        executor.execute {
            ensureFlushReceiverRegistered()
            flushLogsToHost()
        }
    }

    private fun ensureFlushReceiverRegistered() {
        if (isFlushReceiverRegistered.get()) return
        val appContext = runCatching { AndroidAppHelper.currentApplication() }.getOrNull() ?: return
        if (!isFlushReceiverRegistered.compareAndSet(false, true)) return

        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == AppLogger.ACTION_REQUEST_LOGS) {
                        executor.execute { flushLogsToHost() }
                    }
                }
            }
            val filter = IntentFilter(AppLogger.ACTION_REQUEST_LOGS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        }.onFailure {
            isFlushReceiverRegistered.set(false)
        }
    }

    private fun flushLogsToHost() {
        val appContext = runCatching { AndroidAppHelper.currentApplication() }.getOrNull() ?: return
        val snapshot = logQueue.toList().takeLast(60)
        if (snapshot.isEmpty()) return

        val uids = ArrayList<String>(snapshot.size)
        val timestamps = LongArray(snapshot.size)
        val categories = ArrayList<String>(snapshot.size)
        val packages = ArrayList<String>(snapshot.size)
        val actions = ArrayList<String>(snapshot.size)
        val details = ArrayList<String>(snapshot.size)
        val spoofed = BooleanArray(snapshot.size)

        snapshot.forEachIndexed { index, item ->
            uids.add(item.uid)
            timestamps[index] = item.timestamp
            categories.add(item.category)
            packages.add(item.packageName)
            actions.add(item.action)
            details.add(item.details)
            spoofed[index] = item.isSpoofed
        }

        val bundle = Bundle().apply {
            putStringArrayList(AppLogger.EXTRA_UIDS, uids)
            putLongArray(AppLogger.EXTRA_TIMESTAMPS, timestamps)
            putStringArrayList(AppLogger.EXTRA_CATEGORIES, categories)
            putStringArrayList(AppLogger.EXTRA_PACKAGES, packages)
            putStringArrayList(AppLogger.EXTRA_ACTIONS, actions)
            putStringArrayList(AppLogger.EXTRA_DETAILS, details)
            putBooleanArray(AppLogger.EXTRA_SPOOFED, spoofed)
        }

        var deliveredViaProvider = false
        runCatching {
            val res = appContext.contentResolver.call(
                providerUri,
                LogContentProvider.METHOD_APPEND_LOGS,
                null,
                bundle
            )
            if (res?.getBoolean(LogContentProvider.KEY_OK) == true) {
                deliveredViaProvider = true
            }
        }

        if (deliveredViaProvider) {
            val sentUids = uids.toHashSet()
            logQueue.removeIf { sentUids.contains(it.uid) }
        } else {
            runCatching {
                val broadcastIntent = Intent(AppLogger.ACTION_LOG_BATCH).apply {
                    putExtras(bundle)
                }
                appContext.sendBroadcast(broadcastIntent)
            }
        }
    }
}
