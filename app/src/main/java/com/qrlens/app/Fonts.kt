package com.qrlens.app

import android.content.Context
import android.graphics.Typeface

/**
 * The app's bundled type (Inter). Loading our own font makes typography identical on every phone,
 * including devices with a custom system font (where "sans-serif" can resolve to a serif face).
 */
object Fonts {
    private var reg: Typeface? = null
    private var med: Typeface? = null
    private var bold: Typeface? = null

    private fun load(ctx: Context, path: String): Typeface = try {
        Typeface.createFromAsset(ctx.applicationContext.assets, path)
    } catch (t: Throwable) {
        Typeface.create("sans-serif", Typeface.NORMAL)  // safe fallback
    }

    fun regular(ctx: Context): Typeface = reg ?: load(ctx, "fonts/inter_regular.otf").also { reg = it }
    fun medium(ctx: Context): Typeface = med ?: load(ctx, "fonts/inter_medium.otf").also { med = it }
    fun bold(ctx: Context): Typeface = bold ?: load(ctx, "fonts/inter_bold.otf").also { bold = it }
}
