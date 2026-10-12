package com.qrlens.app

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.Binarizer
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.detector.FinderPattern
import java.util.EnumMap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Finds every QR code in a screenshot (normal, dark-mode/inverted, rotated, tiny or huge).
 * Runs several cheap passes and merges them: half resolution first (fast, best for large codes),
 * then full resolution (best for small codes), each with two binarizers, normal and inverted.
 */
object QrDetector {

    class Hit(val text: String, val rect: RectF)

    fun detect(bmp: Bitmap): List<Hit> {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val full: LuminanceSource = RGBLuminanceSource(w, h, px)

        val hw = max(1, w / 2)
        val hh = max(1, h / 2)
        val hpx = IntArray(hw * hh)
        for (y in 0 until hh) {
            val row = (y * 2) * w
            val out = y * hw
            for (x in 0 until hw) hpx[out + x] = px[row + x * 2]
        }
        val half: LuminanceSource = RGBLuminanceSource(hw, hh, hpx)

        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.CHARACTER_SET] = "UTF-8"
        hints[DecodeHintType.TRY_HARDER] = true

        val hits = ArrayList<Hit>()
        fun collect(src: LuminanceSource, scale: Float) {
            for (binarizer in arrayOf<Binarizer>(HybridBinarizer(src), GlobalHistogramBinarizer(src))) {
                for (r in run(binarizer, hints)) {
                    val text = r.text ?: continue
                    val rect = boundsOf(r, scale) ?: continue
                    if (hits.none { it.text == text && RectF.intersects(it.rect, rect) }) hits.add(Hit(text, rect))
                }
            }
        }
        collect(half, 2f)
        collect(half.invert(), 2f)
        collect(full, 1f)
        collect(full.invert(), 1f)
        return hits
    }

    private fun run(binarizer: Binarizer, hints: Map<DecodeHintType, Any>): List<Result> = try {
        QRCodeMultiReader().decodeMultiple(BinaryBitmap(binarizer), hints).toList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Finder-pattern centres sit 3.5 modules inside the code's corners, so grow the box by that. */
    private fun boundsOf(r: Result, scale: Float): RectF? {
        val pts: Array<ResultPoint?> = r.resultPoints ?: return null
        val finders = pts.filterNotNull().take(3)
        if (finders.size < 3) return null
        val bl = finders[0]
        val tl = finders[1]
        val tr = finders[2]
        // The fourth (bottom-right) corner completes the parallelogram.
        val brX = tr.x + bl.x - tl.x
        val brY = tr.y + bl.y - tl.y
        val side = hypot((tr.x - tl.x).toDouble(), (tr.y - tl.y).toDouble()).toFloat()
        val module = (tl as? FinderPattern)?.estimatedModuleSize ?: (side / 20f)
        val pad = module * 3.5f
        val minX = min(min(bl.x, tl.x), min(tr.x, brX)) - pad
        val maxX = max(max(bl.x, tl.x), max(tr.x, brX)) + pad
        val minY = min(min(bl.y, tl.y), min(tr.y, brY)) - pad
        val maxY = max(max(bl.y, tl.y), max(tr.y, brY)) + pad
        return RectF(minX * scale, minY * scale, maxX * scale, maxY * scale)
    }
}
