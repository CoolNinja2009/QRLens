package com.qrlens.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/** Creates QR code bitmaps on-device (ZXing writer). No network. */
object QrGen {

    fun encode(text: String, sizePx: Int = 900, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap? {
        val content = text.trim()
        if (content.isEmpty()) return null
        return try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val px = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) px[row + x] = if (matrix.get(x, y)) dark else light
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(px, 0, w, 0, 0, w, h)
            }
        } catch (t: Throwable) {
            null   // e.g. content too long for a QR code
        }
    }

    /** Builds a WIFI: payload from fields, for the generator's Wi-Fi mode. */
    fun wifiPayload(ssid: String, password: String, security: String, hidden: Boolean): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
            .replace(":", "\\:").replace("\"", "\\\"")
        val sec = if (password.isEmpty()) "nopass" else security
        return buildString {
            append("WIFI:T:").append(sec).append(';')
            append("S:").append(esc(ssid)).append(';')
            if (password.isNotEmpty()) append("P:").append(esc(password)).append(';')
            if (hidden) append("H:true;")
            append(';')
        }
    }
}
