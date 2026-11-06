package io.github.jqssun.gpssetter.xposed

import android.annotation.SuppressLint
import android.location.GnssStatus
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
                                if (isSpoofingActive(pkgName)) {
                                    val loc = param.result as? Location
                                    if (loc != null) {
                                        amendLocationInPlace(loc)
                                    } else {
                                        param.result = createSpoofedLocation()
                                    }
                                }
                            }
                        }
                    )
                }
                "asList" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (isSpoofingActive(pkgName)) {
                                    @Suppress("UNCHECKED_CAST")
                                    val list = param.result as? List<Location>
                                    if (!list.isNullOrEmpty()) {
                                        list.forEach { amendLocationInPlace(it) }
                                    } else {
                                        param.result = listOf(createSpoofedLocation())
                                    }
                                }
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
                                if (isSpoofingActive(pkgName)) {
                                    val provider = (param.args.firstOrNull() as? String)
                                        ?: LocationManager.GPS_PROVIDER
                                    val origin = param.result as? Location
                                    param.result = createSpoofedLocation(origin, provider)
                                }
                            }
                        }
                    )
                }
                "getCurrentLocation" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isSpoofingActive(pkgName)) return
                                val provider = (param.args.firstOrNull() as? String)
                                    ?: LocationManager.GPS_PROVIDER
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
                                    ?: return
                                val provider = (param.args.firstOrNull { it is String } as? String)
                                    ?: LocationManager.GPS_PROVIDER

                                hookListenerClassIfNeeded(listener.javaClass, pkgName)
                                activeListeners[listener] = provider
                                ensureLiveTickerRunning()

                                if (isSpoofingActive(pkgName)) {
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
                            if (!isSpoofingActive(pkgName)) return
                            val firstArg = param.args.firstOrNull() ?: return
                            if (firstArg is Location) {
                                amendLocationInPlace(firstArg)
                            } else if (firstArg is List<*>) {
                                firstArg.filterIsInstance<Location>().forEach {
                                    amendLocationInPlace(it)
                                }
                            }
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
                                    if (isSpoofingActive(pkgName)) {
                                        val loc = param.result as? Location
                                        if (loc != null) {
                                            amendLocationInPlace(loc)
                                        } else {
                                            param.result = createSpoofedLocation()
                                        }
                                    }
                                }
                            }
                        )
                    }
                    "getLocations" -> {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (isSpoofingActive(pkgName)) {
                                        @Suppress("UNCHECKED_CAST")
                                        val list = param.result as? List<Location>
                                        if (!list.isNullOrEmpty()) {
                                            list.forEach { amendLocationInPlace(it) }
                                        } else {
                                            param.result = listOf(createSpoofedLocation())
                                        }
                                    }
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
                                    } ?: return
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
                                    }
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
                            if (!isSpoofingActive(pkgName)) return
                            val resultObj = param.args.firstOrNull() ?: return
                            runCatching {
                                @Suppress("UNCHECKED_CAST")
                                val locations = XposedHelpers.callMethod(resultObj, "getLocations") as? List<Location>
                                locations?.forEach { amendLocationInPlace(it) }
                            }
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
