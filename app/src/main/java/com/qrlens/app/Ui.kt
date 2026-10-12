package com.qrlens.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Shared, code-built UI helpers so every screen matches the overlay's look. */
object Ui {
    fun dp(ctx: Context) = ctx.resources.displayMetrics.density

    fun text(ctx: Context, s: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD)
        else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(0f, 1.18f)
    }

    fun card(ctx: Context): LinearLayout {
        val d = dp(ctx)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (18 * d).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = 22 * d
                setColor(Theme.CARD_BG)
                setStroke((1 * d).toInt().coerceAtLeast(1), Theme.CARD_STROKE)
            }
        }
    }

    fun cardLp(ctx: Context, top: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (top * dp(ctx)).toInt() }
    }

    fun button(ctx: Context, label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val d = dp(ctx)
        return TextView(ctx).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (primary) Color.rgb(0x0B, 0x0C, 0x14) else Color.WHITE)
            gravity = Gravity.CENTER
            val h = (18 * d).toInt()
            val v = (11 * d).toInt()
            setPadding(h, v, h, v)
            val radius = 100 * d
            val bg = if (primary) {
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Theme.ACCENT_A, Theme.ACCENT_B))
                    .apply { cornerRadius = radius }
            } else {
                GradientDrawable().apply { cornerRadius = radius; setColor(Color.argb(0x1F, 0xFF, 0xFF, 0xFF)) }
            }
            val mask = GradientDrawable().apply { cornerRadius = radius; setColor(0xFFFFFFFF.toInt()) }
            background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, mask)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    @Suppress("DEPRECATION")
    fun switch(ctx: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        return Switch(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
    }

    /** Standard dark scroll page. Returns the inner column to add content to. */
    fun page(act: Activity): Pair<ScrollView, LinearLayout> {
        val d = dp(act)
        act.window.statusBarColor = Theme.BG
        act.window.navigationBarColor = Theme.BG
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = (22 * d).toInt()
            setPadding(p, (48 * d).toInt(), p, (40 * d).toInt())
        }
        val scroll = ScrollView(act).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            addView(col)
        }
        return Pair(scroll, col)
    }

    /** A tappable section row for the home screen: icon glyph, title, subtitle, chevron. */
    fun navRow(ctx: Context, glyph: String, title: String, subtitle: String, onClick: () -> Unit): View {
        val d = dp(ctx)
        val row = card(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(ctx).apply {
            text = glyph
            textSize = 22f
            setTextColor(Theme.ACCENT_A)
        }, LinearLayout.LayoutParams((34 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(ctx, title, 17f, Color.WHITE, bold = true))
        texts.addView(text(ctx, subtitle, 13f, Theme.TEXT_2))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = (10 * d).toInt()
        })
        row.addView(text(ctx, "›", 24f, Theme.TEXT_2))
        return row
    }
}
