package io.github.jqssun.gpssetter.ui.viewmodel

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.repository.FavoriteRepository
import io.github.jqssun.gpssetter.repository.LogRepository
import io.github.jqssun.gpssetter.room.Favorite
import io.github.jqssun.gpssetter.room.LogEntry
import io.github.jqssun.gpssetter.update.UpdateChecker
import io.github.jqssun.gpssetter.utils.AppLogger
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.ext.onIO
import io.github.jqssun.gpssetter.utils.ext.onMain
import io.github.jqssun.gpssetter.utils.ext.showToast
import io.github.jqssun.gpssetter.xposed.XposedSelfCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class MainViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val logRepository: LogRepository,
    private val prefManger: PrefManager,
    private val checkUpdates: UpdateChecker,
    private val downloadManager: DownloadManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    companion object {
        @Volatile
        private var hasAutoCheckedOnStartup: Boolean = false
    }

    val getLat: Double
        get() = prefManger.getLat
    val getLng: Double
        get() = prefManger.getLng
    val isStarted: Boolean
        get() = prefManger.isStarted
    val mapType: Int
        get() = prefManger.mapType

    val allLogs: Flow<List<LogEntry>>
        get() = logRepository.allLogs

    fun clearAllLogs() = onIO {
        logRepository.clearAllLogs()
    }

    private val _allFavList = MutableStateFlow<List<Favorite>>(emptyList())
    val allFavList: StateFlow<List<Favorite>> = _allFavList

    fun doGetUserDetails() {
        onIO {
            favoriteRepository.getAllFavorites
                .catch { e ->
                    Timber.tag("Favorite").d(e.message.toString())
                }
                .collectLatest {
                    _allFavList.emit(it)
                }
        }
    }

    fun update(start: Boolean, la: Double, ln: Double) {
        prefManger.update(start, la, ln)
        val coordStr = String.format(Locale.US, "(%.6f, %.6f)", la, ln)
        AppLogger.logApp(
            action = if (start) "PrefManager.update (开启/更新伪装位置)" else "PrefManager.update (停止定位伪装)",
            details = "state=${if (start) "STARTED" else "STOPPED"} | coord=$coordStr | accuracy=${prefManger.accuracy}m | randomJitter=${prefManger.isRandomPosition} | deepBinderGnss=${prefManger.isSystemHooked} | prefsWorldReadable=true",
            isSpoofed = start,
            level = LogEntry.LEVEL_INFO
        )
    }

    private val _response = MutableLiveData<Long>()
    val response: LiveData<Long> = _response

    private fun insertNewFavorite(favorite: Favorite) = onIO {
        val id = favoriteRepository.addNewFavorite(favorite)
        _response.postValue(id)
        AppLogger.logApp(
            action = "FavoriteRepository.addNewFavorite (新增收藏位置)",
            details = "rowId=$id | favId=${favorite.id} | name=\"${favorite.address}\" | lat=${favorite.lat} | lng=${favorite.lng}",
            level = if (id == -1L) LogEntry.LEVEL_ERROR else LogEntry.LEVEL_INFO
        )
    }

    val isXposed = MutableLiveData<Boolean>()

    @Keep
    fun isXposedModuleHooked(): Boolean {
        return XposedSelfCheck.isXposedModuleEnabled()
    }

    @Keep
    fun updateXposedState() {
        val active = isXposedModuleHooked() || XposedSelfCheck.isModuleActive(appContext)
        if (isXposed.value != active) {
            isXposed.value = active
        }
    }

    fun getXposedStatusSummary(): String {
        return XposedSelfCheck.getStatusSummary(appContext)
    }

    fun deleteFavorite(favorite: Favorite) = onIO {
        favoriteRepository.deleteFavorite(favorite)
        AppLogger.logApp(
            action = "FavoriteRepository.deleteFavorite (删除收藏位置)",
            details = "favId=${favorite.id} | name=\"${favorite.address}\" | lat=${favorite.lat} | lng=${favorite.lng}",
            level = LogEntry.LEVEL_INFO
        )
    }

    private fun getFavoriteSingle(i: Int): Favorite? {
        return favoriteRepository.getSingleFavorite(i.toLong())
    }

    private val _update = MutableStateFlow<UpdateChecker.Update?>(null)
    val update = _update.asStateFlow()

    init {
        if (!hasAutoCheckedOnStartup) {
            hasAutoCheckedOnStartup = true
            refreshUpdateCheck()
        }
    }

    fun refreshUpdateCheck() {
        viewModelScope.launch {
            AppLogger.logApp(
                action = "UpdateChecker.autoCheck (启动自动检查更新)",
                details = "currentVersion=${BuildConfig.VERSION_NAME} | tag=${BuildConfig.TAG_NAME} | channel=${prefManger.updateChannel}",
                level = LogEntry.LEVEL_DEBUG
            )
            withContext(Dispatchers.IO) {
                checkUpdates.clearCachedDownloads(appContext)
            }
            checkUpdates.getLatestRelease().collect {
                _update.emit(it)
                if (it != null) {
                    AppLogger.logApp(
                        action = "UpdateChecker.getLatestRelease (检测到新版本)",
                        details = "channel=${prefManger.updateChannel} | latestName=${it.name} | asset=${it.assetName} | url=${it.assetUrl} | timestamp=${it.timestamp}",
                        level = LogEntry.LEVEL_INFO
                    )
                }
            }
        }
    }

    fun manualCheckUpdates(onResult: (UpdateChecker.Update?) -> Unit) {
        viewModelScope.launch {
            AppLogger.logApp(
                action = "UpdateChecker.manualCheck (手动触发检查更新)",
                details = "currentVersion=${BuildConfig.VERSION_NAME} | tag=${BuildConfig.TAG_NAME} | channel=${prefManger.updateChannel}",
                level = LogEntry.LEVEL_INFO
            )
            withContext(Dispatchers.IO) {
                checkUpdates.clearCachedDownloads(appContext)
            }
            checkUpdates.getLatestRelease().collect { result ->
                _update.emit(result)
                if (result != null) {
                    AppLogger.logApp(
                        action = "UpdateChecker.getLatestRelease (检测到新版本)",
                        details = "channel=${prefManger.updateChannel} | latestName=${result.name} | asset=${result.assetName} | url=${result.assetUrl}",
                        level = LogEntry.LEVEL_INFO
                    )
                } else {
                    AppLogger.logApp(
                        action = "UpdateChecker.getLatestRelease (已是最新版本)",
                        details = "channel=${prefManger.updateChannel} | currentVersion=${BuildConfig.VERSION_NAME} | tag=${BuildConfig.TAG_NAME}",
                        level = LogEntry.LEVEL_DEBUG
                    )
                }
                onResult(result)
            }
        }
    }

    fun getAvailableUpdate(): UpdateChecker.Update? {
        return _update.value
    }

    fun clearUpdate() {
        viewModelScope.launch {
            _update.emit(null)
        }
    }

    private var requestId: Long? = null
    private var _downloadState = MutableStateFlow<State>(State.Idle)
    private var downloadFile: File? = null
    val downloadState = _downloadState.asStateFlow()

    fun startDownload(context: Context, update: UpdateChecker.Update) {
        if (_downloadState.value is State.Idle) {
            downloadUpdate(context, update.assetUrl, update.assetName)
        }
    }

    private val downloadStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            viewModelScope.launch {
                var success = false
                val query = DownloadManager.Query().apply {
                    setFilterById(requestId ?: return@apply)
                }
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (cursor.getInt(columnIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                        success = true
                    }
                }
                if (success && downloadFile != null) {
                    val outputUri = FileProvider.getUriForFile(
                        context,
                        BuildConfig.APPLICATION_ID + ".provider",
                        downloadFile!!
                    )
                    _downloadState.emit(State.Done(outputUri))
                } else {
                    _downloadState.emit(State.Failed)
                }
            }
        }
    }

    private val downloadObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            viewModelScope.launch {
                val query = DownloadManager.Query()
                query.setFilterById(requestId ?: return@launch)
                val c: Cursor = downloadManager.query(query)
                var progress = 0.0
                if (c.moveToFirst()) {
                    val sizeIndex: Int =
                        c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val downloadedIndex: Int =
                        c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val size = c.getInt(sizeIndex)
                    val downloaded = c.getInt(downloadedIndex)
                    if (size != -1) progress = downloaded * 100.0 / size
                }
                _downloadState.emit(State.Downloading(progress.roundToInt()))
            }
        }
    }

    private fun downloadUpdate(context: Context, url: String, fileName: String) = viewModelScope.launch {
        val downloadFolder = File(context.externalCacheDir, "updates").apply {
            mkdirs()
        }
        downloadFile = File(downloadFolder, fileName)
        ContextCompat.registerReceiver(
            context,
            downloadStateReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        context.contentResolver.registerContentObserver(
            Uri.parse("content://downloads/my_downloads"),
            true,
            downloadObserver
        )
        requestId = DownloadManager.Request(Uri.parse(url)).apply {
            setDescription(context.getString(R.string.download_manager_description))
            setTitle(context.getString(R.string.app_name))
            setDestinationUri(Uri.fromFile(downloadFile!!))
        }.run {
            downloadManager.enqueue(this)
        }
    }

    fun openPackageInstaller(context: Context, uri: Uri) {
        runCatching {
            Intent(Intent.ACTION_VIEW, uri).apply {
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.also {
                context.startActivity(it)
            }
        }.onFailure {
            it.printStackTrace()
            context.showToast(context.getString(R.string.app_update_failed))
        }
    }

    fun cancelDownload(context: Context) {
        viewModelScope.launch {
            requestId?.let {
                downloadManager.remove(it)
            }
            runCatching { context.unregisterReceiver(downloadStateReceiver) }
            runCatching { context.contentResolver.unregisterContentObserver(downloadObserver) }
            _downloadState.emit(State.Idle)
        }
    }

    sealed class State {
        object Idle : State()
        data class Downloading(val progress: Int) : State()
        data class Done(val fileUri: Uri) : State()
        object Failed : State()
    }

    fun storeFavorite(
        address: String,
        lat: Double,
        lon: Double
    ) = onIO {
        val slot: Int
        var i = 0
        while (true) {
            if (getFavoriteSingle(i) == null) {
                slot = i
                break
            } else {
                i++
            }
        }
        insertNewFavorite(Favorite(id = slot.toLong(), address = address, lat = lat, lng = lon))
    }
}