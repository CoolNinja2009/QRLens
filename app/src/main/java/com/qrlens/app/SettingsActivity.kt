package com.qrlens.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.content.res.ColorStateList

/** All of QR Lens's options in one place: Shortcut, Animation, Sound, Security, About. */
class SettingsActivity : Activity() {

    private val d by lazy { Ui.dp(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (scroll, col) = Ui.page(this)
        col.addView(Ui.text(this, "Settings", 26f, Color.WHITE, bold = true))

        col.addView(shortcutCard(), Ui.cardLp(this, 18))
        val animCard = choiceCard("Animation", "The scan effect when you trigger QR Lens.",
            listOf("classic" to "Classic sweep", "ripple" to "Ripple from center", "radar" to "Radar sweep"),
            Prefs.animation(this)) { Prefs.setAnimation(this, it) }
        animCard.addView(Ui.switch(this, "Shortened animation", Prefs.shortAnim(this)) {
            Prefs.setShortAnim(this, it)
        }.apply { setPadding(0, (10 * d).toInt(), 0, 0) })
        animCard.addView(Ui.text(this,
            "Off: the scan effect plays through once, with found codes faintly outlined until it ends. Tap anywhere to skip ahead. On: jump to the code the moment it's found.",
            13f, Theme.TEXT_2).apply { setPadding(0, (2 * d).toInt(), 0, 0) })
        col.addView(animCard, Ui.cardLp(this, 14))
        col.addView(choiceCard("Sound", "A soft chime when a scan starts.",
            listOf("default" to "Default chime", "none" to "No sound"),
            Prefs.sound(this)) { Prefs.setSound(this, it) }, Ui.cardLp(this, 14))
        col.addView(securityCard(), Ui.cardLp(this, 14))
        col.addView(aboutCard(), Ui.cardLp(this, 14))

        setContentView(scroll)
    }

    private fun shortcutCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.text(this, "Shortcut", 17f, Color.WHITE, bold = true))
        card.addView(Ui.text(this, "How you open the scanner. The Power button can't be used — Android keeps it for itself.",
            13f, Theme.TEXT_2).apply { setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt()) })
        val group = RadioGroup(this)
        val current = Prefs.triggerId(this)
        for ((i, t) in Trigger.values().withIndex()) {
            group.addView(RadioButton(this).apply {
                typeface = Fonts.regular(this@SettingsActivity)
                id = 2000 + i
                text = t.title
                textSize = 15f
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(Theme.ACCENT_A)
                setPadding((6 * d).toInt(), (9 * d).toInt(), 0, (9 * d).toInt())
                isChecked = t.id == current
            })
        }
        val hint = Ui.text(this, Trigger.byId(current).hint, 13f, Theme.ACCENT_A)
            .apply { setPadding(0, (4 * d).toInt(), 0, 0) }
        group.setOnCheckedChangeListener { _, id ->
            val t = Trigger.values().getOrNull(id - 2000) ?: return@setOnCheckedChangeListener
            Prefs.setTriggerId(this, t.id)
            hint.text = t.hint
        }
        card.addView(group)
        card.addView(hint)
        return card
    }

    /** A titled card with a radio list persisting a string pref. */
    private fun choiceCard(title: String, subtitle: String, options: List<Pair<String, String>>,
                           current: String, onPick: (String) -> Unit): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.text(this, title, 17f, Color.WHITE, bold = true))
        card.addView(Ui.text(this, subtitle, 13f, Theme.TEXT_2).apply { setPadding(0, (4 * d).toInt(), 0, (2 * d).toInt()) })
        val group = RadioGroup(this)
        options.forEachIndexed { i, (value, label) ->
            group.addView(RadioButton(this).apply {
                typeface = Fonts.regular(this@SettingsActivity)
                id = 3000 + i
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(Theme.ACCENT_A)
                setPadding((6 * d).toInt(), (9 * d).toInt(), 0, (9 * d).toInt())
                isChecked = value == current
            })
        }
        group.setOnCheckedChangeListener { _, id ->
            options.getOrNull(id - 3000)?.let { onPick(it.first) }
        }
        card.addView(group)
        return card
    }

    private fun securityCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.text(this, "Security", 17f, Color.WHITE, bold = true))
        card.addView(Ui.switch(this, "Warn about risky links & payments", Prefs.flagging(this)) {
            Prefs.setFlagging(this, it)
        }.apply { setPadding(0, (8 * d).toInt(), 0, 0) })
        card.addView(Ui.text(this,
            "Flags look-alike domains, insecure login pages and shortened links, and cautions on UPI payments. All checks run on-device; it's a hint, not a guarantee.",
            13f, Theme.TEXT_2).apply { setPadding(0, (2 * d).toInt(), 0, (10 * d).toInt()) })

        card.addView(Ui.switch(this, "Auto-pause in BHIM & bank apps", Prefs.autoPause(this)) {
            Prefs.setAutoPause(this, it)
        })
        card.addView(Ui.text(this,
            "These apps refuse to run while a sideloaded accessibility app is on, so QR Lens switches itself off when they open. Turn it back on by holding both volume keys.",
            13f, Theme.TEXT_2).apply { setPadding(0, (2 * d).toInt(), 0, (10 * d).toInt()) })

        card.addView(Ui.switch(this, "Hold back volume while counting presses", Prefs.holdBackVolume(this)) {
            Prefs.setHoldBackVolume(this, it)
        })
        card.addView(Ui.text(this,
            "On: your shortcut won't change the volume. Off: volume changes instantly.",
            13f, Theme.TEXT_2).apply { setPadding(0, (2 * d).toInt(), 0, 0) })
        return card
    }

    private fun aboutCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.text(this, "About", 17f, Color.WHITE, bold = true))
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        card.addView(Ui.text(this, "QR Lens $version · on-device, no internet permission.", 13f, Theme.TEXT_2)
            .apply { setPadding(0, (4 * d).toInt(), 0, (12 * d).toInt()) })
        card.addView(Ui.button(this, "Open source code", primary = false) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/CoolNinja2009/QRLens")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(Ui.button(this, "Accessibility settings", primary = false) {
            try {
                startActivity(Setup.serviceSwitchIntent(this))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * d).toInt()
        })
        return card
    }
}
