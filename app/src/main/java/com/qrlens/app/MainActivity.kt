package com.qrlens.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.content.res.ColorStateList
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusButton: TextView
    private lateinit var intro: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (22 * dp).toInt()
            setPadding(p, (56 * dp).toInt(), p, (40 * dp).toInt())
        }

        col.addView(text("QR LENS", 12f, Theme.ACCENT_A, bold = true).apply { letterSpacing = 0.2f })
        col.addView(text("Scan anything\non your screen.", 32f, Color.WHITE, bold = true).apply {
            setPadding(0, (8 * dp).toInt(), 0, (10 * dp).toInt())
            setLineSpacing(0f, 1.05f)
        })
        intro = text("", 15f, Theme.TEXT_2)
        col.addView(intro)

        // Status card
        val status = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.RED) }
        }
        status.addView(statusDot, LinearLayout.LayoutParams((10 * dp).toInt(), (10 * dp).toInt()))
        statusText = text("", 16f, Color.WHITE, bold = true)
        status.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = (12 * dp).toInt()
        })
        statusButton = button("Turn on", primary = true) {
            val svc = QrLensService.instance
            if (svc != null) {
                svc.pause()
                statusDot.postDelayed({ onResume() }, 400)
            } else {
                try {
                    startActivity(Setup.serviceSwitchIntent(this))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
        status.addView(statusButton)
        col.addView(status, cardLp(28))

        // Two modes + settings
        col.addView(Ui.navRow(this, "▦", "History", "Codes you've scanned, saved on this phone") {
            startActivity(Intent(this, HistoryActivity::class.java))
        }, cardLp(18))
        col.addView(Ui.navRow(this, "＋", "Generate", "Make a QR code from text or a link") {
            startActivity(Intent(this, GenerateActivity::class.java))
        }, cardLp(12))
        col.addView(Ui.navRow(this, "⚙", "Settings", "Shortcut, animation, sound, security") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, cardLp(12))

        // Setup steps
        val steps = card()
        steps.addView(text("Setup (one time)", 16f, Color.WHITE, bold = true))
        steps.addView(text(
            "1.  Tap Turn on, choose QR Lens under Installed/Downloaded apps and switch it on.\n\n" +
                "2.  If Android says the setting is restricted (Android 13+ for apps from an APK): open App info, tap the ⋮ menu, choose \"Allow restricted settings\", then repeat step 1.\n\n" +
                "3.  Done. Use your shortcut on any screen.",
            14f, Theme.TEXT_2
        ).apply { setPadding(0, (10 * dp).toInt(), 0, (14 * dp).toInt()) })
        steps.addView(button("Open App info", primary = false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(steps, cardLp(18))

        col.addView(text(
            "Links open in your browser. Anything else (text, contacts, events, Wi-Fi) opens in your phone's own viewer. Everything is processed on-device.",
            13f, Theme.TEXT_2
        ).apply { setPadding((4 * dp).toInt(), (22 * dp).toInt(), (4 * dp).toInt(), 0) })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(col)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val running = QrLensService.instance != null
        val enabled = isServiceEnabled()
        val color = when {
            running -> Color.rgb(0x4A, 0xDE, 0x80)
            enabled -> Color.rgb(0xFB, 0xBF, 0x24)
            else -> Color.rgb(0xF8, 0x71, 0x71)
        }
        (statusDot.background as GradientDrawable).setColor(color)
        statusText.text = when {
            running -> "Ready: ${Trigger.byId(Prefs.triggerId(this)).short}"
            // Switched on in Settings, but Android isn't running it (e.g. after a crash).
            enabled -> "Stopped. Turn it off and on"
            else -> "Service is off"
        }
        refreshTexts()
        statusButton.text = when {
            running -> "Pause"
            enabled -> "Fix"
            else -> "Turn on"
        }
    }

    private fun refreshTexts() {
        val t = Trigger.byId(Prefs.triggerId(this))
        intro.text = "${t.title}, anywhere. QR Lens freezes the screen, finds every QR code and zooms right in."
        if (QrLensService.instance != null) statusText.text = "Ready: ${t.short}"
    }

    /** Switched on in Accessibility settings (not necessarily running). */
    private fun isServiceEnabled(): Boolean {
        if (QrLensService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(this, QrLensService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), ignoreCase = true) || it.equals(cn.flattenToShortString(), ignoreCase = true)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (18 * dp).toInt()
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            cornerRadius = 22 * dp
            setColor(Color.rgb(0x15, 0x16, 0x20))
            setStroke((1 * dp).toInt().coerceAtLeast(1), Theme.CARD_STROKE)
        }
    }

    private fun cardLp(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (top * dp).toInt() }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(if (primary) Color.rgb(0x0B, 0x0C, 0x14) else Color.WHITE)
        val h = (18 * dp).toInt()
        val v = (10 * dp).toInt()
        setPadding(h, v, h, v)
        background = if (primary) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Theme.ACCENT_A, Theme.ACCENT_B))
                .apply { cornerRadius = 100 * dp }
        } else {
            GradientDrawable().apply {
                cornerRadius = 100 * dp
                setColor(Color.argb(0x1F, 0xFF, 0xFF, 0xFF))
            }
        }
        setOnClickListener { onClick() }
    }

    companion object {
        private val BG = Color.rgb(0x0A, 0x0B, 0x12)
    }
}
