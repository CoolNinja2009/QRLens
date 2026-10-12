package com.qrlens.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
    private val engine by lazy { TriggerEngine(this, handler) { trigger() } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayController(this)
        QrLensTile.refresh(this)
    }

    override fun onDestroy() {
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

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val ov = overlay
        if (ov != null && ov.isShowing) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) ov.back()
                return true
            }
            return false
        }
        return engine.onKey(event)
    }

    fun trigger() {
        if (capturing || overlay?.isShowing == true) return
        capturing = true
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
                overlay?.show(bmp)
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
        private const val PRESS_WINDOW_MS = 450L

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
