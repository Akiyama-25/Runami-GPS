package io.github.jqssun.gpssetter.xposed

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.WorkSource
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

    @Volatile
    private var lastDeliveredSpoofLat: Double = Double.NaN

    @Volatile
    private var lastDeliveredSpoofLng: Double = Double.NaN

    @Volatile
    private var lastComputedMovementBearing: Float = Float.NaN

    @Volatile
    private var lastComputedMovementTime: Long = 0L

    @Volatile
    private var lastRealLocation: Location? = null

    @Volatile
    private var lastSystemCallbackTime: Long = 0L

    @Volatile
    private var lastLivePushedLat: Double = Double.NaN

    @Volatile
    private var lastLivePushedLng: Double = Double.NaN

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
    private val hookedBinderListenerClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())
    private val hookedBinderCallbackClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())
    private val hookedGnssCallbackClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    private val activeListeners = Collections.synchronizedMap(WeakHashMap<LocationListener, String>())
    private val activeBinderListeners = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val activeGmsCallbacks = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var isLiveTickerScheduled = false

    private fun formatCoord(lat: Double = newlat, lng: Double = newlng): String {
        return String.format(Locale.US, "(%.6f, %.6f) acc=%.1fm", lat, lng, accuracy)
    }

    private fun describeLocation(loc: Location?): String {
        if (loc == null) return "null"
        return runCatching {
            val prov = loc.provider ?: "unknown"
            val latStr = String.format(Locale.US, "%.6f", loc.latitude)
            val lngStr = String.format(Locale.US, "%.6f", loc.longitude)
            val accStr = if (loc.hasAccuracy()) String.format(Locale.US, "%.1fm", loc.accuracy) else "N/A"
            val altStr = if (loc.hasAltitude()) String.format(Locale.US, "%.1fm", loc.altitude) else "N/A"
            val spdStr = if (loc.hasSpeed()) String.format(Locale.US, "%.2fm/s", loc.speed) else "N/A"
            val brgStr = if (loc.hasBearing()) {
                val bAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasBearingAccuracy()) {
                    String.format(Locale.US, "±%.1f°", loc.bearingAccuracyDegrees)
                } else {
                    ""
                }
                String.format(Locale.US, "%.1f°%s", loc.bearing, bAcc)
            } else {
                "N/A(sensor)"
            }
            val sats = loc.extras?.getInt("satellites", -1)?.takeIf { it >= 0 }?.toString() ?: "N/A"
            val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                loc.isMock
            } else {
                @Suppress("DEPRECATION")
                loc.isFromMockProvider
            }
            "Location[prov=$prov, lat=$latStr, lng=$lngStr, acc=$accStr, alt=$altStr, speed=$spdStr, bearing=$brgStr, sat=$sats, mock=$isMock, time=${loc.time}]"
        }.getOrDefault("Location[unreadable]")
    }

    private fun extractWorkSourcePackages(args: Array<Any?>?): List<String> {
        if (args.isNullOrEmpty()) return emptyList()
        val result = linkedSetOf<String>()
        for (arg in args) {
            if (arg == null) continue
            runCatching {
                if (arg is WorkSource) {
                    collectFromWorkSource(arg, result)
                } else {
                    // Check if arg is a LocationRequest / LastLocationRequest / CurrentLocationRequest with getWorkSource()
                    val wsMethod = arg.javaClass.methods.firstOrNull {
                        it.name == "getWorkSource" && it.parameterCount == 0
                    }
                    val ws = wsMethod?.invoke(arg) as? WorkSource
                    if (ws != null) {
                        collectFromWorkSource(ws, result)
                    }
                    // Also check field mWorkSource or workSource
                    val wsField = arg.javaClass.declaredFields.firstOrNull {
                        WorkSource::class.java.isAssignableFrom(it.type)
                    }
                    if (wsField != null) {
                        wsField.isAccessible = true
                        val fieldWs = wsField.get(arg) as? WorkSource
                        if (fieldWs != null) {
                            collectFromWorkSource(fieldWs, result)
                        }
                    }
                }
            }
        }
        result.remove("com.google.android.gms")
        result.remove("android")
        result.remove(BuildConfig.APPLICATION_ID)
        return result.toList()
    }

    private fun collectFromWorkSource(ws: WorkSource, out: MutableSet<String>) {
        runCatching {
            val size = (XposedHelpers.callMethod(ws, "size") as? Int) ?: 0
            for (i in 0 until size) {
                val pkg = ( runCatching { XposedHelpers.callMethod(ws, "getPackageName", i) as? String }.getOrNull()
                    ?: runCatching { XposedHelpers.callMethod(ws, "getName", i) as? String }.getOrNull() )
                if (!pkg.isNullOrBlank()) {
                    out.add(pkg)
                }
            }
        }
        runCatching {
            val str = ws.toString()
            val regex = Regex("""([a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+)""")
            regex.findAll(str).forEach { m ->
                val candidate = m.value
                if (candidate.contains('.') && !candidate.startsWith("WorkSource")) {
                    out.add(candidate)
                }
            }
        }
    }

    private fun summarizeRequestArgs(args: Array<Any?>?): String {
        if (args.isNullOrEmpty()) return "none"
        val parts = mutableListOf<String>()
        for ((idx, arg) in args.withIndex()) {
            when (arg) {
                null -> parts.add("arg[$idx]=null")
                is String -> parts.add("arg[$idx]=\"$arg\"")
                is Number, is Boolean -> parts.add("arg[$idx]=$arg")
                is Location -> parts.add("arg[$idx]=${describeLocation(arg)}")
                is WorkSource -> {
                    val pkgs = linkedSetOf<String>()
                    collectFromWorkSource(arg, pkgs)
                    parts.add("arg[$idx]=WorkSource(${pkgs.joinToString(",")})")
                }
                else -> {
                    val clsName = arg.javaClass.simpleName.ifEmpty { arg.javaClass.name }
                    val str = runCatching {
                        if (clsName.contains("Request", ignoreCase = true)) {
                            arg.toString().replace('\n', ' ').take(140)
                        } else {
                            "$clsName@${Integer.toHexString(System.identityHashCode(arg))}"
                        }
                    }.getOrDefault(clsName)
                    parts.add("arg[$idx]=$str")
                }
            }
        }
        return parts.joinToString(", ")
    }

    private fun extractCallerFrames(maxFrames: Int = 4): String {
        return runCatching {
            Throwable().stackTrace
                .asSequence()
                .filter { el ->
                    val cn = el.className
                    !cn.startsWith("io.github.jqssun.gpssetter") &&
                        !cn.startsWith("de.robv.android.xposed") &&
                        !cn.startsWith("org.lsposed") &&
                        !cn.startsWith("J.") &&
                        !cn.startsWith("LSPHooker") &&
                        !cn.startsWith("java.lang.reflect") &&
                        !cn.startsWith("android.os.Binder")
                }
                .take(maxFrames)
                .joinToString(" <- ") { el ->
                    val simpleCls = el.className.substringAfterLast('.')
                    "$simpleCls.${el.methodName}(${el.fileName ?: "Unknown"}:${el.lineNumber})"
                }
                .ifEmpty { "native/direct" }
        }.getOrDefault("unknown")
    }

    private fun resolveEffectivePackage(basePkg: String, args: Array<Any?>?): String {
        val wsPkgs = extractWorkSourcePackages(args)
        return if (wsPkgs.isNotEmpty() && basePkg == "com.google.android.gms") {
            "$basePkg -> [${wsPkgs.joinToString(",")}]"
        } else if (wsPkgs.isNotEmpty() && !wsPkgs.contains(basePkg)) {
            "$basePkg (client=${wsPkgs.joinToString(",")})"
        } else {
            basePkg
        }
    }

    private fun buildDetailedLocationLog(
        layer: String,
        methodSig: String,
        active: Boolean,
        provider: String,
        args: Array<Any?>? = null,
        originLoc: Location? = null,
        resultLoc: Location? = null,
        extraNote: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("mode=").append(if (active) "SPOOFED(伪装生效)" else "PASSTHROUGH(真实透传)")
        sb.append(" | layer=").append(layer)
        sb.append(" | method=").append(methodSig)
        sb.append(" | provider=").append(provider)
        if (!extraNote.isNullOrBlank()) {
            sb.append(" | note=").append(extraNote)
        }
        if (args != null) {
            sb.append(" | args=[").append(summarizeRequestArgs(args)).append("]")
        }
        if (originLoc != null) {
            sb.append(" | origin=").append(describeLocation(originLoc))
        }
        if (resultLoc != null) {
            sb.append(" | result=").append(describeLocation(resultLoc))
        } else if (active) {
            sb.append(" | target=").append(formatCoord())
        }
        sb.append(" | caller=").append(extractCallerFrames())
        return sb.toString()
    }

    private val liveUpdateRunnable = object : Runnable {
        override fun run() {
            try {
                updateLocation(forceReload = true)
                if (isStartedCache) {
                    val now = System.currentTimeMillis()
                    val coordMoved = lastLivePushedLat.isNaN() ||
                        kotlin.math.abs(newlat - lastLivePushedLat) > 1e-7 ||
                        kotlin.math.abs(newlng - lastLivePushedLng) > 1e-7
                    val systemCallbackStale = (now - lastSystemCallbackTime) > 2500L
                    // Only push synthetic ticker updates when coordinates actually moved or system GPS callbacks are idle.
                    // This prevents clobbering real compass/bearing updates delivered by the system.
                    if (coordMoved || systemCallbackStale) {
                        lastLivePushedLat = newlat
                        lastLivePushedLng = newlng
                        pushLiveLocationToActiveListeners()
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("GPS Setter live ticker error: $t")
            } finally {
                val hasActive = synchronized(activeListeners) { activeListeners.isNotEmpty() } ||
                    synchronized(activeBinderListeners) { activeBinderListeners.isNotEmpty() } ||
                    synchronized(activeGmsCallbacks) { activeGmsCallbacks.isNotEmpty() }
                if (hasActive) {
                    mainHandler.postDelayed(this, 1200L)
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
                mainHandler.postDelayed(liveUpdateRunnable, 400L)
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
                val loc = createSpoofedLocation(lastRealLocation, provider)
                listener.onLocationChanged(loc)
                if (currentHookedPkg.isNotEmpty()) {
                    HookLogReporter.recordLocationRequest(
                        packageName = currentHookedPkg,
                        action = "LocationListener.onLocationChanged (实时推送)",
                        details = buildDetailedLocationLog(
                            layer = "SDK/Ticker",
                            methodSig = "${listener.javaClass.name}.onLocationChanged(Location)",
                            active = true,
                            provider = provider,
                            originLoc = lastRealLocation,
                            resultLoc = loc,
                            extraNote = "实时位置同步推送"
                        ),
                        isSpoofed = true,
                        throttleMs = 3500L
                    )
                }
            }
        }

        val binderSnapshot = synchronized(activeBinderListeners) {
            activeBinderListeners.entries.mapNotNull { entry ->
                entry.key?.let { it to (entry.value ?: LocationManager.GPS_PROVIDER) }
            }
        }
        for ((binderListener, provider) in binderSnapshot) {
            runCatching {
                val loc = createSpoofedLocation(lastRealLocation, provider)
                val methods = binderListener.javaClass.declaredMethods.filter { it.name == "onLocationChanged" }
                for (m in methods) {
                    m.isAccessible = true
                    val paramTypes = m.parameterTypes
                    if (paramTypes.size == 2 && List::class.java.isAssignableFrom(paramTypes[0])) {
                        m.invoke(binderListener, listOf(loc), null)
                        break
                    } else if (paramTypes.size == 1 && Location::class.java.isAssignableFrom(paramTypes[0])) {
                        m.invoke(binderListener, loc)
                        break
                    } else if (paramTypes.size == 1 && List::class.java.isAssignableFrom(paramTypes[0])) {
                        m.invoke(binderListener, listOf(loc))
                        break
                    }
                }
                if (currentHookedPkg.isNotEmpty()) {
                    HookLogReporter.recordLocationRequest(
                        packageName = currentHookedPkg,
                        action = "[Binder] ILocationListener.onLocationChanged (实时推送)",
                        details = buildDetailedLocationLog(
                            layer = "Binder/Ticker",
                            methodSig = "${binderListener.javaClass.name}.onLocationChanged",
                            active = true,
                            provider = provider,
                            originLoc = lastRealLocation,
                            resultLoc = loc,
                            extraNote = "底层AIDL实时位置同步推送"
                        ),
                        isSpoofed = true,
                        throttleMs = 3500L
                    )
                }
            }
        }

        val gmsSnapshot = synchronized(activeGmsCallbacks) {
            activeGmsCallbacks.keys.filterNotNull()
        }
        for (callback in gmsSnapshot) {
            runCatching {
                val loc = createSpoofedLocation(lastRealLocation, LocationManager.GPS_PROVIDER)
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
                            details = buildDetailedLocationLog(
                                layer = "GMS/Ticker",
                                methodSig = "${callback.javaClass.name}.onLocationResult(LocationResult)",
                                active = true,
                                provider = "fused",
                                originLoc = lastRealLocation,
                                resultLoc = loc,
                                extraNote = "GMS Fused 实时位置同步推送"
                            ),
                            isSpoofed = true,
                            throttleMs = 3500L
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

            val prevLat = newlat
            val prevLng = newlng

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

            // Compute movement bearing ONLY if the user moved the target pin significantly (> 1.5m)
            if (!lastDeliveredSpoofLat.isNaN() && !lastDeliveredSpoofLng.isNaN()) {
                val results = FloatArray(2)
                Location.distanceBetween(lastDeliveredSpoofLat, lastDeliveredSpoofLng, newlat, newlng, results)
                val distMeters = results[0]
                if (distMeters >= 1.5f) {
                    val initialBearing = (results[1] + 360f) % 360f
                    lastComputedMovementBearing = initialBearing
                    lastComputedMovementTime = now
                    lastDeliveredSpoofLat = newlat
                    lastDeliveredSpoofLng = newlng
                }
            } else {
                lastDeliveredSpoofLat = newlat
                lastDeliveredSpoofLng = newlng
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

    private fun cacheRealLocationIfValid(loc: Location?) {
        if (loc == null) return
        runCatching {
            if (kotlin.math.abs(loc.latitude - newlat) > 1e-5 || kotlin.math.abs(loc.longitude - newlng) > 1e-5) {
                lastRealLocation = Location(loc)
            }
        }
    }

    private fun clearMockFlag(location: Location) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    HiddenApiBypass.invoke(
                        Location::class.java,
                        location,
                        "setIsFromMockProvider",
                        false
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        HiddenApiBypass.invoke(
                            Location::class.java,
                            location,
                            "setMock",
                            false
                        )
                    }
                }
            } else {
                XposedHelpers.callMethod(location, "setIsFromMockProvider", false)
            }
        } catch (e: Throwable) {
            runCatching {
                XposedHelpers.callMethod(location, "setIsFromMockProvider", false)
            }
        }
    }

    /**
     * Creates a spoofed Location while strictly preserving real sensor/GPS bearing, speed, altitude,
     * and extras from `origin` (or `lastRealLocation`).
     * CRITICAL: Never force `bearing = 0f` or `bearingAccuracyDegrees = 1.0f` when stationary,
     * because `hasBearing() == true` with 0.0° overrides the device compass (`TYPE_ROTATION_VECTOR`) in map apps!
     */
    @SuppressLint("NewApi")
    private fun createSpoofedLocation(
        origin: Location? = null,
        provider: String = LocationManager.GPS_PROVIDER
    ): Location {
        cacheRealLocationIfValid(origin)
        val source = origin ?: lastRealLocation
        val safeProvider = source?.provider?.takeIf { it.isNotEmpty() }
            ?: provider.ifEmpty { LocationManager.GPS_PROVIDER }

        // Copy from source if available so all internal fields (bearing, speed, altitude, mslAltitude, extras) are preserved
        val location = if (source != null) {
            Location(source).apply { this.provider = safeProvider }
        } else {
            Location(safeProvider)
        }

        val nowMs = System.currentTimeMillis()
        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()

        location.latitude = newlat
        location.longitude = newlng
        location.accuracy = if (accuracy > 0f) accuracy else 10.0f
        if (!location.hasAltitude()) {
            location.altitude = 30.0
        }
        location.time = nowMs
        location.elapsedRealtimeNanos = nowElapsedNanos

        // If user recently moved the spoofed coordinates (< 3s ago) and the original Location had no bearing,
        // provide the movement bearing; otherwise preserve whatever bearing state `source` had (or none).
        if (!location.hasBearing() && !lastComputedMovementBearing.isNaN() && (nowMs - lastComputedMovementTime) < 3000L) {
            location.bearing = lastComputedMovementBearing
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!location.hasVerticalAccuracy()) {
                location.verticalAccuracyMeters = location.accuracy
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!location.hasElapsedRealtimeUncertaintyNanos()) {
                location.elapsedRealtimeUncertaintyNanos = 1000.0
            }
        }

        val extras = location.extras?.let { Bundle(it) } ?: Bundle()
        if (!extras.containsKey("satellites")) {
            extras.putInt("satellites", 12)
        }
        location.extras = extras

        clearMockFlag(location)
        return location
    }

    /**
     * Modifies an existing Location in-place, preserving its native `bearing`, `hasBearing()`,
     * `bearingAccuracyDegrees`, `speed`, `hasSpeed()`, `altitude`, and `extras`.
     */
    @SuppressLint("NewApi")
    private fun amendLocationInPlace(location: Location) {
        cacheRealLocationIfValid(location)
        val nowMs = System.currentTimeMillis()

        location.latitude = newlat
        location.longitude = newlng
        location.accuracy = if (accuracy > 0f) accuracy else 10.0f
        if (!location.hasAltitude()) {
            location.altitude = 30.0
        }
        location.time = nowMs
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

        if (!location.hasBearing() && !lastComputedMovementBearing.isNaN() && (nowMs - lastComputedMovementTime) < 3000L) {
            location.bearing = lastComputedMovementBearing
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!location.hasVerticalAccuracy()) {
                location.verticalAccuracyMeters = location.accuracy
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!location.hasElapsedRealtimeUncertaintyNanos()) {
                location.elapsedRealtimeUncertaintyNanos = 1000.0
            }
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
        // Never touch system_server so no device reboot is ever required and unchecked apps stay 100% isolated.
        if (ignorePkg.contains(pkgName)) {
            return
        }

        currentHookedPkg = pkgName
        hookApplicationLifecycleForContext(lpparam)

        val active = isSpoofingActive(pkgName)
        HookLogReporter.recordLocationRequest(
            packageName = pkgName,
            action = "HookEntry.initHooks (进程内双层定位钩子初始化)",
            details = buildDetailedLocationLog(
                layer = "Init/Xposed",
                methodSig = "LocationHook.initHooks(LoadPackageParam)",
                active = active,
                provider = "all",
                extraNote = "process=${lpparam.processName}, advancedHook=$isAdvancedHookCache"
            ),
            isSpoofed = active,
            throttleMs = 0L
        )

        // Layer 1: Deep In-Process Binder Proxy & AIDL Transport Stubs (Replaces System Framework hook without reboot!)
        hookBinderLocationManagerProxy(lpparam, pkgName)
        hookFrameworkLocationTransports(lpparam, pkgName)

        // Layer 2: SDK API & GMS Fused Location Hooks
        hookLocationClass(lpparam, pkgName)
        hookLocationResultClass(lpparam, pkgName)
        hookLocationManagerClass(lpparam, pkgName)
        hookGmsLocationClasses(lpparam, pkgName)
        hookGnssAndMockDetection(lpparam, pkgName)
    }

    /**
     * Captures Application / Context as soon as the target process initializes it so early logs
     * (including `initHooks` and startup location requests) are immediately flushed to `LogContentProvider`.
     */
    private fun hookApplicationLifecycleForContext(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val app = param.args.firstOrNull() as? Application
                        HookLogReporter.onContextAvailable(app)
                    }
                }
            )
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = (param.thisObject as? Context) ?: (param.args.firstOrNull() as? Context)
                        HookLogReporter.onContextAvailable(ctx)
                    }
                }
            )
        }
    }

    /**
     * Layer 1A: Intercepts android.location.ILocationManager$Stub$Proxy directly inside the target app process.
     * Every Binder IPC call from the target app to system_server's LocationManagerService goes through this proxy.
     */
    private fun hookBinderLocationManagerProxy(lpparam: XC_LoadPackage.LoadPackageParam, pkgName: String) {
        val proxyClass = XposedHelpers.findClassIfExists(
            "android.location.ILocationManager\$Stub\$Proxy",
            lpparam.classLoader
        ) ?: return

        val iLocationListenerClass = XposedHelpers.findClassIfExists(
            "android.location.ILocationListener",
            lpparam.classLoader
        )
        val iLocationCallbackClass = XposedHelpers.findClassIfExists(
            "android.location.ILocationCallback",
            lpparam.classLoader
        )
        val iGnssStatusListenerClass = XposedHelpers.findClassIfExists(
            "android.location.IGnssStatusListener",
            lpparam.classLoader
        )

        for (method in proxyClass.declaredMethods) {
            when (method.name) {
                "getLastLocation", "getLastKnownLocation" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val active = isSpoofingActive(pkgName)
                                val provider = (param.args.firstOrNull { it is String } as? String)
                                    ?: LocationManager.GPS_PROVIDER
                                val origin = (param.result as? Location)?.let { Location(it) }
                                cacheRealLocationIfValid(origin)
                                if (active) {
                                    param.result = createSpoofedLocation(origin, provider)
                                }
                                val resultLoc = param.result as? Location
                                val effectivePkg = resolveEffectivePackage(pkgName, param.args)
                                HookLogReporter.recordLocationRequest(
                                    packageName = effectivePkg,
                                    action = "[Binder] ILocationManager.${method.name}",
                                    details = buildDetailedLocationLog(
                                        layer = "Binder/IPC",
                                        methodSig = "ILocationManager\$Stub\$Proxy.${method.name}",
                                        active = active,
                                        provider = provider,
                                        args = param.args,
                                        originLoc = origin,
                                        resultLoc = resultLoc
                                    ),
                                    isSpoofed = active,
                                    throttleMs = 600L
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
                                val provider = (param.args.firstOrNull { it is String } as? String)
                                    ?: LocationManager.GPS_PROVIDER
                                val callbackObj = if (iLocationCallbackClass != null) {
                                    param.args.firstOrNull {
                                        it != null && iLocationCallbackClass.isAssignableFrom(it.javaClass)
                                    }
                                } else {
                                    null
                                }
                                if (callbackObj != null) {
                                    hookBinderLocationCallbackIfNeeded(callbackObj.javaClass, pkgName)
                                }

                                val active = isSpoofingActive(pkgName)
                                val spoofed = if (active) createSpoofedLocation(lastRealLocation, provider) else null
                                val effectivePkg = resolveEffectivePackage(pkgName, param.args)
                                HookLogReporter.recordLocationRequest(
                                    packageName = effectivePkg,
                                    action = "[Binder] ILocationManager.getCurrentLocation",
                                    details = buildDetailedLocationLog(
                                        layer = "Binder/IPC",
                                        methodSig = "ILocationManager\$Stub\$Proxy.getCurrentLocation",
                                        active = active,
                                        provider = provider,
                                        args = param.args,
                                        originLoc = lastRealLocation,
                                        resultLoc = spoofed
                                    ),
                                    isSpoofed = active,
                                    throttleMs = 400L
                                )

                                if (active && callbackObj != null && spoofed != null) {
                                    mainHandler.post {
                                        runCatching {
                                            XposedHelpers.callMethod(callbackObj, "onLocation", spoofed)
                                        }
                                    }
                                    param.result = null
                                }
                            }
                        }
                    )
                }
                "registerLocationListener", "requestLocationUpdates" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val provider = (param.args.firstOrNull { it is String } as? String)
                                    ?: LocationManager.GPS_PROVIDER
                                val binderListener = if (iLocationListenerClass != null) {
                                    param.args.firstOrNull {
                                        it != null && iLocationListenerClass.isAssignableFrom(it.javaClass)
                                    }
                                } else {
                                    null
                                }

                                val active = isSpoofingActive(pkgName)
                                val effectivePkg = resolveEffectivePackage(pkgName, param.args)
                                HookLogReporter.recordLocationRequest(
                                    packageName = effectivePkg,
                                    action = "[Binder] ILocationManager.${method.name}",
                                    details = buildDetailedLocationLog(
                                        layer = "Binder/IPC",
                                        methodSig = "ILocationManager\$Stub\$Proxy.${method.name}",
                                        active = active,
                                        provider = provider,
                                        args = param.args,
                                        originLoc = lastRealLocation,
                                        extraNote = "transport=${binderListener?.javaClass?.name ?: "PendingIntent"}"
                                    ),
                                    isSpoofed = active,
                                    throttleMs = 400L
                                )

                                if (binderListener != null) {
                                    hookBinderLocationListenerIfNeeded(binderListener.javaClass, pkgName)
                                    activeBinderListeners[binderListener] = provider
                                    ensureLiveTickerRunning()
                                }
                            }
                        }
                    )
                }
                "unregisterLocationListener", "removeUpdates" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val binderListener = if (iLocationListenerClass != null) {
                                    param.args.firstOrNull {
                                        it != null && iLocationListenerClass.isAssignableFrom(it.javaClass)
                                    }
                                } else {
                                    null
                                }
                                if (binderListener != null) {
                                    activeBinderListeners.remove(binderListener)
                                }
                            }
                        }
                    )
                }
                "isProviderEnabled", "isProviderEnabledForUser" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isSpoofingActive(pkgName) || !isAdvancedHookCache) return
                                val provider = param.args.firstOrNull() as? String ?: return
                                if (provider == LocationManager.GPS_PROVIDER ||
                                    provider == LocationManager.NETWORK_PROVIDER ||
                                    provider == "fused"
                                ) {
                                    param.result = true
                                }
                            }
                        }
                    )
                }
                "getProviders", "getAllProviders" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!isSpoofingActive(pkgName) || !isAdvancedHookCache) return
                                @Suppress("UNCHECKED_CAST")
                                val orig = (param.result as? List<String>)?.toMutableList() ?: mutableListOf()
                                if (!orig.contains(LocationManager.GPS_PROVIDER)) {
                                    orig.add(LocationManager.GPS_PROVIDER)
                                }
                                if (!orig.contains(LocationManager.NETWORK_PROVIDER)) {
                                    orig.add(LocationManager.NETWORK_PROVIDER)
                                }
                                param.result = orig
                            }
                        }
                    )
                }
                "registerGnssStatusCallback" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (iGnssStatusListenerClass == null) return
                                val gnssCallback = param.args.firstOrNull {
                                    it != null && iGnssStatusListenerClass.isAssignableFrom(it.javaClass)
                                } ?: return
                                hookGnssStatusListenerIfNeeded(gnssCallback.javaClass, pkgName)
                                val active = isSpoofingActive(pkgName)
                                val spoofed = active && isAdvancedHookCache
                                HookLogReporter.recordLocationRequest(
                                    packageName = resolveEffectivePackage(pkgName, param.args),
                                    action = "[Binder] ILocationManager.registerGnssStatusCallback",
                                    details = buildDetailedLocationLog(
                                        layer = "Binder/GNSS",
                                        methodSig = "ILocationManager\$Stub\$Proxy.registerGnssStatusCallback",
                                        active = spoofed,
                                        provider = LocationManager.GPS_PROVIDER,
                                        args = param.args,
                                        extraNote = "listener=${gnssCallback.javaClass.name}"
                                    ),
                                    isSpoofed = spoofed,
                                    throttleMs = 800L
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * Layer 1B: Proactively hooks Android framework's internal AIDL Stub transport classes inside the target process.
     */
    private fun hookFrameworkLocationTransports(lpparam: XC_LoadPackage.LoadPackageParam, pkgName: String) {
        val transportClassNames = listOf(
            "android.location.LocationManager\$LocationListenerTransport",
            "android.location.LocationManager\$ListenerTransport"
        )
        for (name in transportClassNames) {
            val clazz = XposedHelpers.findClassIfExists(name, lpparam.classLoader)
            if (clazz != null) {
                hookBinderLocationListenerIfNeeded(clazz, pkgName)
            }
        }

        val currentLocTransportNames = listOf(
            "android.location.LocationManager\$GetCurrentLocationTransport"
        )
        for (name in currentLocTransportNames) {
            val clazz = XposedHelpers.findClassIfExists(name, lpparam.classLoader)
            if (clazz != null) {
                hookBinderLocationCallbackIfNeeded(clazz, pkgName)
            }
        }

        val gnssTransportNames = listOf(
            "android.location.LocationManager\$GnssStatusTransport"
        )
        for (name in gnssTransportNames) {
            val clazz = XposedHelpers.findClassIfExists(name, lpparam.classLoader)
            if (clazz != null) {
                hookGnssStatusListenerIfNeeded(clazz, pkgName)
            }
        }
    }

    private fun hookBinderLocationListenerIfNeeded(clazz: Class<*>, pkgName: String) {
        if (!hookedBinderListenerClasses.add(clazz)) return

        for (method in clazz.declaredMethods) {
            if (method.name == "onLocationChanged") {
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            lastSystemCallbackTime = System.currentTimeMillis()
                            val active = isSpoofingActive(pkgName)
                            val firstArg = param.args.firstOrNull()
                            var origSnapshot: Location? = null
                            var resSnapshot: Location? = null

                            if (firstArg is Location) {
                                origSnapshot = Location(firstArg)
                                cacheRealLocationIfValid(origSnapshot)
                                if (active) {
                                    amendLocationInPlace(firstArg)
                                }
                                resSnapshot = firstArg
                            } else if (firstArg is List<*>) {
                                val locs = firstArg.filterIsInstance<Location>()
                                origSnapshot = locs.firstOrNull()?.let { Location(it) }
                                cacheRealLocationIfValid(origSnapshot)
                                if (active) {
                                    if (locs.isNotEmpty()) {
                                        locs.forEach { amendLocationInPlace(it) }
                                        resSnapshot = locs.firstOrNull()
                                    } else {
                                        val created = createSpoofedLocation(lastRealLocation)
                                        param.args[0] = listOf(created)
                                        resSnapshot = created
                                    }
                                } else {
                                    resSnapshot = origSnapshot
                                }
                            }

                            HookLogReporter.recordLocationRequest(
                                packageName = pkgName,
                                action = "[Binder] ILocationListener.Stub.onLocationChanged",
                                details = buildDetailedLocationLog(
                                    layer = "Binder/Stub",
                                    methodSig = "${clazz.name}.onLocationChanged",
                                    active = active,
                                    provider = origSnapshot?.provider ?: resSnapshot?.provider ?: "gps",
                                    originLoc = origSnapshot,
                                    resultLoc = resSnapshot
                                ),
                                isSpoofed = active,
                                throttleMs = 1500L
                            )
                        }
                    }
                )
            }
        }
    }

    private fun hookBinderLocationCallbackIfNeeded(clazz: Class<*>, pkgName: String) {
        if (!hookedBinderCallbackClasses.add(clazz)) return

        for (method in clazz.declaredMethods) {
            if (method.name == "onLocation") {
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            lastSystemCallbackTime = System.currentTimeMillis()
                            val active = isSpoofingActive(pkgName)
                            val loc = param.args.firstOrNull() as? Location
                            val origSnapshot = loc?.let { Location(it) }
                            cacheRealLocationIfValid(origSnapshot)
                            var resSnapshot: Location? = loc

                            if (active) {
                                if (loc != null) {
                                    amendLocationInPlace(loc)
                                    resSnapshot = loc
                                } else if (param.args.isNotEmpty()) {
                                    val created = createSpoofedLocation(lastRealLocation)
                                    param.args[0] = created
                                    resSnapshot = created
                                }
                            }

                            HookLogReporter.recordLocationRequest(
                                packageName = pkgName,
                                action = "[Binder] ILocationCallback.Stub.onLocation",
                                details = buildDetailedLocationLog(
                                    layer = "Binder/Stub",
                                    methodSig = "${clazz.name}.onLocation(Location)",
                                    active = active,
                                    provider = origSnapshot?.provider ?: resSnapshot?.provider ?: "gps",
                                    originLoc = origSnapshot,
                                    resultLoc = resSnapshot
                                ),
                                isSpoofed = active,
                                throttleMs = 800L
                            )
                        }
                    }
                )
            }
        }
    }

    private fun hookGnssStatusListenerIfNeeded(clazz: Class<*>, pkgName: String) {
        if (!hookedGnssCallbackClasses.add(clazz)) return

        for (method in clazz.declaredMethods) {
            if (method.name == "onSvStatusChanged") {
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val active = isSpoofingActive(pkgName) && isAdvancedHookCache
                            HookLogReporter.recordLocationRequest(
                                packageName = pkgName,
                                action = "[Binder] IGnssStatusListener.Stub.onSvStatusChanged",
                                details = buildDetailedLocationLog(
                                    layer = "Binder/GNSS",
                                    methodSig = "${clazz.name}.onSvStatusChanged",
                                    active = active,
                                    provider = LocationManager.GPS_PROVIDER,
                                    args = param.args
                                ),
                                isSpoofed = active,
                                throttleMs = 4000L
                            )
                        }
                    }
                )
            }
        }
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
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val self = param.thisObject as? Location
                                val active = isSpoofingActive(pkgName)
                                val realLat = param.result as? Double
                                if (active) {
                                    param.result = newlat
                                }
                                HookLogReporter.recordLocationRequest(
                                    packageName = pkgName,
                                    action = "Location.getLatitude",
                                    details = buildDetailedLocationLog(
                                        layer = "SDK/Location",
                                        methodSig = "android.location.Location.getLatitude()",
                                        active = active,
                                        provider = self?.provider ?: "unknown",
                                        extraNote = "origLat=${realLat ?: "N/A"}, returnedLat=${param.result}, hasBearing=${self?.hasBearing() ?: false}, bearing=${if (self?.hasBearing() == true) self.bearing else "N/A(sensor)"}"
                                    ),
                                    isSpoofed = active,
                                    throttleMs = 3500L
                                )
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
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val active = isSpoofingActive(pkgName)
                                val origMock = param.result as? Boolean ?: false
                                if (active) {
                                    param.result = false
                                }
                                HookLogReporter.recordLocationRequest(
                                    packageName = pkgName,
                                    action = "Location.${method.name}",
                                    details = buildDetailedLocationLog(
                                        layer = "SDK/AntiMock",
                                        methodSig = "android.location.Location.${method.name}()",
                                        active = active,
                                        provider = (param.thisObject as? Location)?.provider ?: "unknown",
                                        extraNote = "origMock=$origMock -> returned=${param.result}"
                                    ),
                                    isSpoofed = active,
                                    throttleMs = 4000L
                                )
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
                                val loc = param.result as? Location
                                val origSnapshot = loc?.let { Location(it) }
                                cacheRealLocationIfValid(origSnapshot)
                                if (active) {
                                    if (loc != null) {
                                        amendLocationInPlace(loc)
                                    } else {
                                        param.result = createSpoofedLocation(lastRealLocation)
                                    }
                                }
                                HookLogReporter.recordLocationRequest(
                                    packageName = pkgName,
                                    action = "LocationResult.${method.name}",
                                    details = buildDetailedLocationLog(
                                        layer = "SDK/LocationResult",
                                        methodSig = "android.location.LocationResult.${method.name}",
                                        active = active,
                                        provider = origSnapshot?.provider ?: "gps",
                                        originLoc = origSnapshot,
                                        resultLoc = param.result as? Location
                                    ),
                                    isSpoofed = active,
                                    throttleMs = 1500L
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
                                @Suppress("UNCHECKED_CAST")
                                val list = param.result as? List<Location>
                                val origSnapshot = list?.firstOrNull()?.let { Location(it) }
                                cacheRealLocationIfValid(origSnapshot)
                                if (active) {
                                    if (!list.isNullOrEmpty()) {
                                        list.forEach { amendLocationInPlace(it) }
                                    } else {
                                        param.result = listOf(createSpoofedLocation(lastRealLocation))
                                    }
                                }
                                val resLoc = (param.result as? List<*>)?.firstOrNull() as? Location
                                HookLogReporter.recordLocationRequest(
                                    packageName = pkgName,
                                    action = "LocationResult.asList",
                                    details = buildDetailedLocationLog(
                                        layer = "SDK/LocationResult",
                                        methodSig = "android.location.LocationResult.asList()",
                                        active = active,
                                        provider = origSnapshot?.provider ?: "gps",
                                        originLoc = origSnapshot,
                                        resultLoc = resLoc,
                                        extraNote = "listSize=${list?.size ?: 0}"
                                    ),
                                    isSpoofed = active,
                                    throttleMs = 1500L
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    private fun captureContextFromLocationManager(lmObj: Any?) {
        if (lmObj == null) return
        runCatching {
            val ctx = XposedHelpers.getObjectField(lmObj, "mContext") as? Context
            HookLogReporter.onContextAvailable(ctx)
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
                                captureContextFromLocationManager(param.thisObject)
                                val provider = (param.args.firstOrNull() as? String)
                                    ?: LocationManager.GPS_PROVIDER
                                val active = isSpoofingActive(pkgName)
                                val origin = (param.result as? Location)?.let { Location(it) }
                                cacheRealLocationIfValid(origin)
                                if (active) {
                                    param.result = createSpoofedLocation(origin, provider)
                                }
                                HookLogReporter.recordLocationRequest(
                                    packageName = resolveEffectivePackage(pkgName, param.args),
                                    action = "LocationManager.getLastKnownLocation",
                                    details = buildDetailedLocationLog(
                                        layer = "SDK/LocationManager",
                                        methodSig = "LocationManager.getLastKnownLocation",
                                        active = active,
                                        provider = provider,
                                        args = param.args,
                                        originLoc = origin,
                                        resultLoc = param.result as? Location
                                    ),
                                    isSpoofed = active,
                                    throttleMs = 400L
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
                                captureContextFromLocationManager(param.thisObject)
                                val provider = (param.args.firstOrNull() as? String)
                                    ?: LocationManager.GPS_PROVIDER
                                val active = isSpoofingActive(pkgName)
                                val spoofed = if (active) createSpoofedLocation(lastRealLocation, provider) else null
                                HookLogReporter.recordLocationRequest(
                                    packageName = resolveEffectivePackage(pkgName, param.args),
                                    action = "LocationManager.getCurrentLocation",
                                    details = buildDetailedLocationLog(
                                        layer = "SDK/LocationManager",
                                        methodSig = "LocationManager.getCurrentLocation",
                                        active = active,
                                        provider = provider,
                                        args = param.args,
                                        originLoc = lastRealLocation,
                                        resultLoc = spoofed
                                    ),
                                    isSpoofed = active,
                                    throttleMs = 300L
                                )
                                if (!active) return
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && spoofed != null) {
                                    val consumer = param.args.firstOrNull { it is Consumer<*> }
                                    if (consumer != null) {
                                        @Suppress("UNCHECKED_CAST")
                                        val locConsumer = consumer as Consumer<Location>
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
                                captureContextFromLocationManager(param.thisObject)
                                val listener = param.args.firstOrNull { it is LocationListener } as? LocationListener
                                val provider = (param.args.firstOrNull { it is String } as? String)
                                    ?: LocationManager.GPS_PROVIDER
                                val active = isSpoofingActive(pkgName)

                                HookLogReporter.recordLocationRequest(
                                    packageName = resolveEffectivePackage(pkgName, param.args),
                                    action = "LocationManager.${method.name}",
                                    details = buildDetailedLocationLog(
                                        layer = "SDK/LocationManager",
                                        methodSig = "LocationManager.${method.name}",
                                        active = active,
                                        provider = provider,
                                        args = param.args,
                                        originLoc = lastRealLocation,
                                        extraNote = "listener=${listener?.javaClass?.name ?: "PendingIntent/Consumer"}"
                                    ),
                                    isSpoofed = active,
                                    throttleMs = 300L
                                )

                                if (listener == null) return
                                hookListenerClassIfNeeded(listener.javaClass, pkgName)
                                activeListeners[listener] = provider
                                ensureLiveTickerRunning()

                                if (active) {
                                    val initialLoc = createSpoofedLocation(lastRealLocation, provider)
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
                                captureContextFromLocationManager(param.thisObject)
                                val listener = param.args.firstOrNull { it is LocationListener } as? LocationListener
                                if (listener != null) {
                                    activeListeners.remove(listener)
                                    val active = isSpoofingActive(pkgName)
                                    HookLogReporter.recordLocationRequest(
                                        packageName = pkgName,
                                        action = "LocationManager.removeUpdates",
                                        details = buildDetailedLocationLog(
                                            layer = "SDK/LocationManager",
                                            methodSig = "LocationManager.removeUpdates",
                                            active = active,
                                            provider = "all",
                                            args = param.args,
                                            extraNote = "removedListener=${listener.javaClass.name}"
                                        ),
                                        isSpoofed = active,
                                        throttleMs = 300L
                                    )
                                }
                            }
                        }
                    )
                }
                "isProviderEnabled", "isProviderEnabledForUser" -> {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!isSpoofingActive(pkgName) || !isAdvancedHookCache) return
                                val provider = param.args.firstOrNull() as? String ?: return
                                if (provider == LocationManager.GPS_PROVIDER ||
                                    provider == LocationManager.NETWORK_PROVIDER ||
                                    provider == "fused"
                                ) {
                                    param.result = true
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
                            lastSystemCallbackTime = System.currentTimeMillis()
                            val active = isSpoofingActive(pkgName)
                            val firstArg = param.args.firstOrNull() ?: return
                            var origSnapshot: Location? = null
                            var resSnapshot: Location? = null

                            if (firstArg is Location) {
                                origSnapshot = Location(firstArg)
                                cacheRealLocationIfValid(origSnapshot)
                                if (active) {
                                    amendLocationInPlace(firstArg)
                                }
                                resSnapshot = firstArg
                            } else if (firstArg is List<*>) {
                                val locs = firstArg.filterIsInstance<Location>()
                                origSnapshot = locs.firstOrNull()?.let { Location(it) }
                                cacheRealLocationIfValid(origSnapshot)
                                if (active) {
                                    locs.forEach { amendLocationInPlace(it) }
                                }
                                resSnapshot = locs.firstOrNull()
                            }

                            HookLogReporter.recordLocationRequest(
                                packageName = pkgName,
                                action = "LocationListener.onLocationChanged",
                                details = buildDetailedLocationLog(
                                    layer = "SDK/Callback",
                                    methodSig = "${listenerClass.name}.onLocationChanged",
                                    active = active,
                                    provider = origSnapshot?.provider ?: resSnapshot?.provider ?: "gps",
                                    originLoc = origSnapshot,
                                    resultLoc = resSnapshot
                                ),
                                isSpoofed = active,
                                throttleMs = 1500L
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
                                    val loc = param.result as? Location
                                    val origSnapshot = loc?.let { Location(it) }
                                    cacheRealLocationIfValid(origSnapshot)
                                    if (active) {
                                        if (loc != null) {
                                            amendLocationInPlace(loc)
                                        } else {
                                            param.result = createSpoofedLocation(lastRealLocation)
                                        }
                                    }
                                    HookLogReporter.recordLocationRequest(
                                        packageName = pkgName,
                                        action = "GmsLocationResult.getLastLocation",
                                        details = buildDetailedLocationLog(
                                            layer = "GMS/LocationResult",
                                            methodSig = "com.google.android.gms.location.LocationResult.getLastLocation()",
                                            active = active,
                                            provider = origSnapshot?.provider ?: "fused",
                                            originLoc = origSnapshot,
                                            resultLoc = param.result as? Location
                                        ),
                                        isSpoofed = active,
                                        throttleMs = 1500L
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
                                    @Suppress("UNCHECKED_CAST")
                                    val list = param.result as? List<Location>
                                    val origSnapshot = list?.firstOrNull()?.let { Location(it) }
                                    cacheRealLocationIfValid(origSnapshot)
                                    if (active) {
                                        if (!list.isNullOrEmpty()) {
                                            list.forEach { amendLocationInPlace(it) }
                                        } else {
                                            param.result = listOf(createSpoofedLocation(lastRealLocation))
                                        }
                                    }
                                    val resLoc = (param.result as? List<*>)?.firstOrNull() as? Location
                                    HookLogReporter.recordLocationRequest(
                                        packageName = pkgName,
                                        action = "GmsLocationResult.getLocations",
                                        details = buildDetailedLocationLog(
                                            layer = "GMS/LocationResult",
                                            methodSig = "com.google.android.gms.location.LocationResult.getLocations()",
                                            active = active,
                                            provider = origSnapshot?.provider ?: "fused",
                                            originLoc = origSnapshot,
                                            resultLoc = resLoc,
                                            extraNote = "count=${list?.size ?: 0}"
                                        ),
                                        isSpoofed = active,
                                        throttleMs = 1500L
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
                                        packageName = resolveEffectivePackage(pkgName, param.args),
                                        action = "FusedLocationProviderClient.requestLocationUpdates",
                                        details = buildDetailedLocationLog(
                                            layer = "GMS/FusedClient",
                                            methodSig = "FusedLocationProviderClient.requestLocationUpdates",
                                            active = active,
                                            provider = "fused",
                                            args = param.args,
                                            originLoc = lastRealLocation,
                                            extraNote = "callback=${callback?.javaClass?.name ?: "PendingIntent"}"
                                        ),
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
                                        val active = isSpoofingActive(pkgName)
                                        HookLogReporter.recordLocationRequest(
                                            packageName = pkgName,
                                            action = "FusedLocationProviderClient.removeLocationUpdates",
                                            details = buildDetailedLocationLog(
                                                layer = "GMS/FusedClient",
                                                methodSig = "FusedLocationProviderClient.removeLocationUpdates",
                                                active = active,
                                                provider = "fused",
                                                args = param.args,
                                                extraNote = "removedCallback=${callback.javaClass.name}"
                                            ),
                                            isSpoofed = active,
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
                                        packageName = resolveEffectivePackage(pkgName, param.args),
                                        action = "FusedLocationProviderClient.${method.name}",
                                        details = buildDetailedLocationLog(
                                            layer = "GMS/FusedClient",
                                            methodSig = "FusedLocationProviderClient.${method.name}",
                                            active = active,
                                            provider = "fused",
                                            args = param.args,
                                            originLoc = lastRealLocation
                                        ),
                                        isSpoofed = active,
                                        throttleMs = 400L
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
                            lastSystemCallbackTime = System.currentTimeMillis()
                            val active = isSpoofingActive(pkgName)
                            val resultObj = param.args.firstOrNull() ?: return
                            var origSnapshot: Location? = null
                            var resSnapshot: Location? = null

                            runCatching {
                                @Suppress("UNCHECKED_CAST")
                                val locations = XposedHelpers.callMethod(resultObj, "getLocations") as? List<Location>
                                origSnapshot = locations?.firstOrNull()?.let { Location(it) }
                                cacheRealLocationIfValid(origSnapshot)
                                if (active) {
                                    locations?.forEach { amendLocationInPlace(it) }
                                }
                                resSnapshot = locations?.firstOrNull()
                            }

                            HookLogReporter.recordLocationRequest(
                                packageName = pkgName,
                                action = "LocationCallback.onLocationResult",
                                details = buildDetailedLocationLog(
                                    layer = "GMS/Callback",
                                    methodSig = "${callbackClass.name}.onLocationResult(LocationResult)",
                                    active = active,
                                    provider = origSnapshot?.provider ?: "fused",
                                    originLoc = origSnapshot,
                                    resultLoc = resSnapshot
                                ),
                                isSpoofed = active,
                                throttleMs = 1500L
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
                                    val active = isSpoofingActive(pkgName) && isAdvancedHookCache
                                    val realCount = (param.result as? Int) ?: 0
                                    if (active && realCount < 8) {
                                        param.result = 12
                                    }
                                    HookLogReporter.recordLocationRequest(
                                        packageName = pkgName,
                                        action = "GnssStatus.getSatelliteCount",
                                        details = buildDetailedLocationLog(
                                            layer = "SDK/GNSS",
                                            methodSig = "android.location.GnssStatus.getSatelliteCount()",
                                            active = active,
                                            provider = LocationManager.GPS_PROVIDER,
                                            extraNote = "realSatCount=$realCount, returnedSatCount=${param.result}"
                                        ),
                                        isSpoofed = active,
                                        throttleMs = 4000L
                                    )
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
