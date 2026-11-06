package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import io.github.controlwear.virtual.joystick.android.JoystickView
import io.github.jqssun.gpssetter.R
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class JoystickService : Service(), View.OnTouchListener, View.OnClickListener {

    companion object {
        @Volatile
        var isRunning: Boolean = false
    }

    private var wm: WindowManager? = null
    private var mJoystickContainerView: View? = null
    private var mJoystickView: JoystickView? = null
    private var mJoystickLayoutParams: WindowManager.LayoutParams? = null
    private var lat: Double = PrefManager.getLat
    private var lon: Double = PrefManager.getLng
    private var lastJoystickLogMs: Long = 0L

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        AppLogger.logApp(
            action = "JoystickService.onCreate (摇杆悬浮窗启动)",
            details = String.format(Locale.US, "初始坐标=(%.6f, %.6f)", lat, lon)
        )
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val mInflater: LayoutInflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        mJoystickContainerView = mInflater.inflate(R.layout.joystick, null as ViewGroup?) as View
        mJoystickView = mJoystickContainerView!!.findViewById(R.id.joystickView_right)
        mJoystickView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                try {
                    lat = PrefManager.getLat
                    lon = PrefManager.getLng
                    updateLocation(lat, lon, forceLog = true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            false
        }
        mJoystickView?.setOnMoveListener { angle, strength, _ ->
            val radians = Math.toRadians(angle.toDouble())
            try {
                val factorX: Double = cos(radians) / 100000.0 * (strength / 30)
                val factorY: Double = sin(radians) / 100000.0 * (strength / 30)
                lon = PrefManager.getLng + factorX
                lat = PrefManager.getLat + factorY
                updateLocation(lat, lon, forceLog = false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        mJoystickLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        mJoystickLayoutParams?.let {
            it.gravity = Gravity.LEFT
        }

        wm!!.addView(mJoystickContainerView, mJoystickLayoutParams)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        return false
    }

    override fun onClick(v: View?) {
    }

    override fun onDestroy() {
        isRunning = false
        AppLogger.logApp(
            action = "JoystickService.onDestroy (摇杆悬浮窗关闭)",
            details = String.format(Locale.US, "结束坐标=(%.6f, %.6f)", lat, lon)
        )
        super.onDestroy()
        if (this.mJoystickContainerView != null) {
            this.wm?.removeView(mJoystickContainerView)
            this.mJoystickContainerView = null
        }
    }

    private fun updateLocation(lat: Double, lon: Double, forceLog: Boolean) {
        PrefManager.update(start = PrefManager.isStarted, la = lat, ln = lon)
        val now = System.currentTimeMillis()
        if (forceLog || now - lastJoystickLogMs >= 1500L) {
            lastJoystickLogMs = now
            AppLogger.logApp(
                action = "JoystickService.updateLocation (摇杆移动位置)",
                details = String.format(Locale.US, "更新伪装坐标=(%.6f, %.6f)", lat, lon)
            )
        }
    }
}