package io.github.jqssun.gpssetter.xposed

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.jqssun.gpssetter.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber
import java.util.Collections
import java.util.Locale
import java.util.Random
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kotlin.math.cos

object LocationHook {

    @Volatile
    var newlat: Double = 40.7128

    @Volatile
    var newlng: Double = -74.0060

    private var lastBaseLat: Double = Double.NaN
    private var lastBaseLng: Double = Double.NaN
    private var lastJitterTime: Long = 0L

    private const val pi = 3.14159265359
    private const val earth = 6378137.0
    private val rand: Random = Random()

    @Volatile
    private var accuracy: Float = 10.0f

    @Volatile
    private var isStartedCache: Boolean = false

    @Volatile
    private var isAdvancedHookCache: Boolean = true

    private val settings = Xshare()

    @Volatile
    private var mLastUpdated: Long = 0L

    @Volatile
    private var currentHookedPkg: String = ""

    private val ignorePkg = setOf(
        "android",
        "com.android.location.fused",
        "com.android.phone",
        "com.android.systemui",
        BuildConfig.APPLICATION_ID
    )

    private val hookedListenerClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())
    private val hookedGmsCallbackClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    private val activeListeners = Collections.synchronizedMap(WeakHashMap<LocationListener, String>())
    private val activeGmsCallbacks = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var isLiveTickerScheduled = false

    private fun formatCoord(lat: Double = newlat, lng: Double = newlng): String {
        return String.format(Locale.US, "(%.6f, %.6f) acc=%.1fm", lat, lng, accuracy)
    }

    private val liveUpdateRunnable = object : Runnable {
        override fun run() {
            try {
                updateLocation(forceReload = true)
                if (isStartedCache) {
                    pushLiveLocationToActiveListeners()
                }
            } catch (t: Throwable) {
                XposedBridge.log("GPS Setter live ticker error: $t")
            } finally {
                val hasActive = synchronized(activeListeners) { activeListeners.isNotEmpty() } ||
                    synchronized(activeGmsCallbacks) { activeGmsCallbacks.isNotEmpty() }
                if (hasActive) {
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    isLiveTickerScheduled = false
                }
            }
        }
    }

    private fun ensureLiveTickerRunning() {
        mainHandler.post {
            if (!isLiveTickerScheduled) {
                isLiveTickerScheduled = true
                mainHandler.postDelayed(liveUpdateRunnable, 300L)
            }
        }
    }

    private fun pushLiveLocationToActiveListeners() {
        val listenerSnapshot = synchronized(activeListeners) {
            activeListeners.entries.mapNotNull { entry ->
                entry.key?.let { it to (entry.value ?: LocationManager.GPS_PROVIDER) }
            }
        }
        for ((listener, provider) in listenerSnapshot) {
            runCatching {
                val loc = createSpoofedLocation(null, provider)
                listener.onLocationChanged(loc)
                if (currentHookedPkg.isNotEmpty()) {
                    HookLogReporter.recordLocationRequest(
                        packageName = currentHookedPkg,
                        action = "LocationListener.onLocationChanged (实时推送)",
                        details = "provider=$provider -> 伪装位置 ${formatCoord()}",
                        isSpoofed = true,
                        throttleMs = 3000L
                    )
                }
            }
        }

        val gmsSnapshot = synchronized(activeGmsCallbacks) {
            activeGmsCallbacks.keys.filterNotNull()
        }
        for (callback in gmsSnapshot) {
            runCatching {
                val loc = createSpoofedLocation(null, LocationManager.GPS_PROVIDER)
                val resultClass = XposedHelpers.findClassIfExists(
                    "com.google.android.gms.location.LocationResult",
                    callback.javaClass.classLoader
                )
                if (resultClass != null) {
                    val locationResult = XposedHelpers.callStaticMethod(
                        resultClass,
                        "create",
                        listOf(loc)
                    )
                    XposedHelpers.callMethod(callback, "onLocationResult", locationResult)
                    if (currentHookedPkg.isNotEmpty()) {
                        HookLogReporter.recordLocationRequest(
                            packageName = currentHookedPkg,
                            action = "FusedLocationCallback.onLocationResult (实时推送)",
                            details = "GMS Fused -> 伪装位置 ${formatCoord()}",
                            isSpoofed = true,
                            throttleMs = 3000L
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    private fun updateLocation(forceReload: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            if (!forceReload && now - mLastUpdated <= 100L) {
                return
            }
            mLastUpdated = now
            settings.reload()

            isStartedCache = settings.isStarted
            isAdvancedHookCache = settings.isHookedSystem

            val baseLat = settings.getLat
            val baseLng = settings.getLng
            val parsedAcc = settings.accuracy?.toFloatOrNull() ?: 10.0f
            accuracy = if (parsedAcc > 0f) parsedAcc else 10.0f

            if (settings.isRandomPosition) {
                if (baseLat != lastBaseLat || baseLng != lastBaseLng || now - lastJitterTime > 1000L) {
                    val x = (rand.nextInt(50) - 15).toDouble()
                    val y = (rand.nextInt(50) - 15).toDouble()
                    val dlat = x / earth
                    val dlng = y / (earth * cos(pi * baseLat / 180.0))
                    newlat = baseLat + (dlat * 180.0 / pi)
                    newlng = baseLng + (dlng * 180.0 / pi)
                    lastBaseLat = baseLat
                    lastBaseLng = baseLng
                    lastJitterTime = now
                }
            } else {
                newlat = baseLat
                newlng = baseLng
                lastBaseLat = baseLat
                lastBaseLng = baseLng
            }
        } catch (e: Exception) {
            Timber.tag("GPS Setter").e(e, "Failed to update XposedSettings")
        }
    }

    private fun isSpoofingActive(packageName: String): Boolean {
        if (ignorePkg.contains(packageName)) return false
        updateLocation()
        return isStartedCache
    }

    private fun clearMockFlag(location: Location) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.invoke(
                    Location::class.java,
                    location,
                    "setIsFromMockProvider",
                    false
                )
            } else {
                XposedHelpers.callMethod(location, "setIsFromMockProvider", false)
            }
        } catch (e: Throwable) {
            runCatching {
                XposedHelpers.callMethod(location, "setIsFromMockProvider", false)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun createSpoofedLocation(
        origin: Location? = null,
        provider: String = LocationManager.GPS_PROVIDER
    ): Location {
        val safeProvider = origin?.provider?.takeIf { it.isNotEmpty() } ?: provider.ifEmpty { LocationManager.GPS_PROVIDER }
        val location = Location(safeProvider)
        val nowMs = System.currentTimeMillis()
        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()

        location.latitude = newlat
        location.longitude = newlng
        location.accuracy = if (accuracy > 0f) accuracy else 10.0f
        location.altitude = origin?.takeIf { it.hasAltitude() }?.altitude ?: 30.0
        location.speed = 0f
        location.bearing = origin?.takeIf { it.hasBearing() }?.bearing ?: 0f
        location.time = nowMs
        location.elapsedRealtimeNanos = nowElapsedNanos

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.verticalAccuracyMeters = origin?.takeIf { it.hasVerticalAccuracy() }?.verticalAccuracyMeters ?: location.accuracy
            location.speedAccuracyMetersPerSecond = 0.1f
            location.bearingAccuracyDegrees = 1.0f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            location.elapsedRealtimeUncertaintyNanos = 1000.0
        }

        val extras = origin?.extras?.let { Bundle(it) } ?: Bundle()
        if (!extras.containsKey("satellites")) {
            extras.putInt("satellites", 12)
        }
        location.extras = extras

        clearMockFlag(location)
        return location
    }

    @SuppressLint("NewApi")
    private fun amendLocationInPlace(location: Location) {
        location.latitude = newlat
        location.longitude = newlng
        location.accuracy = if (accuracy > 0f) accuracy else 10.0f
        if (!location.hasAltitude()) {
            location.altitude = 30.0
        }
        location.speed = 0f
        location.time = System.currentTimeMillis()
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!location.hasVerticalAccuracy()) {
                location.verticalAccuracyMeters = location.accuracy
            }
            location.speedAccuracyMetersPerSecond = 0.1f
            location.bearingAccuracyDegrees = 1.0f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            location.elapsedRealtimeUncertaintyNanos = 1000.0
        }

        val extras = location.extras ?: Bundle()
        if (!extras.containsKey("satellites")) {
            extras.putInt("satellites", 12)
            location.extras = extras
        }

        clearMockFlag(location)
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkgName = lpparam.packageName ?: return

        // Strictly scope hooks to LSPosed-checked target applications only.
        // Never mutate global system_server state so unchecked apps always receive real locations.
        if (ignorePkg.contains(pkgName)) {
            return
        }

        currentHookedPkg = pkgName
        val active = isSpoofingActive(pkgName)
        HookLogReporter.recordLocationRequest(
            packageName = pkgName,
            action = "HookEntry.initHooks (注入目标进程)",
            details = if (active) {
                "已挂载定位拦截钩子 · 当前伪装开启 ${formatCoord()}"
            } else {
                "已挂载定位拦截钩子 · 当前伪装未开启（透传真实位置）"
            },
            isSpoofed = active,
            throttleMs = 0L
        )

        hookLocationClass(lpparam, pkgName)
        hookLocationResultClass(lpparam, pkgName)
        hookLocationManagerClass(lpparam, pkgName)
        hookGmsLocationClasses(lpparam, pkgName)
        hookGnssAndMockDetection(lpparam, pkgName)
    }

    private fun hookLocationClass(lpparam: XC_LoadPackage.LoadPackageParam, pkgName: String) {
        val locationClass = XposedHelpers.findClassIfExists(
            "android.location.Location",
            lpparam.classLoader
        ) ?: return

        for (method in locationClass.declaredMethods) {
            when (method.name) {
                "getLatitude" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isSpoofingActive(pkgName)) {
                                    param.result = newlat
                                    HookLogReporter.recordLocationRequest(
                                        packageName = pkgName,
                                        action = "Location.getLatitude/getLongitude",
                                        details = "直接读取坐标 -> 返回伪装位置 ${formatCoord()}",
                                        isSpoofed = true,
                                        throttleMs = 4000L
                                    )
                                }
                            }
                        }
                    )
                }
                "getLongitude" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isSpoofingActive(pkgName)) {
                                    param.result = newlng
                                }
                            }
                        }
                    )
                }
                "getAccuracy" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isSpoofingActive(pkgName)) {
                                    param.result = if (accuracy > 0f) accuracy else 10.0f
                                }
                            }
                        }
                    )
                }
                "hasAccuracy" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isSpoofingActive(pkgName)) {
                                    param.result = true
                                }
                            }
                        }
                    )
                }
                "isFromMockProvider", "isMock" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isSpoofingActive(pkgName)) {
                                    param.result = false
                                    HookLogReporter.recordLocationRequest(
                                        packageName = pkgName,
                                        action = "Location.${method.name} (模拟位置检测)",
                                        details = "拦截虚拟定位检测 -> 强制返回 false",
                                        isSpoofed = true,
                                        throttleMs = 5000L
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }

        XposedHelpers.findAndHookMethod(
            locationClass,
            "set",
            Location::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isSpoofingActive(pkgName)) {
                        val self = param.thisObject as? Location ?: return
                        amendLocationInPlace(self)
                    }
                }
            }
        )
    }

    private fun hookLocationResultClass(lpparam: XC_LoadPackage.LoadPackageParam, pkgName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val locationResultClass = XposedHelpers.findClassIfExists(
            "android.location.LocationResult",
            lpparam.classLoader
        ) ?: return

        for (method in locationResultClass.declaredMethods) {
            when (method.name) {
                "getLastLocation", "get" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val active = isSpoofingActive(pkgName)
                                if (active) {
                                    val loc = param.result as? Location
                                    if (loc != null) {
                                        amendLocationInPlace(loc)
                                    } else {
                                        param.result = createSpoofedLocation()
                                    }
                                }
                                HookLogReporter.recordLocationRequest(
                                    packageName = pkgName,
                                    action = "LocationResult.${method.name}",
                                    details = if (active) "返回伪装位置 ${formatCoord()}" else "未开启伪装，透传真实位置",
                                    isSpoofed = active,
                                    throttleMs = 2000L
                                )
                            }
                        }
                    )
                }
                "asList" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val active = isSpoofingActive(pkgName)
                                if (active) {
                                    @Suppress("UNCHECKED_CAST")
                                    val list = param.result as? List<Location>
                                    if (!list.isNullOrEmpty()) {
                                        list.forEach { amendLocationInPlace(it) }
                                    } else {
                                        param.result = listOf(createSpoofedLocation())
                                    }
                                }
                                HookLogReporter.recordLocationRequest(
                                    packageName = pkgName,
                                    action = "LocationResult.asList",
                                    details = if (active) "改写位置列表 -> ${formatCoord()}" else "未开启伪装，透传真实位置列表",
                                    isSpoofed = active,
                                    throttleMs = 2000L
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    private fun hookLocationManagerClass(lpparam: XC_LoadPackage.LoadPackageParam, pkgName: String) {
        val locationManagerClass = XposedHelpers.findClassIfExists(
            "android.location.LocationManager",
            lpparam.classLoader
        ) ?: return

        for (method in locationManagerClass.declaredMethods) {
            when (method.name) {
                "getLastKnownLocation" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val provider = (param.args.firstOrNull() as? String)
                                    ?: LocationManager.GPS_PROVIDER
                                val active = isSpoofingActive(pkgName)
                                if (active) {
                                    val origin = param.result as? Location
                                    param.result = createSpoofedLocation(origin, provider)
                                }
                                HookLogReporter.recordLocationRequest(
                                    packageName = pkgName,
                                    action = "LocationManager.getLastKnownLocation",
                                    details = if (active) {
                                        "provider=$provider -> 返回伪装位置 ${formatCoord()}"
                                    } else {
                                        "provider=$provider -> 未开启伪装，透传真实位置"
                                    },
                                    isSpoofed = active,
                                    throttleMs = 500L
                                )
                            }
                        }
                    )
                }
                "getCurrentLocation" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val provider = (param.args.firstOrNull() as? String)
                                    ?: LocationManager.GPS_PROVIDER
                                val active = isSpoofingActive(pkgName)
                                HookLogReporter.recordLocationRequest(
                                    packageName = pkgName,
                                    action = "LocationManager.getCurrentLocation",
                                    details = if (active) {
                                        "provider=$provider -> 拦截并回调伪装位置 ${formatCoord()}"
                                    } else {
                                        "provider=$provider -> 未开启伪装，透传真实请求"
                                    },
                                    isSpoofed = active,
                                    throttleMs = 300L
                                )
                                if (!active) return
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    val consumer = param.args.firstOrNull { it is Consumer<*> }
                                    if (consumer != null) {
                                        @Suppress("UNCHECKED_CAST")
                                        val locConsumer = consumer as Consumer<Location>
                                        val spoofed = createSpoofedLocation(null, provider)
                                        mainHandler.post {
                                            runCatching { locConsumer.accept(spoofed) }
                                        }
                                        param.result = null
                                    }
                                }
                            }
                        }
                    )
                }
                "requestLocationUpdates", "requestSingleUpdate" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val listener = param.args.firstOrNull { it is LocationListener } as? LocationListener
                                val provider = (param.args.firstOrNull { it is String } as? String)
                                    ?: LocationManager.GPS_PROVIDER
                                val active = isSpoofingActive(pkgName)

                                HookLogReporter.recordLocationRequest(
                                    packageName = pkgName,
                                    action = "LocationManager.${method.name}",
                                    details = if (active) {
                                        "provider=$provider, listener=${listener?.javaClass?.simpleName ?: "PendingIntent"} -> 注入伪装定位流 ${formatCoord()}"
                                    } else {
                                        "provider=$provider, listener=${listener?.javaClass?.simpleName ?: "PendingIntent"} -> 未开启伪装，监听真实位置"
                                    },
                                    isSpoofed = active,
                                    throttleMs = 300L
                                )

                                if (listener == null) return
                                hookListenerClassIfNeeded(listener.javaClass, pkgName)
                                activeListeners[listener] = provider
                                ensureLiveTickerRunning()

                                if (active) {
                                    val initialLoc = createSpoofedLocation(null, provider)
                                    mainHandler.post {
                                        runCatching { listener.onLocationChanged(initialLoc) }
                                    }
                                }
                            }
                        }
                    )
                }
                "removeUpdates" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val listener = param.args.firstOrNull { it is LocationListener } as? LocationListener
                                if (listener != null) {
                                    activeListeners.remove(listener)
                                    HookLogReporter.recordLocationRequest(
                                        packageName = pkgName,
                                        action = "LocationManager.removeUpdates",
                                        details = "移除监听器 ${listener.javaClass.simpleName}",
                                        isSpoofed = isSpoofingActive(pkgName),
                                        throttleMs = 300L
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun hookListenerClassIfNeeded(listenerClass: Class<*>, pkgName: String) {
        if (!hookedListenerClasses.add(listenerClass)) return

        for (method in listenerClass.declaredMethods) {
            if (method.name == "onLocationChanged") {
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val active = isSpoofingActive(pkgName)
                            if (!active) {
                                HookLogReporter.recordLocationRequest(
                                    packageName = pkgName,
                                    action = "LocationListener.onLocationChanged",
                                    details = "listener=${listenerClass.simpleName} -> 透传系统真实位置回调",
                                    isSpoofed = false,
                                    throttleMs = 3000L
                                )
                                return
                            }
                            val firstArg = param.args.firstOrNull() ?: return
                            if (firstArg is Location) {
                                amendLocationInPlace(firstArg)
                            } else if (firstArg is List<*>) {
                                firstArg.filterIsInstance<Location>().forEach {
                                    amendLocationInPlace(it)
                                }
                            }
                            HookLogReporter.recordLocationRequest(
                                packageName = pkgName,
                                action = "LocationListener.onLocationChanged",
                                details = "listener=${listenerClass.simpleName} -> 覆写回调为伪装位置 ${formatCoord()}",
                                isSpoofed = true,
                                throttleMs = 2000L
                            )
                        }
                    }
                )
            }
        }
    }

    private fun hookGmsLocationClasses(lpparam: XC_LoadPackage.LoadPackageParam, pkgName: String) {
        val gmsResultClass = XposedHelpers.findClassIfExists(
            "com.google.android.gms.location.LocationResult",
            lpparam.classLoader
        )
        if (gmsResultClass != null) {
            for (method in gmsResultClass.declaredMethods) {
                when (method.name) {
                    "getLastLocation" -> {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    val active = isSpoofingActive(pkgName)
                                    if (active) {
                                        val loc = param.result as? Location
                                        if (loc != null) {
                                            amendLocationInPlace(loc)
                                        } else {
                                            param.result = createSpoofedLocation()
                                        }
                                    }
                                    HookLogReporter.recordLocationRequest(
                                        packageName = pkgName,
                                        action = "GmsLocationResult.getLastLocation",
                                        details = if (active) "GMS 返回伪装位置 ${formatCoord()}" else "GMS 透传真实位置",
                                        isSpoofed = active,
                                        throttleMs = 2000L
                                    )
                                }
                            }
                        )
                    }
                    "getLocations" -> {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    val active = isSpoofingActive(pkgName)
                                    if (active) {
                                        @Suppress("UNCHECKED_CAST")
                                        val list = param.result as? List<Location>
                                        if (!list.isNullOrEmpty()) {
                                            list.forEach { amendLocationInPlace(it) }
                                        } else {
                                            param.result = listOf(createSpoofedLocation())
                                        }
                                    }
                                    HookLogReporter.recordLocationRequest(
                                        packageName = pkgName,
                                        action = "GmsLocationResult.getLocations",
                                        details = if (active) "GMS 位置列表改写 -> ${formatCoord()}" else "GMS 透传真实位置列表",
                                        isSpoofed = active,
                                        throttleMs = 2000L
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        val gmsCallbackClass = XposedHelpers.findClassIfExists(
            "com.google.android.gms.location.LocationCallback",
            lpparam.classLoader
        )
        if (gmsCallbackClass != null) {
            val fusedClientClass = XposedHelpers.findClassIfExists(
                "com.google.android.gms.location.FusedLocationProviderClient",
                lpparam.classLoader
            )
            if (fusedClientClass != null) {
                for (method in fusedClientClass.declaredMethods) {
                    if (method.name == "requestLocationUpdates") {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val callback = param.args.firstOrNull {
                                        it != null && gmsCallbackClass.isAssignableFrom(it.javaClass)
                                    }
                                    val active = isSpoofingActive(pkgName)
                                    HookLogReporter.recordLocationRequest(
                                        packageName = pkgName,
                                        action = "FusedLocationProviderClient.requestLocationUpdates",
                                        details = if (active) {
                                            "callback=${callback?.javaClass?.simpleName ?: "PendingIntent"} -> 注入 GMS 伪装位置流 ${formatCoord()}"
                                        } else {
                                            "callback=${callback?.javaClass?.simpleName ?: "PendingIntent"} -> 未开启伪装，透传 GMS 请求"
                                        },
                                        isSpoofed = active,
                                        throttleMs = 300L
                                    )
                                    if (callback == null) return
                                    hookGmsCallbackClassIfNeeded(callback.javaClass, pkgName)
                                    activeGmsCallbacks[callback] = true
                                    ensureLiveTickerRunning()
                                }
                            }
                        )
                    } else if (method.name == "removeLocationUpdates") {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val callback = param.args.firstOrNull {
                                        it != null && gmsCallbackClass.isAssignableFrom(it.javaClass)
                                    }
                                    if (callback != null) {
                                        activeGmsCallbacks.remove(callback)
                                        HookLogReporter.recordLocationRequest(
                                            packageName = pkgName,
                                            action = "FusedLocationProviderClient.removeLocationUpdates",
                                            details = "移除 GMS 回调 ${callback.javaClass.simpleName}",
                                            isSpoofed = isSpoofingActive(pkgName),
                                            throttleMs = 300L
                                        )
                                    }
                                }
                            }
                        )
                    } else if (method.name == "getLastLocation" || method.name == "getCurrentLocation") {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val active = isSpoofingActive(pkgName)
                                    HookLogReporter.recordLocationRequest(
                                        packageName = pkgName,
                                        action = "FusedLocationProviderClient.${method.name}",
                                        details = if (active) {
                                            "发起 GMS 单次定位请求 -> 将返回伪装位置 ${formatCoord()}"
                                        } else {
                                            "发起 GMS 单次定位请求 -> 未开启伪装，透传真实位置"
                                        },
                                        isSpoofed = active,
                                        throttleMs = 500L
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun hookGmsCallbackClassIfNeeded(callbackClass: Class<*>, pkgName: String) {
        if (!hookedGmsCallbackClasses.add(callbackClass)) return
        for (method in callbackClass.declaredMethods) {
            if (method.name == "onLocationResult") {
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val active = isSpoofingActive(pkgName)
                            if (!active) {
                                HookLogReporter.recordLocationRequest(
                                    packageName = pkgName,
                                    action = "LocationCallback.onLocationResult",
                                    details = "callback=${callbackClass.simpleName} -> 透传 GMS 真实位置回调",
                                    isSpoofed = false,
                                    throttleMs = 3000L
                                )
                                return
                            }
                            val resultObj = param.args.firstOrNull() ?: return
                            runCatching {
                                @Suppress("UNCHECKED_CAST")
                                val locations = XposedHelpers.callMethod(resultObj, "getLocations") as? List<Location>
                                locations?.forEach { amendLocationInPlace(it) }
                            }
                            HookLogReporter.recordLocationRequest(
                                packageName = pkgName,
                                action = "LocationCallback.onLocationResult",
                                details = "callback=${callbackClass.simpleName} -> 覆写 GMS 回调为伪装位置 ${formatCoord()}",
                                isSpoofed = true,
                                throttleMs = 2000L
                            )
                        }
                    }
                )
            }
        }
    }

    private fun hookGnssAndMockDetection(lpparam: XC_LoadPackage.LoadPackageParam, pkgName: String) {
        val gnssStatusClass = XposedHelpers.findClassIfExists(
            "android.location.GnssStatus",
            lpparam.classLoader
        )
        if (gnssStatusClass != null) {
            for (method in gnssStatusClass.declaredMethods) {
                when (method.name) {
                    "getSatelliteCount" -> {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (isSpoofingActive(pkgName) && isAdvancedHookCache) {
                                        val realCount = (param.result as? Int) ?: 0
                                        if (realCount < 8) {
                                            param.result = 12
                                        }
                                        HookLogReporter.recordLocationRequest(
                                            packageName = pkgName,
                                            action = "GnssStatus.getSatelliteCount",
                                            details = "增强卫星状态伪装 -> 卫星数量=${param.result} (真实=$realCount), 信噪比=38.0dBHz",
                                            isSpoofed = true,
                                            throttleMs = 5000L
                                        )
                                    }
                                }
                            }
                        )
                    }
                    "usedInFix" -> {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (isSpoofingActive(pkgName) && isAdvancedHookCache) {
                                        param.result = true
                                    }
                                }
                            }
                        )
                    }
                    "getCn0DbHz" -> {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (isSpoofingActive(pkgName) && isAdvancedHookCache) {
                                        param.result = 38.0f
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
