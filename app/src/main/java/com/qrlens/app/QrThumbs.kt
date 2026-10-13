package com.qrlens.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.widget.ImageView

/**
 * Generates QR thumbnails off the UI thread and caches them, so the History grid scrolls
 * smoothly even with ~100 codes. Thumbnails are small (generated at [SIZE] px).
 */
object QrThumbs {
    private const val SIZE = 240
    private const val CACHE = 120
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Bitmap>) = size > CACHE
    }
    private val worker = HandlerThread("qr-thumbs").apply { start() }
    private val bg = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())

    /** Sets the thumbnail for [text] into [img], generating it in the background if needed. */
    fun into(img: ImageView, text: String) {
        val cached = synchronized(cache) { cache[text] }
        if (cached != null) {
            img.setImageBitmap(cached)
            return
        }
        img.setImageBitmap(null)
        img.tag = text
        bg.post {
            val bmp = QrGen.encode(text, sizePx = SIZE) ?: return@post
            synchronized(cache) { cache[text] = bmp }
            main.post { if (img.tag == text) img.setImageBitmap(bmp) }
        }
    }
}
