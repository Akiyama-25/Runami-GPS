package io.github.jqssun.gpssetter.xposed

import androidx.annotation.Keep
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.jqssun.gpssetter.BuildConfig

@Keep
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkgName = lpparam.packageName ?: return
        if (pkgName == BuildConfig.APPLICATION_ID) {
            val apiVer = runCatching { XposedBridge.getXposedVersion() }.getOrDefault(101)
            runCatching {
                val selfCheckClass = XposedHelpers.findClassIfExists(
                    "io.github.jqssun.gpssetter.xposed.XposedSelfCheck",
                    lpparam.classLoader
                )
                if (selfCheckClass != null) {
                    runCatching {
                        XposedHelpers.setStaticBooleanField(selfCheckClass, "isHookedByXposed", true)
                        XposedHelpers.setStaticIntField(selfCheckClass, "hookedApiVersion", apiVer)
                    }
                    runCatching {
                        XposedHelpers.findAndHookMethod(
                            selfCheckClass,
                            "isXposedModuleEnabled",
                            XC_MethodReplacement.returnConstant(true)
                        )
                    }
                    runCatching {
                        XposedHelpers.findAndHookMethod(
                            selfCheckClass,
                            "getXposedVersion",
                            XC_MethodReplacement.returnConstant(apiVer)
                        )
                    }
                }
            }
            runCatching {
                XposedHelpers.findAndHookMethod(
                    "io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel",
                    lpparam.classLoader,
                    "isXposedModuleHooked",
                    XC_MethodReplacement.returnConstant(true)
                )
            }
            runCatching {
                XposedBridge.log("I/RunamiGPS [APP] [$pkgName] HookEntry.handleLoadPackage: Self-hook active (API=$apiVer)")
            }
            return
        }
        LocationHook.initHooks(lpparam)
    }
}
