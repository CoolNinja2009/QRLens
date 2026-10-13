package com.qrlens.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Crisp vector icons drawn in code (white), so they look consistent and never fall back to a
 * missing system glyph. Tint at use-site with ImageView.setColorFilter().
 */
object Icons {
    private fun bmp(ctx: Context, sizeDp: Float, draw: (Canvas, Float, Paint) -> Unit): Bitmap {
        val s = (sizeDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        draw(c, s.toFloat(), p)
        return b
    }

    /** 2×2 rounded squares — History. */
    fun history(ctx: Context, sizeDp: Float = 24f) = bmp(ctx, sizeDp) { c, s, p ->
        p.style = Paint.Style.FILL
        val pad = s * 0.18f
        val gap = s * 0.12f
        val cell = (s - 2 * pad - gap) / 2
        val r = cell * 0.28f
        for (i in 0..1) for (j in 0..1) {
            val x = pad + i * (cell + gap)
            val y = pad + j * (cell + gap)
            c.drawRoundRect(x, y, x + cell, y + cell, r, r, p)
        }
    }

    /** Plus — Generate. */
    fun plus(ctx: Context, sizeDp: Float = 24f) = bmp(ctx, sizeDp) { c, s, p ->
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.1f
        val pad = s * 0.22f
        c.drawLine(s / 2, pad, s / 2, s - pad, p)
        c.drawLine(pad, s / 2, s - pad, s / 2, p)
    }

    /** Sliders — Settings. */
    fun settings(ctx: Context, sizeDp: Float = 22f) = bmp(ctx, sizeDp) { c, s, p ->
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.08f
        val pad = s * 0.2f
        val rows = floatArrayOf(0.3f, 0.5f, 0.7f)
        val knobX = floatArrayOf(0.66f, 0.38f, 0.6f)
        for (k in rows.indices) {
            val y = s * rows[k]
            c.drawLine(pad, y, s - pad, y, p)
            p.style = Paint.Style.FILL
            c.drawCircle(s * knobX[k], y, s * 0.075f, p)
            // knob border hole
            val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT; xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
            c.drawCircle(s * knobX[k], y, s * 0.035f, hole)
            p.style = Paint.Style.STROKE
        }
    }

    /** Gear — Settings. */
    fun gear(ctx: Context, sizeDp: Float = 22f) = bmp(ctx, sizeDp) { c, s, p ->
        val cx = s / 2f; val cy = s / 2f
        val rOut = s * 0.40f      // tooth tip radius
        val rIn = s * 0.30f       // tooth base radius
        val toothHalf = 10.0      // half tooth angular width (degrees)
        val teeth = 8
        p.style = Paint.Style.FILL
        val gear = Path()
        for (t in 0 until teeth) {
            val a = t * (360.0 / teeth)
            val pts = doubleArrayOf(a - 22, a - toothHalf, a + toothHalf, a + 22)
            val radii = doubleArrayOf(rIn.toDouble(), rOut.toDouble(), rOut.toDouble(), rIn.toDouble())
            for (k in 0 until 4) {
                val rad = Math.toRadians(pts[k])
                val x = cx + (radii[k] * Math.cos(rad)).toFloat()
                val y = cy + (radii[k] * Math.sin(rad)).toFloat()
                if (t == 0 && k == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
            }
        }
        gear.close()
        c.drawPath(gear, p)
        // punch out the center hole
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        c.drawCircle(cx, cy, s * 0.15f, hole)
    }

    /** Trash can. */
    fun trash(ctx: Context, sizeDp: Float = 22f) = bmp(ctx, sizeDp) { c, s, p ->
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.08f
        val l = s * 0.26f; val r = s * 0.74f
        val top = s * 0.3f
        c.drawLine(s * 0.18f, top, s * 0.82f, top, p)          // lid
        c.drawLine(s * 0.42f, s * 0.22f, s * 0.58f, s * 0.22f, p) // handle
        val body = Path().apply {
            moveTo(l, top)
            lineTo(l + s * 0.03f, s * 0.8f)
            quadTo(l + s * 0.03f, s * 0.84f, l + s * 0.08f, s * 0.84f)
            lineTo(r - s * 0.08f, s * 0.84f)
            quadTo(r - s * 0.03f, s * 0.84f, r - s * 0.03f, s * 0.8f)
            lineTo(r, top)
        }
        c.drawPath(body, p)
        c.drawLine(s * 0.42f, top + s * 0.1f, s * 0.42f, s * 0.74f, p)
        c.drawLine(s * 0.58f, top + s * 0.1f, s * 0.58f, s * 0.74f, p)
    }

    /** Share — three nodes connected. */
    fun share(ctx: Context, sizeDp: Float = 24f) = bmp(ctx, sizeDp) { c, s, p ->
        val rr = s * 0.1f
        val n1x = s * 0.72f; val n1y = s * 0.22f
        val n2x = s * 0.72f; val n2y = s * 0.78f
        val n3x = s * 0.28f; val n3y = s * 0.5f
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.07f
        c.drawLine(n3x, n3y, n1x, n1y, p)
        c.drawLine(n3x, n3y, n2x, n2y, p)
        p.style = Paint.Style.FILL
        c.drawCircle(n1x, n1y, rr, p)
        c.drawCircle(n2x, n2y, rr, p)
        c.drawCircle(n3x, n3y, rr, p)
    }

    /** Picture frame with sun + mountain — source image. */
    fun image(ctx: Context, sizeDp: Float = 24f) = bmp(ctx, sizeDp) { c, s, p ->
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.07f
        val pad = s * 0.2f
        val r = s * 0.1f
        c.drawRoundRect(pad, pad, s - pad, s - pad, r, r, p)
        p.style = Paint.Style.FILL
        c.drawCircle(s * 0.37f, s * 0.37f, s * 0.055f, p)
        val m = Path().apply {
            moveTo(pad + s * 0.02f, s - pad - s * 0.02f)
            lineTo(s * 0.46f, s * 0.52f)
            lineTo(s * 0.6f, s * 0.66f)
            lineTo(s * 0.72f, s * 0.54f)
            lineTo(s - pad - s * 0.02f, s - pad - s * 0.02f)
            close()
        }
        c.drawPath(m, p)
    }

    /** Chevron pointing right. */
    fun chevron(ctx: Context, sizeDp: Float = 18f) = bmp(ctx, sizeDp) { c, s, p ->
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.1f
        c.drawLine(s * 0.4f, s * 0.28f, s * 0.64f, s * 0.5f, p)
        c.drawLine(s * 0.64f, s * 0.5f, s * 0.4f, s * 0.72f, p)
    }
}
