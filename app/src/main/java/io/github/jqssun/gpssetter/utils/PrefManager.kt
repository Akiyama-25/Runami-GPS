package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.gsApp

@SuppressLint("WorldReadableFiles")
object PrefManager {

    private const val START = "start"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    private const val HOOKED_SYSTEM = "system_hooked"
    private const val RANDOM_POSITION = "random_position"
    private const val ACCURACY_SETTING = "accuracy_level"
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val APP_LANGUAGE = "app_language"
    private const val DISABLE_UPDATE = "update_disabled"
    private const val UPDATE_CHANNEL = "update_channel"
    private const val ENABLE_JOYSTICK = "joystick_enabled"
    private const val LOG_LEVEL = "log_level"

    private val pref: SharedPreferences by lazy {
        try {
            val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
            gsApp.getSharedPreferences(
                prefsFile,
                Context.MODE_WORLD_READABLE
            )
        } catch (e: SecurityException) {
            val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
            gsApp.getSharedPreferences(
                prefsFile,
                Context.MODE_PRIVATE
            )
        }
    }

    val isStarted: Boolean
        get() = pref.getBoolean(START, false)

    val getLat: Double
        get() = pref.getFloat(LATITUDE, 40.7128F).toDouble()

    val getLng: Double
        get() = pref.getFloat(LONGITUDE, -74.0060F).toDouble()

    var isSystemHooked: Boolean
        get() = pref.getBoolean(HOOKED_SYSTEM, true)
        set(value) { pref.edit().putBoolean(HOOKED_SYSTEM, value).apply() }

    var isRandomPosition: Boolean
        get() = pref.getBoolean(RANDOM_POSITION, false)
        set(value) { pref.edit().putBoolean(RANDOM_POSITION, value).apply() }

    var accuracy: String?
        get() = pref.getString(ACCURACY_SETTING, "10")
        set(value) { pref.edit().putString(ACCURACY_SETTING, value).apply() }

    var mapType: Int
        get() = pref.getInt(MAP_TYPE, 1)
        set(value) { pref.edit().putInt(MAP_TYPE, value).apply() }

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit().putInt(DARK_THEME, value).apply()

    var appLanguage: String
        get() = pref.getString(APP_LANGUAGE, "system") ?: "system"
        set(value) = pref.edit().putString(APP_LANGUAGE, value).apply()

    var isUpdateDisabled: Boolean
        get() = pref.getBoolean(DISABLE_UPDATE, true)
        set(value) = pref.edit().putBoolean(DISABLE_UPDATE, value).apply()

    var updateChannel: String
        get() = pref.getString(UPDATE_CHANNEL, "release") ?: "release"
        set(value) = pref.edit().putString(UPDATE_CHANNEL, value).apply()

    var isJoystickEnabled: Boolean
        get() = pref.getBoolean(ENABLE_JOYSTICK, false)
        set(value) = pref.edit().putBoolean(ENABLE_JOYSTICK, value).apply()

    var logLevel: String
        get() = pref.getString(LOG_LEVEL, "ERROR") ?: "ERROR"
        set(value) {
            pref.edit().putString(LOG_LEVEL, value).apply()
            makeWorldReadable()
        }

    fun update(start: Boolean, la: Double, ln: Double) {
        val prefEditor = pref.edit()
        prefEditor.putFloat(LATITUDE, la.toFloat())
        prefEditor.putFloat(LONGITUDE, ln.toFloat())
        prefEditor.putBoolean(START, start)
        prefEditor.apply()
        makeWorldReadable()
    }

    @SuppressLint("SetWorldReadable")
    fun makeWorldReadable() {
        runCatching {
            val prefsDir = java.io.File(gsApp.applicationInfo.dataDir, "shared_prefs")
            val prefsFile = java.io.File(prefsDir, "${BuildConfig.APPLICATION_ID}_prefs.xml")
            if (prefsDir.exists()) {
                prefsDir.setReadable(true, false)
                prefsDir.setExecutable(true, false)
            }
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
            }
        }
    }
}