package com.qrlens.app

import android.graphics.Bitmap
import kotlin.math.max

/** Cheap, good-looking background blur: downscale then a few box-blur passes. */
object Blur {
    fun make(src: Bitmap, factor: Int = 14, radius: Int = 2, passes: Int = 3): Bitmap {
        val w = max(1, src.width / factor)
        val h = max(1, src.height / factor)
        val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(passes) {
            boxPass(px, tmp, w, h, radius, horizontal = true)
            boxPass(tmp, px, w, h, radius, horizontal = false)
        }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        for (o in 0 until outer) {
            for (i in 0 until inner) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, inner - 1)
                    val c = if (horizontal) src[o * w + j] else src[j * w + o]
                    a += c ushr 24
                    rr += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    n++
                }
                val idx = if (horizontal) o * w + i else i * w + o
                dst[idx] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}
