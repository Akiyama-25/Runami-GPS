package io.github.jqssun.gpssetter.xposed

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.Keep
import io.github.jqssun.gpssetter.BuildConfig

@Keep
object XposedSelfCheck {

    @Volatile
    @JvmField
    var isHookedByXposed: Boolean = false

    @Volatile
    @JvmField
    var hookedApiVersion: Int = 0

    @Keep
    @JvmStatic
    fun isXposedModuleEnabled(): Boolean {
        return isHookedByXposed
    }

    @Keep
    @JvmStatic
    fun getXposedVersion(): Int {
        return hookedApiVersion
    }

    @SuppressLint("WorldReadableFiles")
    @Keep
    @JvmStatic
    fun isWorldReadablePrefsSupported(context: Context): Boolean {
        return try {
            val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
            context.getSharedPreferences(prefsFile, Context.MODE_WORLD_READABLE)
            true
        } catch (t: Throwable) {
            false
        }
    }

    @Keep
    @JvmStatic
    fun isModuleActive(context: Context): Boolean {
        return isXposedModuleEnabled() || isWorldReadablePrefsSupported(context)
    }

    @Keep
    @JvmStatic
    fun getStatusSummary(context: Context): String {
        val selfHooked = isXposedModuleEnabled()
        val prefsHooked = isWorldReadablePrefsSupported(context)
        val apiVer = getXposedVersion()
        return when {
            selfHooked && apiVer > 0 -> "ACTIVE (SelfHook=true, XSharedPrefs=$prefsHooked, API=$apiVer)"
            selfHooked -> "ACTIVE (SelfHook=true, XSharedPrefs=$prefsHooked)"
            prefsHooked -> "ACTIVE (XSharedPrefs=true, MODE_WORLD_READABLE=true)"
            else -> "INACTIVE (SelfHook=false, XSharedPrefs=false)"
        }
    }
}
