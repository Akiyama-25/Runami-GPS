package io.github.jqssun.gpssetter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.adapter.LogListAdapter
import io.github.jqssun.gpssetter.databinding.ActivityLogBinding
import io.github.jqssun.gpssetter.room.LogEntry
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.utils.AppLogger
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.ext.showToast
import io.github.jqssun.gpssetter.xposed.XposedSelfCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class LogActivity : AppCompatActivity() {

    private val binding by lazy { ActivityLogBinding.inflate(layoutInflater) }
    private val viewModel by viewModels<MainViewModel>()
    private lateinit var logAdapter: LogListAdapter

    private var allLogsCache: List<LogEntry> = emptyList()
    private var selectedCategory: String? = null
    private var selectedLogLevel: String = PrefManager.logLevel
    private var searchQuery: String = ""

    // Use application/octet-stream so Android SAF DocumentsUI preserves the exact .log extension without appending .txt
    private val createLogFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            saveLogsToUri(uri)
        }
    }

    companion object {
        private const val MENU_REFRESH = 1001
        private const val MENU_LEVEL = 1002
        private const val MENU_EXPORT = 1003
        private const val MENU_CLEAR = 1004

        private val LEVEL_VALUES = arrayOf(
            LogEntry.LEVEL_ERROR,
            LogEntry.LEVEL_WARN,
            LogEntry.LEVEL_INFO,
            LogEntry.LEVEL_DEBUG
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContentView(binding.root)
        theme.applyStyle(
            com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight_NoActionBar,
            true
        )

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        selectedLogLevel = LogEntry.normalizeLevel(PrefManager.logLevel)
        updateLogLevelChipText()

        logAdapter = LogListAdapter { entry ->
            copySingleLogToClipboard(entry)
        }

        binding.logRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@LogActivity)
            adapter = logAdapter
        }

        binding.logFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedCategory = when (checkedIds.firstOrNull()) {
                R.id.chip_app -> LogEntry.CATEGORY_APP
                R.id.chip_location -> LogEntry.CATEGORY_LOCATION
                else -> null
            }
            applyFilters()
        }

        binding.chipLogLevel.setOnClickListener {
            showLogLevelPickerDialog()
        }

        binding.logSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                applyFilters()
            }
        })

        observeLogs()
        AppLogger.requestFlushFromHookedApps(this)
    }

    override fun onResume() {
        super.onResume()
        selectedLogLevel = LogEntry.normalizeLevel(PrefManager.logLevel)
        updateLogLevelChipText()
        AppLogger.requestFlushFromHookedApps(this)
        updateHeaderSummary()
        applyFilters()
    }

    private fun updateLogLevelChipText() {
        binding.chipLogLevel.text = getString(R.string.log_level_chip_format, selectedLogLevel)
    }

    private fun showLogLevelPickerDialog() {
        val labels = resources.getStringArray(R.array.log_level_texts)
        val currentIdx = LEVEL_VALUES.indexOfFirst { it.equals(selectedLogLevel, ignoreCase = true) }
            .coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.log_level_dialog_title)
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                val chosen = LEVEL_VALUES.getOrElse(which) { LogEntry.LEVEL_ERROR }
                selectedLogLevel = chosen
                PrefManager.logLevel = chosen
                updateLogLevelChipText()
                updateHeaderSummary()
                applyFilters()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allLogs.collect { logs ->
                    allLogsCache = logs
                    updateHeaderSummary()
                    applyFilters()
                }
            }
        }
    }

    private fun updateHeaderSummary() {
        val levelFiltered = allLogsCache.filter { AppLogger.shouldShowEntry(it, selectedLogLevel) }
        val total = levelFiltered.size
        val appCount = levelFiltered.count { it.category == LogEntry.CATEGORY_APP }
        val locCount = levelFiltered.count { it.category == LogEntry.CATEGORY_LOCATION }
        val spoofedCount = levelFiltered.count { it.category == LogEntry.CATEGORY_LOCATION && it.isSpoofed }
        val statusStr = if (PrefManager.isStarted) {
            getString(R.string.log_status_spoofing_on, PrefManager.getLat, PrefManager.getLng)
        } else {
            getString(R.string.log_status_spoofing_off)
        }
        binding.logStatusSummary.text = getString(
            R.string.log_status_summary_format,
            statusStr,
            total,
            appCount,
            locCount,
            spoofedCount
        )
    }

    private fun applyFilters() {
        val query = searchQuery.lowercase(Locale.getDefault())
        val filtered = allLogsCache.filter { entry ->
            // Location request records are NEVER affected by log level
            val matchesLevel = AppLogger.shouldShowEntry(entry, selectedLogLevel)
            val matchesCat = selectedCategory == null || entry.category == selectedCategory
            val matchesQuery = query.isEmpty() ||
                entry.packageName.lowercase(Locale.getDefault()).contains(query) ||
                entry.action.lowercase(Locale.getDefault()).contains(query) ||
                entry.details.lowercase(Locale.getDefault()).contains(query) ||
                entry.level.lowercase(Locale.getDefault()).contains(query)
            matchesLevel && matchesCat && matchesQuery
        }

        logAdapter.submitList(filtered)
        binding.logEmptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun formatLogLine(entry: LogEntry, sdf: SimpleDateFormat): String {
        val normalizedLevel = LogEntry.normalizeLevel(entry.level)
        val shortLvl = LogEntry.shortTag(normalizedLevel)
        val tag = when {
            entry.category == LogEntry.CATEGORY_APP -> "APP:$normalizedLevel"
            entry.isSpoofed -> "LOC:SPOOFED"
            else -> "LOC:PASSTHROUGH"
        }
        val cleanAction = entry.action.replace('\n', ' ').trim()
        val lines = entry.details.trim().lines()
        val firstLine = lines.firstOrNull().orEmpty()
        val prefix = "${sdf.format(Date(entry.timestamp))} $shortLvl/RunamiGPS [$tag] [${entry.packageName}] $cleanAction: $firstLine"
        return if (lines.size <= 1) {
            prefix
        } else {
            buildString {
                append(prefix)
                for (i in 1 until lines.size) {
                    append('\n').append("    ").append(lines[i])
                }
            }
        }
    }

    private fun copySingleLogToClipboard(entry: LogEntry) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val formatted = formatLogLine(entry, sdf)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Runami GPS Log", formatted))
        showToast(getString(R.string.log_copied))
    }

    private fun buildLogFileContent(list: List<LogEntry>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()

        val spoofingDesc = if (PrefManager.isStarted) {
            String.format(
                Locale.US,
                "ACTIVE (%.6f, %.6f) | Accuracy=%sm | Random=%s | GnssEnhance=%s",
                PrefManager.getLat,
                PrefManager.getLng,
                PrefManager.accuracy ?: "10",
                PrefManager.isRandomPosition,
                PrefManager.isSystemHooked
            )
        } else {
            "INACTIVE"
        }

        sb.appendLine("SDK: ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})")
        sb.appendLine("PRODUCT_NAME: ${Build.PRODUCT}")
        sb.appendLine("DEVICE_NAME: ${Build.DEVICE}")
        sb.appendLine("BOARD_NAME: ${Build.BOARD}")
        sb.appendLine("SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        sb.appendLine("MANUFACTURER: ${Build.MANUFACTURER}")
        sb.appendLine("BRAND: ${Build.BRAND}")
        sb.appendLine("MODEL: ${Build.MODEL}")
        sb.appendLine()
        sb.appendLine("APP_NAME: ${getString(R.string.app_name)}")
        sb.appendLine("PACKAGE: ${BuildConfig.APPLICATION_ID}")
        sb.appendLine("VERSION_NAME: ${BuildConfig.VERSION_NAME}")
        sb.appendLine("VERSION_CODE: ${BuildConfig.VERSION_CODE}")
        sb.appendLine("XPOSED_STATUS: ${XposedSelfCheck.getStatusSummary(this)}")
        sb.appendLine("SPOOFING_STATUS: $spoofingDesc")
        sb.appendLine("APP_LOG_LEVEL: $selectedLogLevel (Location Requests Exempt / Unfiltered)")
        sb.appendLine("EXPORT_TIME: ${sdf.format(Date())}")
        sb.appendLine()

        // Sort chronologically (oldest first, newest last) for standard .log reading
        val chronologicalList = list.sortedWith(
            compareBy<LogEntry> { it.timestamp }.thenBy { it.id }
        )
        for (entry in chronologicalList) {
            sb.appendLine(formatLogLine(entry, sdf))
        }
        return sb.toString()
    }

    private fun defaultLogFileName(): String {
        val fileTimeFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "Runami-GPS-${fileTimeFormat.format(Date())}.log"
    }

    private fun showExportLogOptions() {
        val list = logAdapter.currentList
        if (list.isEmpty()) {
            showToast(getString(R.string.log_empty))
            return
        }
        val options = arrayOf(
            getString(R.string.log_export_save_file),
            getString(R.string.log_export_share_file)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.log_export)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> createLogFileLauncher.launch(defaultLogFileName())
                    1 -> shareLogFileAttachment()
                }
            }
            .show()
    }

    private fun saveLogsToUri(uri: Uri) {
        val list = logAdapter.currentList
        if (list.isEmpty()) {
            showToast(getString(R.string.log_empty))
            return
        }
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val content = buildLogFileContent(list)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: error("Failed to open output stream")
                }.isSuccess
            }
            if (success) {
                AppLogger.logApp(
                    action = "LogActivity.exportLogFile",
                    details = "Exported ${list.size} log entries to .log file (level=$selectedLogLevel)",
                    level = LogEntry.LEVEL_DEBUG
                )
                showToast(getString(R.string.log_export_success))
            } else {
                showToast(getString(R.string.log_export_failed))
            }
        }
    }

    private fun shareLogFileAttachment() {
        val list = logAdapter.currentList
        if (list.isEmpty()) {
            showToast(getString(R.string.log_empty))
            return
        }
        lifecycleScope.launch {
            val fileUri = withContext(Dispatchers.IO) {
                runCatching {
                    val logDir = File(externalCacheDir, "logs").apply { mkdirs() }
                    val logFile = File(logDir, defaultLogFileName())
                    logFile.writeText(buildLogFileContent(list), Charsets.UTF_8)
                    FileProvider.getUriForFile(
                        this@LogActivity,
                        "${BuildConfig.APPLICATION_ID}.provider",
                        logFile
                    )
                }.getOrNull()
            }
            if (fileUri != null) {
                AppLogger.logApp(
                    action = "LogActivity.shareLogFile",
                    details = "Shared .log file with ${list.size} entries (level=$selectedLogLevel)",
                    level = LogEntry.LEVEL_DEBUG
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    clipData = ClipData.newRawUri("Runami GPS .log", fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.log_export)))
            } else {
                showToast(getString(R.string.log_export_failed))
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_REFRESH, 1, getString(R.string.log_refresh)).apply {
            setIcon(R.drawable.ic_update)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        menu.add(Menu.NONE, MENU_LEVEL, 2, getString(R.string.log_level_title)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(Menu.NONE, MENU_EXPORT, 3, getString(R.string.log_export)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(Menu.NONE, MENU_CLEAR, 4, getString(R.string.log_clear)).apply {
            setIcon(R.drawable.ic_delete)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            MENU_REFRESH -> {
                AppLogger.requestFlushFromHookedApps(this)
                showToast(getString(R.string.log_sync_requested))
                true
            }
            MENU_LEVEL -> {
                showLogLevelPickerDialog()
                true
            }
            MENU_EXPORT -> {
                showExportLogOptions()
                true
            }
            MENU_CLEAR -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.log_clear)
                    .setMessage(R.string.log_clear_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewModel.clearAllLogs()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
