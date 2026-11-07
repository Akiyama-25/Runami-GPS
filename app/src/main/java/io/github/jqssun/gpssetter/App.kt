package io.github.jqssun.gpssetter

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp
import io.github.jqssun.gpssetter.utils.AppLogger
import io.github.jqssun.gpssetter.utils.LocaleHelper
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
        LocaleHelper.applyLanguage(PrefManager.appLanguage)
        val spoofStatus = if (PrefManager.isStarted) {
            String.format(java.util.Locale.US, "开启 (%.6f, %.6f)", PrefManager.getLat, PrefManager.getLng)
        } else {
            "关闭"
        }
        AppLogger.logApp(
            action = "App.onCreate (应用启动)",
            details = "版本=${BuildConfig.VERSION_NAME}, 语言=${PrefManager.appLanguage}, 当前伪装状态=$spoofStatus"
        )
    }
}