package io.github.jqssun.gpssetter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.adapter.LogListAdapter
import io.github.jqssun.gpssetter.databinding.ActivityLogBinding
import io.github.jqssun.gpssetter.room.LogEntry
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.utils.AppLogger
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class LogActivity : AppCompatActivity() {

    private val binding by lazy { ActivityLogBinding.inflate(layoutInflater) }
    private val viewModel by viewModels<MainViewModel>()
    private lateinit var logAdapter: LogListAdapter

    private var allLogsCache: List<LogEntry> = emptyList()
    private var selectedCategory: String? = null // null = ALL, "APP", "LOCATION"
    private var searchQuery: String = ""

    companion object {
        private const val MENU_REFRESH = 1001
        private const val MENU_EXPORT = 1002
        private const val MENU_CLEAR = 1003
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
        AppLogger.requestFlushFromHookedApps(this)
        updateHeaderSummary()
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
        val total = allLogsCache.size
        val appCount = allLogsCache.count { it.category == LogEntry.CATEGORY_APP }
        val locCount = allLogsCache.count { it.category == LogEntry.CATEGORY_LOCATION }
        val spoofedCount = allLogsCache.count { it.category == LogEntry.CATEGORY_LOCATION && it.isSpoofed }
        val statusStr = if (PrefManager.isStarted) {
            String.format(
                Locale.US,
                "伪装开启 (%.5f, %.5f)",
                PrefManager.getLat,
                PrefManager.getLng
            )
        } else {
            "伪装未开启"
        }
        binding.logStatusSummary.text =
            "状态: $statusStr | 总计: $total 条 (自身: $appCount · 定位请求: $locCount · 已伪装: $spoofedCount)"
    }

    private fun applyFilters() {
        val query = searchQuery.lowercase(Locale.getDefault())
        val filtered = allLogsCache.filter { entry ->
            val matchesCat = selectedCategory == null || entry.category == selectedCategory
            val matchesQuery = query.isEmpty() ||
                entry.packageName.lowercase(Locale.getDefault()).contains(query) ||
                entry.action.lowercase(Locale.getDefault()).contains(query) ||
                entry.details.lowercase(Locale.getDefault()).contains(query)
            matchesCat && matchesQuery
        }

        logAdapter.submitList(filtered)
        binding.logEmptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun copySingleLogToClipboard(entry: LogEntry) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val formatted = "[${sdf.format(Date(entry.timestamp))}] [${entry.category}] [${entry.packageName}] ${entry.action}\n${entry.details}"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Runami GPS Log", formatted))
        showToast(getString(R.string.log_copied))
    }

    private fun shareLogs() {
        val list = logAdapter.currentList
        if (list.isEmpty()) {
            showToast(getString(R.string.log_empty))
            return
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("=== Runami GPS 运行与定位请求日志 ===")
        sb.appendLine("导出时间: ${sdf.format(Date())}")
        sb.appendLine("当前伪装状态: ${if (PrefManager.isStarted) "开启 (${PrefManager.getLat}, ${PrefManager.getLng})" else "未开启"}")
        sb.appendLine("----------------------------------------")
        for (entry in list) {
            val tag = if (entry.category == LogEntry.CATEGORY_APP) {
                "APP"
            } else if (entry.isSpoofed) {
                "LOC:SPOOFED"
            } else {
                "LOC:PASSTHROUGH"
            }
            sb.appendLine("[${sdf.format(Date(entry.timestamp))}] [$tag] [${entry.packageName}] ${entry.action}")
            sb.appendLine("  -> ${entry.details}")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Runami GPS Logs")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.log_export)))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_REFRESH, 1, getString(R.string.log_refresh)).apply {
            setIcon(R.drawable.ic_update)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        menu.add(Menu.NONE, MENU_EXPORT, 2, getString(R.string.log_export)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(Menu.NONE, MENU_CLEAR, 3, getString(R.string.log_clear)).apply {
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
                showToast("已请求目标应用进程同步定位日志")
                true
            }
            MENU_EXPORT -> {
                shareLogs()
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
