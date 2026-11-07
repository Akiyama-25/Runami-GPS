package io.github.jqssun.gpssetter.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
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
import io.github.jqssun.gpssetter.xposed.XposedSelfCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.UUID
import kotlin.math.abs

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
    private val dbSessionLock = Any()

    @Volatile
    private var isSessionValidatedInProcess = false

    @Volatile
    private var minValidTimestampMs: Long = 0L

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
        gsApp.globalScope.launch(Dispatchers.IO) {
            ensureSessionFreshnessLocked(context.applicationContext)
        }
    }

    /**
     * Checks whether the module was updated, reinstalled, reloaded (Xposed hook state or Zygote changed),
     * or the system rebooted since the previous log session. If so, synchronously clears all historical logs
     * before any new logs in the current session are written.
     */
    private fun ensureSessionFreshnessLocked(context: Context = gsApp.applicationContext) {
        synchronized(dbSessionLock) {
            if (isSessionValidatedInProcess) return
            isSessionValidatedInProcess = true

            runCatching {
                val now = System.currentTimeMillis()
                val bootTimeMs = now - SystemClock.elapsedRealtime()
                val bootId = runCatching {
                    File("/proc/sys/kernel/random/boot_id").readText().trim()
                }.getOrDefault("")
                val bootCount = runCatching {
                    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                }.getOrDefault(-1)
                val zygotePpid = runCatching { Os.getppid() }.getOrDefault(-1)

                val pkgInfo = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }.getOrNull()
                val lastUpdateTime = pkgInfo?.lastUpdateTime ?: 0L
                val apkLastModified = runCatching {
                    File(context.applicationInfo.sourceDir).lastModified()
                }.getOrDefault(0L)
                val selfHookState = "${XposedSelfCheck.isXposedModuleEnabled()}:${XposedSelfCheck.getXposedVersion()}"

                val currentSignature = buildString {
                    append("ver=").append(BuildConfig.VERSION_NAME)
                    append("|code=").append(BuildConfig.VERSION_CODE)
                    append("|upd=").append(lastUpdateTime)
                    append("|apk=").append(apkLastModified)
                    append("|bootId=").append(bootId)
                    append("|bootCnt=").append(bootCount)
                    append("|ppid=").append(zygotePpid)
                    append("|hook=").append(selfHookState)
                }

                val savedSignature = PrefManager.logSessionSignature
                val savedBootTimeMs = PrefManager.logSessionBootTimeMs
                val bootTimeShifted = savedBootTimeMs > 0L && abs(bootTimeMs - savedBootTimeMs) > 5000L

                val repo = getLogRepository()
                if (savedSignature != currentSignature || bootTimeShifted) {
                    val reasons = mutableListOf<String>()
                    if (savedSignature.isEmpty()) {
                        reasons.add("INITIAL_SESSION")
                    } else {
                        if (!savedSignature.contains("ver=${BuildConfig.VERSION_NAME}|code=${BuildConfig.VERSION_CODE}|upd=$lastUpdateTime|apk=$apkLastModified")) {
                            reasons.add("MODULE_UPDATED_OR_REINSTALLED")
                        }
                        if (bootTimeShifted || !savedSignature.contains("bootId=$bootId|bootCnt=$bootCount")) {
                            reasons.add("SYSTEM_REBOOT")
                        }
                        if (!savedSignature.contains("ppid=$zygotePpid")) {
                            reasons.add("ZYGOTE_OR_FRAMEWORK_RELOAD")
                        }
                        if (!savedSignature.contains("hook=$selfHookState")) {
                            reasons.add("XPOSED_MODULE_RELOADED")
                        }
                        if (reasons.isEmpty()) {
                            reasons.add("MODULE_RELOAD")
                        }
                    }

                    val resetTimestamp = now - 200L
                    PrefManager.logSessionSignature = currentSignature
                    PrefManager.logSessionBootTimeMs = bootTimeMs
                    PrefManager.logSessionResetTimestamp = resetTimestamp
                    minValidTimestampMs = maxOf(bootTimeMs, lastUpdateTime, apkLastModified, resetTimestamp)

                    repo?.clearAllLogsSync()

                    val reloadEntry = LogEntry(
                        logUid = UUID.randomUUID().toString(),
                        timestamp = now,
                        category = LogEntry.CATEGORY_APP,
                        packageName = BuildConfig.APPLICATION_ID,
                        action = "AppLogger.onSessionReload (模块重载/清除旧日志)",
                        details = "pid=${Process.myPid()} | tid=${Process.myTid()} (${Thread.currentThread().name}) | reason=${reasons.joinToString(",")} | version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) | bootCount=$bootCount | zygotePpid=$zygotePpid | clearedPreviousLogs=true",
                        isSpoofed = runCatching { PrefManager.isStarted }.getOrDefault(false),
                        level = LogEntry.LEVEL_INFO
                    )
                    repo?.insertLogSync(reloadEntry)
                } else {
                    minValidTimestampMs = maxOf(
                        bootTimeMs,
                        lastUpdateTime,
                        apkLastModified,
                        PrefManager.logSessionResetTimestamp
                    )
                    if (minValidTimestampMs > 0L) {
                        repo?.deleteLogsBeforeSync(minValidTimestampMs)
                    }
                }
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

    fun shouldShowEntry(
        entry: LogEntry,
        selectedMinLevel: String = runCatching { PrefManager.logLevel }.getOrDefault(LogEntry.LEVEL_ERROR)
    ): Boolean {
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
                synchronized(dbSessionLock) {
                    if (!isSessionValidatedInProcess) {
                        ensureSessionFreshnessLocked(gsApp.applicationContext)
                    }
                    getLogRepository()?.insertLogSync(entry)
                }
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

            gsApp.globalScope.launch(Dispatchers.IO) {
                synchronized(dbSessionLock) {
                    if (!isSessionValidatedInProcess) {
                        ensureSessionFreshnessLocked(gsApp.applicationContext)
                    }
                    val cutoff = minValidTimestampMs
                    val entries = ArrayList<LogEntry>(count)
                    for (i in 0 until count) {
                        val ts = timestamps[i]
                        // Ignore stale logs from before module reload, update, or system reboot
                        if (cutoff > 0L && ts < cutoff) {
                            continue
                        }
                        val cat = categories[i]
                        val lvl = levels?.getOrNull(i)?.let { LogEntry.normalizeLevel(it) } ?: LogEntry.LEVEL_INFO
                        entries.add(
                            LogEntry(
                                logUid = uids[i],
                                timestamp = ts,
                                category = cat,
                                packageName = packages[i],
                                action = actions[i],
                                details = details[i],
                                isSpoofed = spoofedFlags[i],
                                level = lvl
                            )
                        )
                    }

                    if (entries.isNotEmpty()) {
                        getLogRepository()?.insertLogsSync(entries)
                    }
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
