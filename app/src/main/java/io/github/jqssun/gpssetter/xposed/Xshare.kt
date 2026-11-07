package io.github.jqssun.gpssetter.xposed

import de.robv.android.xposed.XSharedPreferences
import io.github.jqssun.gpssetter.BuildConfig

class Xshare {

    private val xPref: XSharedPreferences by lazy {
        XSharedPreferences(
            BuildConfig.APPLICATION_ID,
            "${BuildConfig.APPLICATION_ID}_prefs"
        ).apply {
            runCatching { makeWorldReadable() }
        }
    }

    private fun pref(): XSharedPreferences {
        return xPref
    }

    fun reload() {
        runCatching {
            xPref.makeWorldReadable()
            xPref.reload()
        }
    }

    val isStarted: Boolean
        get() = pref().getBoolean(
            "start",
            false
        )

    val getLat: Double
        get() = pref().getFloat(
            "latitude",
            40.7128f
        ).toDouble()

    val getLng: Double
        get() = pref().getFloat(
            "longitude",
            -74.0060f
        ).toDouble()

    val isHookedSystem: Boolean
        get() = pref().getBoolean(
            "system_hooked",
            true
        )

    val isRandomPosition: Boolean
        get() = pref().getBoolean(
            "random_position",
            false
        )

    val accuracy: String?
        get() = pref().getString("accuracy_level", "10")

    val logLevel: String
        get() = pref().getString("log_level", "ERROR") ?: "ERROR"

    val logSessionResetTimestamp: Long
        get() = pref().getLong("log_session_reset_time", 0L)
}