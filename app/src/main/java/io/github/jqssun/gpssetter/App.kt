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
            String.format(java.util.Locale.US, "ACTIVE(lat=%.6f, lng=%.6f, acc=%sm, random=%s, gnss=%s)", PrefManager.getLat, PrefManager.getLng, PrefManager.accuracy ?: "10", PrefManager.isRandomPosition, PrefManager.isSystemHooked)
        } else {
            "INACTIVE"
        }
        AppLogger.logApp(
            action = "App.onCreate (应用进程初始化)",
            details = "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) | pkg=${BuildConfig.APPLICATION_ID} | lang=${PrefManager.appLanguage} | theme=${PrefManager.darkTheme} | logLevel=${PrefManager.logLevel} | spoofState=$spoofStatus",
            level = io.github.jqssun.gpssetter.room.LogEntry.LEVEL_INFO
        )
    }
}