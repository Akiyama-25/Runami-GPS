package io.github.jqssun.gpssetter.xposed

import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.annotation.Keep
import de.robv.android.xposed.XposedBridge
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.room.LogEntry
import io.github.jqssun.gpssetter.utils.AppLogger
import io.github.jqssun.gpssetter.utils.LogContentProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Keep
object HookLogReporter {

    private data class QueuedLog(
        val uid: String,
        val timestamp: Long,
        val category: String,
        val level: String,
        val packageName: String,
        val action: String,
        val details: String,
        val isSpoofed: Boolean
    )

    private const val MAX_BUFFER_SIZE = 400
    private val logQueue = ConcurrentLinkedDeque<QueuedLog>()
    private val recentHistory = ConcurrentLinkedDeque<QueuedLog>()
    private val lastLogTimeByMethod = ConcurrentHashMap<String, Long>()
    private val isFlushReceiverRegistered = AtomicBoolean(false)
    private val retryScheduled = AtomicBoolean(false)

    @Volatile
    private var cachedAppContext: Context? = null

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RunamiGPS-HookLog").apply { isDaemon = true }
    }

    private val providerUri by lazy {
        Uri.parse("content://${BuildConfig.APPLICATION_ID}.logprovider")
    }

    fun onContextAvailable(context: Context?) {
        if (context == null) return
        val appCtx = runCatching { context.applicationContext ?: context }.getOrDefault(context)
        val previous = cachedAppContext
        cachedAppContext = appCtx
        if (previous == null || logQueue.isNotEmpty() || !isFlushReceiverRegistered.get()) {
            executor.execute {
                ensureFlushReceiverRegistered()
                flushLogsToHost(includeRecentHistory = true)
            }
        }
    }

    private fun resolveContext(): Context? {
        cachedAppContext?.let { return it }
        val app = runCatching { AndroidAppHelper.currentApplication() }.getOrNull()
        if (app != null) {
            cachedAppContext = app
            return app
        }
        return null
    }

    fun recordLocationRequest(
        packageName: String,
        action: String,
        details: String,
        isSpoofed: Boolean,
        throttleMs: Long = 1200L,
        level: String = LogEntry.LEVEL_INFO
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

        val pid = runCatching { Process.myPid() }.getOrDefault(-1)
        val tid = runCatching { Process.myTid() }.getOrDefault(-1)
        val threadName = runCatching { Thread.currentThread().name }.getOrDefault("unknown")
        val enrichedDetails = if (details.startsWith("pid=")) {
            details
        } else {
            "pid=$pid | tid=$tid ($threadName) | $details"
        }

        val tag = if (isSpoofed) "LOC:SPOOFED" else "LOC:PASSTHROUGH"
        val normalizedLevel = LogEntry.normalizeLevel(level)
        val shortLvl = LogEntry.shortTag(normalizedLevel)
        runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            XposedBridge.log("${sdf.format(Date(now))} $shortLvl/RunamiGPS [$tag] [$packageName] $action: $enrichedDetails")
        }

        val item = QueuedLog(
            uid = UUID.randomUUID().toString(),
            timestamp = now,
            category = LogEntry.CATEGORY_LOCATION,
            level = normalizedLevel,
            packageName = packageName,
            action = action,
            details = enrichedDetails,
            isSpoofed = isSpoofed
        )

        logQueue.addLast(item)
        while (logQueue.size > MAX_BUFFER_SIZE) {
            logQueue.pollFirst()
        }

        recentHistory.addLast(item)
        while (recentHistory.size > MAX_BUFFER_SIZE) {
            recentHistory.pollFirst()
        }

        executor.execute {
            ensureFlushReceiverRegistered()
            flushLogsToHost(includeRecentHistory = false)
        }
    }

    private fun scheduleDelayedRetry() {
        if (!retryScheduled.compareAndSet(false, true)) return
        val delays = longArrayOf(600L, 1800L, 4000L, 8000L)
        for ((index, delayMs) in delays.withIndex()) {
            executor.schedule({
                try {
                    ensureFlushReceiverRegistered()
                    flushLogsToHost(includeRecentHistory = false)
                } finally {
                    if (index == delays.lastIndex) {
                        retryScheduled.set(false)
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun ensureFlushReceiverRegistered() {
        if (isFlushReceiverRegistered.get()) return
        val appContext = resolveContext() ?: return
        if (!isFlushReceiverRegistered.compareAndSet(false, true)) return

        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == AppLogger.ACTION_REQUEST_LOGS) {
                        executor.execute { flushLogsToHost(includeRecentHistory = true) }
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

    private val xshare = Xshare()

    private fun flushLogsToHost(includeRecentHistory: Boolean) {
        val appContext = resolveContext()
        if (appContext == null) {
            if (logQueue.isNotEmpty()) {
                scheduleDelayedRetry()
            }
            return
        }

        val bootTimeMs = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        val resetTimeMs = runCatching {
            xshare.reload()
            xshare.logSessionResetTimestamp
        }.getOrDefault(0L)
        val minValidTimestamp = maxOf(bootTimeMs, resetTimeMs)
        if (minValidTimestamp > 0L) {
            logQueue.removeIf { it.timestamp < minValidTimestamp }
            recentHistory.removeIf { it.timestamp < minValidTimestamp }
        }

        val sourceList = if (includeRecentHistory) {
            (recentHistory.toList() + logQueue.toList()).distinctBy { it.uid }
        } else {
            logQueue.toList()
        }
        val snapshot = sourceList.takeLast(80)
        if (snapshot.isEmpty()) return

        val uids = ArrayList<String>(snapshot.size)
        val timestamps = LongArray(snapshot.size)
        val categories = ArrayList<String>(snapshot.size)
        val levels = ArrayList<String>(snapshot.size)
        val packages = ArrayList<String>(snapshot.size)
        val actions = ArrayList<String>(snapshot.size)
        val details = ArrayList<String>(snapshot.size)
        val spoofed = BooleanArray(snapshot.size)

        snapshot.forEachIndexed { index, item ->
            uids.add(item.uid)
            timestamps[index] = item.timestamp
            categories.add(item.category)
            levels.add(item.level)
            packages.add(item.packageName)
            actions.add(item.action)
            details.add(item.details)
            spoofed[index] = item.isSpoofed
        }

        val bundle = Bundle().apply {
            putStringArrayList(AppLogger.EXTRA_UIDS, uids)
            putLongArray(AppLogger.EXTRA_TIMESTAMPS, timestamps)
            putStringArrayList(AppLogger.EXTRA_CATEGORIES, categories)
            putStringArrayList(AppLogger.EXTRA_LEVELS, levels)
            putStringArrayList(AppLogger.EXTRA_PACKAGES, packages)
            putStringArrayList(AppLogger.EXTRA_ACTIONS, actions)
            putStringArrayList(AppLogger.EXTRA_DETAILS, details)
            putBooleanArray(AppLogger.EXTRA_SPOOFED, spoofed)
        }

        var delivered = false
        runCatching {
            val res = appContext.contentResolver.call(
                providerUri,
                LogContentProvider.METHOD_APPEND_LOGS,
                null,
                bundle
            )
            if (res?.getBoolean(LogContentProvider.KEY_OK) == true) {
                delivered = true
            }
        }

        if (!delivered) {
            runCatching {
                val explicitBroadcast = Intent(AppLogger.ACTION_LOG_BATCH).apply {
                    setPackage(BuildConfig.APPLICATION_ID)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtras(bundle)
                }
                appContext.sendBroadcast(explicitBroadcast)
                val implicitBroadcast = Intent(AppLogger.ACTION_LOG_BATCH).apply {
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtras(bundle)
                }
                appContext.sendBroadcast(implicitBroadcast)
                delivered = true
            }
        }

        if (delivered) {
            val sentUids = uids.toHashSet()
            logQueue.removeIf { sentUids.contains(it.uid) }
        } else {
            scheduleDelayedRetry()
        }
    }
}
