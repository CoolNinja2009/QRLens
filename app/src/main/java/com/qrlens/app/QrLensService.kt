package com.qrlens.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Listens for the chosen volume-key gesture, grabs a screenshot (no permission prompt needed
 * thanks to the accessibility screenshot API) and hands it to the overlay.
 */
class QrLensService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: OverlayController? = null

    private var capturing = false
    private var captureStarted = 0L

    /** Screen turned off mid-scan: close the overlay so it never lingers over the lock screen. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            overlay?.removeNow()
        }
    }
    private val engine by lazy { TriggerEngine(this, handler) { trigger() } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayController(this)
        try {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        } catch (_: Exception) {
        }
        QrLensTile.refresh(this)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenOff)
        } catch (_: Exception) {
        }
        overlay?.removeNow()
        overlay = null
        if (instance === this) instance = null
        QrLensTile.refresh(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Banking / UPI apps such as BHIM refuse to run while a non-Play-Store accessibility
        // service is on. When one comes to the front, switch ourselves off completely.
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in PAUSE_FOR && Prefs.autoPause(this)) pause(pkg)
    }

    /** Fully turns the service off (the user turns it back on with the volume-keys shortcut). */
    fun pause(reason: String? = null) {
        overlay?.removeNow()
        val name = when (reason) {
            null -> "QR Lens paused"
            "in.org.npci.upiapp" -> "QR Lens paused for BHIM"
            else -> "QR Lens paused for this app"
        }
        Toast.makeText(this, "$name. Hold both volume keys to turn it back on.", Toast.LENGTH_LONG).show()
        disableSelf()
    }
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean = try {
        handleKey(event)
    } catch (t: Throwable) {
        // Never let a bug here crash the service (a crashed service stays "on" but stops working).
        overlay?.removeNow()
        false
    }

    private fun handleKey(event: KeyEvent): Boolean {
        val ov = overlay
        if (ov != null && ov.isShowing && ov.isStuck()) ov.removeNow()   // self-heal
        if (ov != null && ov.isShowing) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) ov.back()
                return true
            }
            // Let the engine see key releases so its state stays in sync (e.g. the 3rd press's UP).
            if (event.action == KeyEvent.ACTION_UP) return engine.onKey(event)
            return false
        }
        return engine.onKey(event)
    }

    fun trigger() {
        if (capturing && SystemClock.uptimeMillis() - captureStarted > 3000) capturing = false
        if (capturing || overlay?.isShowing == true) return
        capturing = true
        captureStarted = SystemClock.uptimeMillis()
        engine.reset()
        haptic(VibrationEffect.EFFECT_HEAVY_CLICK)
        capture(retry = true)
    }

    private fun capture(retry: Boolean) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = result.hardwareBuffer
                val bmp: Bitmap? = try {
                    Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                } catch (t: Throwable) {
                    null
                } finally {
                    hw.close()
                }
                capturing = false
                if (bmp == null) {
                    toast("Couldn't capture the screen")
                    return
                }
                try {
                    overlay?.show(bmp)
                } catch (t: Throwable) {
                    overlay?.removeNow()
                    toast("QR Lens hit an error, try again")
                }
            }

            override fun onFailure(errorCode: Int) {
                if (retry && errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    handler.postDelayed({ capture(retry = false) }, 400)
                    return
                }
                capturing = false
                toast("Screenshot blocked (code $errorCode)")
            }
        })
    }

    fun haptic(effect: Int) {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(effect))
        } catch (_: Throwable) {
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Apps known to block while a sideloaded accessibility service is enabled. */
        val PAUSE_FOR = setOf(
            "in.org.npci.upiapp",            // BHIM
            "com.sbi.lotusintouch",          // SBI YONO
            "com.sbi.SBIFreedomPlus",        // YONO Lite SBI
            "com.csam.icici.bank.imobile",   // ICICI iMobile
            "com.snapwork.hdfc",             // HDFC Bank
            "com.axis.mobile",               // Axis Mobile
            "com.msf.kbank.mobile"           // Kotak
        )

        @Volatile
        var instance: QrLensService? = null
            private set
    }
}

object Prefs {
    private const val FILE = "qrlens"
    private const val KEY_HOLD_BACK = "hold_back_volume"
    private const val KEY_AUTO_PAUSE = "auto_pause_bank_apps"
    private const val KEY_TRIGGER = "trigger"

    fun triggerId(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_TRIGGER, Trigger.UP3.id) ?: Trigger.UP3.id

    fun setTriggerId(ctx: Context, id: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_TRIGGER, id).apply()
    }

    fun autoPause(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_PAUSE, true)

    fun setAutoPause(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_PAUSE, value).apply()
    }

    fun holdBackVolume(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_HOLD_BACK, true)

    fun setHoldBackVolume(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOLD_BACK, value).apply()
    }
}
