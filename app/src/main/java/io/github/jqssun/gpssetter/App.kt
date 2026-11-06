package io.github.jqssun.gpssetter

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp
import io.github.jqssun.gpssetter.utils.AppLogger
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import timber.log.Timber

lateinit var gsApp: App

@HiltAndroidApp
class App : Application() {
    val globalScope = CoroutineScope(Dispatchers.Default)

    companion object {
        fun commonInit() {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
            Timber.plant(AppLogger.AppLogTree())
        }
    }

    override fun onCreate() {
        super.onCreate()
        gsApp = this
        AppLogger.init(this)
        commonInit()
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)
        AppLogger.logApp(
            action = "App.onCreate (应用启动)",
            details = "版本=${BuildConfig.VERSION_NAME}, 当前伪装状态=${if (PrefManager.isStarted) "开启 (${PrefManager.getLat}, ${PrefManager.getLng})" else "关闭"}"
        )
    }
}