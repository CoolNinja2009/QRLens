package com.qrlens.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the frozen screenshot and runs every animation through a single "camera"
 * (scale + translation). Coordinates of the screenshot are "bitmap space"; the camera maps
 * bitmap space to view space. Zooms interpolate the focal point linearly and the scale
 * logarithmically, which is what makes them feel natural and smooth.
 */
class ScanView(context: Context) : View(context) {

    interface Listener {
        fun onHoleTap()
        fun onBackgroundTap()
        fun onCodeTap(index: Int)
    }

    var listener: Listener? = null

    private val dp = resources.displayMetrics.density

    private var shot: Bitmap? = null
    private var blurShot: Bitmap? = null
    private var bw = 1f
    private var bh = 1f
    private var originX = 0f
    private var originY = 0f
    private var laidOut = false
    private var pendingStart: (() -> Unit)? = null

    private data class Cam(val s: Float, val tx: Float, val ty: Float)

    private var cam = Cam(1f, 0f, 0f)

    // Animated properties
    private var bgAlpha = 0f
    private var corner = 0f
    private var flash = 0f
    private var scanDim = 0f
    private var sweep = 0f
    private var sweepAlpha = 0f
    private var focus = 0f
    private var bracket = 0f
    private var bracketAlpha = 0f
    private var breathe = 0f
    private var pulse = 0f
    private var hole: RectF? = null

    // Codes already found while the scan animation is still playing: shown as faint outlines.
    private var pendingRects: List<RectF> = emptyList()
    private var pendingAlpha = 0f
    private var introAt = 0L

    // Overview of several codes
    private var overview = 0f
    private var holes: List<RectF> = emptyList()
    private var labels: List<String> = emptyList()
    var inOverview = false
        private set

    /** "classic" sweep line, or "ripple" rings from the center. Set before start(). */
    var animStyle: String = "classic"

    private val anims = ArrayList<Animator>()
    private val byKey = HashMap<String, Animator>()
    private var camAnim: Animator? = null
    private var holeAnim: Animator? = null
    private var sweepAnim: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null

    // Drawing helpers (allocated once)
    private val camMatrix = Matrix()
    private val blurMatrix = Matrix()
    private val shaderMatrix = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = Fonts.bold(context)
        letterSpacing = 0.06f
    }
    private val multiPath = Path()
    private val chipRect = RectF()
    private val pagePath = Path()
    private val holePath = Path()
    private val bracketPath = Path()
    private val tmpRect = RectF()
    private val pageRect = RectF()
    private val holeView = RectF()
    private val frameRect = RectF()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    // ---------------------------------------------------------------- setup

    fun start(bitmap: Bitmap, blurred: Bitmap) {
        shot = bitmap
        blurShot = blurred
        bw = bitmap.width.toFloat()
        bh = bitmap.height.toFloat()
        sweepPaint.shader = LinearGradient(
            0f, 0f, 0f, 1f,
            intArrayOf(Color.TRANSPARENT, withAlpha(Theme.ACCENT_A, 0.06f), withAlpha(Theme.ACCENT_B, 0.34f)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        linePaint.shader = LinearGradient(
            0f, 0f, bw, 0f,
            intArrayOf(Theme.ACCENT_A, Theme.ACCENT_B, Theme.ACCENT_A),
            null, Shader.TileMode.CLAMP
        )
        if (laidOut) startIntro() else pendingStart = { startIntro() }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        originX = loc[0].toFloat()
        originY = loc[1].toFloat()
        if (!laidOut) {
            laidOut = true
            cam = identityCam()
            pendingStart?.invoke()
            pendingStart = null
        }
    }

    private fun identityCam() = Cam(1f, -originX, -originY)

    private fun introCam(): Cam {
        val s = 0.9f
        val cx = bw / 2f
        val cy = bh / 2f
        val vx = cx - originX
        val vy = cy - originY
        return Cam(s, vx - cx * s, vy - cy * s)
    }

    private fun camFor(r: RectF): Cam {
        val side = max(r.width(), r.height()).coerceAtLeast(1f)
        val target = min(width, height) * 0.58f
        val s = (target / side).coerceIn(0.6f, 8f)
        val vx = width / 2f
        val vy = height * 0.56f
        return Cam(s, vx - r.centerX() * s, vy - r.centerY() * s)
    }

    // ---------------------------------------------------------------- phases

    private fun startIntro() {
        cam = identityCam()
        radarT0 = System.nanoTime()
        introAt = android.os.SystemClock.uptimeMillis()
        anim(0f, 1f, 220, Motion.STANDARD, key = "bgAlpha") { bgAlpha = it }
        anim(0f, 1f, 420, Motion.EMPHASIZED_DECEL, key = "corner") { corner = it }
        anim(0.55f, 0f, 380, Motion.STANDARD) { flash = it }
        anim(0f, 1f, 360, Motion.STANDARD, delay = 120, key = "scanDim") { scanDim = it }
        anim(0f, 1f, 320, Motion.STANDARD, delay = 160, key = "bracketAlpha") { bracketAlpha = it }
        anim(0f, 1f, 260, Motion.STANDARD, delay = 80, key = "sweepAlpha") { sweepAlpha = it }
        animateCam(introCam(), bw / 2f, bh / 2f, 460, Motion.EMPHASIZED_DECEL, null)

        sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_MS
            startDelay = SWEEP_DELAY
            repeatCount = ValueAnimator.INFINITE
            interpolator = PathInterpolator(0.45f, 0f, 0.4f, 1f)
            addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
            start()
        }
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = Motion.STANDARD
            addUpdateListener {
                pulse = it.animatedValue as Float
                breathe = pulse
                invalidate()
            }
            start()
        }
    }

    /** Codes were found but the scan animation keeps playing: mark each one with a faint outline. */
    fun markFound(rects: List<RectF>) {
        pendingRects = rects.map { RectF(it) }
        anim(pendingAlpha, 1f, 380, Motion.EMPHASIZED_DECEL, key = "pending") { pendingAlpha = it }
    }

    private fun clearFound() {
        if (pendingRects.isEmpty()) return
        anim(pendingAlpha, 0f, 300, Motion.STANDARD, key = "pending") { pendingAlpha = it }
    }

    /**
     * Milliseconds until the scan animation completes a full cycle (at least one whole pass since it
     * started, ending on a cycle boundary), so we never cut a wave or a sweep off halfway.
     */
    fun msToFinishCycle(): Long {
        val el = android.os.SystemClock.uptimeMillis() - introAt
        val period: Long
        val offset: Long
        when (animStyle) {
            "radar" -> { period = (radarPeriod * 1000).toLong(); offset = 0L }
            else -> { period = SWEEP_MS; offset = SWEEP_DELAY }
        }
        val target = max(el, offset + period)
        val cycles = (target - offset + period - 1) / period
        return (offset + cycles * period - el).coerceAtLeast(0L)
    }

    /** Lock the brackets onto [r] (bitmap coords) and zoom into it. */
    fun focusOn(r: RectF, onDone: () -> Unit) {
        hole = RectF(r)
        clearFound()
        anim(sweepAlpha, 0f, 260, Motion.STANDARD, key = "sweepAlpha") { sweepAlpha = it }
        anim(scanDim, 0f, 300, Motion.STANDARD, key = "scanDim") { scanDim = it }
        anim(0f, 1f, 520, Motion.EMPHASIZED_DECEL, key = "bracket") { bracket = it }
        anim(focus, 1f, 640, Motion.EMPHASIZED, delay = 140, key = "focus") { focus = it }
        animateCam(camFor(r), r.centerX(), r.centerY(), 720, Motion.EMPHASIZED, onDone, delay = 110)
    }

    /** Several codes: outline all of them and let the user pick one. */
    fun showOverview(rects: List<RectF>, chipLabels: List<String>, onDone: () -> Unit) {
        holes = rects.map { RectF(it) }
        labels = chipLabels
        inOverview = true
        clearFound()
        anim(sweepAlpha, 0f, 260, Motion.STANDARD, key = "sweepAlpha") { sweepAlpha = it }
        anim(scanDim, 0f, 300, Motion.STANDARD, key = "scanDim") { scanDim = it }
        anim(bracketAlpha, 0f, 260, Motion.STANDARD, key = "bracketAlpha") { bracketAlpha = it }
        anim(focus, 0f, 420, Motion.EMPHASIZED, key = "focus") { focus = it }
        anim(overview, 1f, 560, Motion.EMPHASIZED, delay = 60, key = "overview") { overview = it }
        val target = overviewCam()
        val u = union(holes)
        animateCam(target, u.centerX(), u.centerY(), 640, Motion.EMPHASIZED, onDone, delay = 40)
    }

    /** From the overview, zoom into code [index]. */
    fun focusFromOverview(index: Int, onDone: () -> Unit) {
        val r = holes.getOrNull(index) ?: return
        inOverview = false
        hole = RectF(r)
        bracket = 1f
        anim(bracketAlpha, 1f, 240, Motion.STANDARD, key = "bracketAlpha") { bracketAlpha = it }
        anim(overview, 0f, 520, Motion.EMPHASIZED, key = "overview") { overview = it }
        anim(focus, 1f, 560, Motion.EMPHASIZED, key = "focus") { focus = it }
        animateCam(camFor(r), r.centerX(), r.centerY(), 660, Motion.EMPHASIZED, onDone)
    }

    /** Index of the code under (x, y) in view coordinates while in the overview, or -1. */
    fun codeAt(x: Float, y: Float): Int {
        val slop = 16f * dp
        for (i in holes.indices) {
            mapRect(paddedHole(holes[i]), tmpRect)
            tmpRect.inset(-slop, -slop)
            if (tmpRect.contains(x, y)) return i
        }
        return -1
    }

    private fun union(rs: List<RectF>): RectF {
        val u = RectF(rs[0])
        for (r in rs) u.union(r)
        return u
    }

    private fun overviewCam(): Cam {
        val u = union(holes.map { paddedHole(it) })
        val sx = width * 0.84f / u.width().coerceAtLeast(1f)
        val sy = height * 0.6f / u.height().coerceAtLeast(1f)
        val s = min(sx, sy).coerceIn(0.6f, 3f)
        val vx = width / 2f
        val vy = height * 0.5f
        return Cam(s, vx - u.centerX() * s, vy - u.centerY() * s)
    }

    /** Glide from the current QR to another one. */
    fun moveTo(r: RectF, onDone: () -> Unit) {
        val from = RectF(hole ?: r)
        val to = RectF(r)
        holeAnim?.cancel()
        holeAnim = anim(0f, 1f, 620, Motion.EMPHASIZED) { p ->
            hole = RectF(
                lerp(from.left, to.left, p), lerp(from.top, to.top, p),
                lerp(from.right, to.right, p), lerp(from.bottom, to.bottom, p)
            )
        }
        animateCam(camFor(to), to.centerX(), to.centerY(), 620, Motion.EMPHASIZED, onDone)
    }

    fun stopScanning() {
        sweepAnim?.let { a ->
            anim(sweepAlpha, 0f, 260, Motion.STANDARD, onEnd = { a.cancel() }, key = "sweepAlpha") { sweepAlpha = it }
        }
        anim(bracketAlpha, 0.35f, 300, Motion.STANDARD, key = "bracketAlpha") { bracketAlpha = it }
    }

    /** Zoom back out to the live screen and fade everything away. */
    fun exit(fast: Boolean, onDone: () -> Unit) {
        val d = if (fast) 200L else 380L
        val h = hole ?: if (holes.isNotEmpty()) union(holes) else null
        val fx = h?.centerX() ?: (bw / 2f)
        val fy = h?.centerY() ?: (bh / 2f)
        inOverview = false
        anim(focus, 0f, d, Motion.STANDARD, key = "focus") { focus = it }
        anim(overview, 0f, d, Motion.STANDARD, key = "overview") { overview = it }
        anim(bracketAlpha, 0f, d / 2, Motion.STANDARD, key = "bracketAlpha") { bracketAlpha = it }
        anim(sweepAlpha, 0f, d / 2, Motion.STANDARD, key = "sweepAlpha") { sweepAlpha = it }
        anim(scanDim, 0f, d, Motion.STANDARD, key = "scanDim") { scanDim = it }
        anim(corner, 0f, d, Motion.EMPHASIZED, key = "corner") { corner = it }
        if (fast) {
            anim(1f, 0f, d, Motion.STANDARD) { alpha = it }
        }
        animateCam(identityCam(), fx, fy, d, Motion.EMPHASIZED, onDone)
    }

    fun cancelAll() {
        for (a in ArrayList(anims)) a.cancel()
        anims.clear()
        sweepAnim?.cancel()
        pulseAnim?.cancel()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        super.onDetachedFromWindow()
    }

    /** The QR "window" in view coordinates. */
    fun holeInView(out: RectF): Boolean {
        val h = hole ?: return false
        mapRect(paddedHole(h), out)
        return true
    }

    // ---------------------------------------------------------------- animation plumbing

    private fun animateCam(target: Cam, fx: Float, fy: Float, dur: Long, interp: TimeInterpolator, onDone: (() -> Unit)?, delay: Long = 0) {
        camAnim?.cancel()
        var start = cam
        var vax = 0f
        var vay = 0f
        val vbx = fx * target.s + target.tx
        val vby = fy * target.s + target.ty
        val lnB = ln(target.s)
        var lnA = 0f
        var captured = false
        camAnim = anim(0f, 1f, dur, interp, delay = delay, onEnd = onDone) { p ->
            if (!captured) {
                // Capture the camera at the moment the animation actually starts (after delay).
                captured = true
                start = cam
                vax = fx * start.s + start.tx
                vay = fy * start.s + start.ty
                lnA = ln(start.s)
            }
            val s = exp(lnA + (lnB - lnA) * p)
            val vx = lerp(vax, vbx, p)
            val vy = lerp(vay, vby, p)
            cam = Cam(s, vx - fx * s, vy - fy * s)
        }
    }

    private fun anim(
        from: Float,
        to: Float,
        dur: Long,
        interp: TimeInterpolator,
        delay: Long = 0,
        onEnd: (() -> Unit)? = null,
        key: String? = null,
        set: (Float) -> Unit
    ): ValueAnimator {
        if (key != null) byKey.remove(key)?.cancel()
        val a = ValueAnimator.ofFloat(from, to)
        if (key != null) byKey[key] = a
        a.duration = dur
        a.startDelay = delay
        a.interpolator = interp
        a.addUpdateListener {
            set(it.animatedValue as Float)
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                anims.remove(animation)
                if (key != null && byKey[key] === animation) byKey.remove(key)
                if (!cancelled) onEnd?.invoke()
            }
        })
        anims.add(a)
        a.start()
        return a
    }

    // ---------------------------------------------------------------- drawing

    private fun paddedHole(h: RectF): RectF {
        val pad = max(h.width(), h.height()) * 0.07f + 4f * dp / max(cam.s, 0.5f)
        return RectF(h.left - pad, h.top - pad, h.right + pad, h.bottom + pad)
    }

    private fun mapRect(src: RectF, out: RectF) {
        out.set(
            src.left * cam.s + cam.tx, src.top * cam.s + cam.ty,
            src.right * cam.s + cam.tx, src.bottom * cam.s + cam.ty
        )
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = shot ?: return

        if (bgAlpha > 0f) canvas.drawColor(Color.argb((bgAlpha * 255).toInt(), 6, 7, 12))

        camMatrix.setScale(cam.s, cam.s)
        camMatrix.postTranslate(cam.tx, cam.ty)
        tmpRect.set(0f, 0f, bw, bh)
        mapRect(tmpRect, pageRect)
        val radius = 30f * dp * corner

        canvas.save()
        pagePath.reset()
        pagePath.addRoundRect(pageRect, radius, radius, Path.Direction.CW)
        canvas.clipPath(pagePath)
        canvas.drawBitmap(bmp, camMatrix, bmpPaint)

        // Dim the frozen screen so the scan stands out; the ripple darkens it further to glow.
        val dimAmount = when (animStyle) { "ripple" -> 0.52f; "radar" -> 0.55f; else -> 0.22f }
        if (scanDim > 0f) canvas.drawColor(Color.argb((scanDim * dimAmount * 255).toInt(), 2, 4, 10))

        if (sweepAlpha > 0.01f) {
            when (animStyle) {
                "ripple" -> drawRipple(canvas)
                "radar" -> drawRadar(canvas)
                else -> drawSweep(canvas)
            }
        }
        if (pendingAlpha > 0.01f && pendingRects.isNotEmpty()) drawPending(canvas)

        val h = hole
        if (focus > 0.001f && h != null) {
            mapRect(paddedHole(h), holeView)
            val hr = 20f * dp
            holePath.reset()
            holePath.addRoundRect(holeView, hr, hr, Path.Direction.CW)
            canvas.save()
            canvas.clipOutPath(holePath)
            blurShot?.let { b ->
                blurMatrix.setScale(bw / b.width, bh / b.height)
                blurMatrix.postConcat(camMatrix)
                blurPaint.alpha = (focus * 255).toInt()
                canvas.drawBitmap(b, blurMatrix, blurPaint)
            }
            canvas.drawColor(Color.argb((focus * 0.55f * 255).toInt(), 0, 0, 0))
            canvas.restore()
        }
        if (overview > 0.001f && holes.isNotEmpty()) {
            val hr = 16f * dp
            multiPath.reset()
            for (r in holes) {
                mapRect(paddedHole(r), tmpRect)
                multiPath.addRoundRect(tmpRect, hr, hr, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipOutPath(multiPath)
            val a = overview * (1f - focus)
            blurShot?.let { b ->
                blurMatrix.setScale(bw / b.width, bh / b.height)
                blurMatrix.postConcat(camMatrix)
                blurPaint.alpha = (a * 255).toInt()
                canvas.drawBitmap(b, blurMatrix, blurPaint)
            }
            canvas.drawColor(Color.argb((a * 0.55f * 255).toInt(), 0, 0, 0))
            canvas.restore()
        }
        canvas.restore()

        if (overview > 0.001f && holes.isNotEmpty()) drawOverviewMarks(canvas)
        if (focus > 0.001f && h != null) drawGlow(canvas)
        if (bracketAlpha > 0.01f) drawBrackets(canvas)

        if (flash > 0f) canvas.drawColor(Color.argb((flash * 255).toInt(), 255, 255, 255))
    }

    private val chipsPlaced = ArrayList<RectF>()

    private fun drawOverviewMarks(canvas: Canvas) {
        val hr = 16f * dp
        val a = overview
        chipsPlaced.clear()
        for (i in holes.indices) {
            mapRect(paddedHole(holes[i]), holeView)
            // glow
            glowPaint.shader = null
            glowPaint.color = Theme.ACCENT_A
            glowPaint.strokeWidth = 12f * dp
            glowPaint.alpha = (a * (0.08f + 0.10f * pulse) * 255).toInt()
            canvas.drawRoundRect(holeView, hr, hr, glowPaint)
            glowPaint.strokeWidth = 2f * dp
            glowPaint.color = Theme.ACCENT_B
            glowPaint.alpha = (a * (0.5f + 0.4f * pulse) * 255).toInt()
            canvas.drawRoundRect(holeView, hr, hr, glowPaint)
            // corner marks
            val o = 7f * dp
            frameRect.set(holeView.left - o, holeView.top - o, holeView.right + o, holeView.bottom + o)
            drawCorners(canvas, frameRect, a * (1f - focus), 3f * dp)
            // label chip (placed below, or shortened, if it would collide with another chip)
            val full = labels.getOrNull(i) ?: continue
            val padH = 10f * dp
            val ch = 24f * dp
            val gap = 8f * dp
            var text = full
            var placed = false
            for (attempt in 0 until 4) {
                if (attempt == 2) text = full.substringBefore("  \u00B7  ")
                val cw = chipText.measureText(text) + padH * 2
                val cx = (holeView.centerX() - cw / 2).coerceIn(8f * dp, width - cw - 8f * dp)
                val below = attempt % 2 == 1
                val cy = if (below) frameRect.bottom + gap else frameRect.top - ch - gap
                chipRect.set(cx, cy, cx + cw, cy + ch)
                if (cy < 8f * dp || cy + ch > height - 8f * dp) continue
                if (chipsPlaced.none { RectF.intersects(it, chipRect) }) { placed = true; break }
            }
            if (!placed) continue
            chipsPlaced.add(RectF(chipRect))
            chipPaint.color = Theme.CARD
            chipPaint.alpha = (a * (1f - focus) * 235).toInt()
            canvas.drawRoundRect(chipRect, ch / 2, ch / 2, chipPaint)
            chipText.color = Theme.ACCENT_A
            chipText.alpha = (a * (1f - focus) * 255).toInt()
            val fm = chipText.fontMetrics
            canvas.drawText(text, chipRect.left + padH, chipRect.centerY() - (fm.ascent + fm.descent) / 2, chipText)
        }
    }

    private fun drawCorners(canvas: Canvas, f: RectF, alpha: Float, stroke: Float) {
        if (alpha <= 0.01f) return
        val len = min(30f * dp, min(f.width(), f.height()) * 0.24f)
        val r = min(14f * dp, len * 0.6f)
        bracketPaint.strokeWidth = stroke
        bracketPaint.alpha = (alpha * 255).toInt()
        val l = f.left
        val t = f.top
        val rr = f.right
        val b = f.bottom
        bracketPath.reset()
        bracketPath.moveTo(l, t + len); bracketPath.lineTo(l, t + r)
        bracketPath.quadTo(l, t, l + r, t); bracketPath.lineTo(l + len, t)
        bracketPath.moveTo(rr - len, t); bracketPath.lineTo(rr - r, t)
        bracketPath.quadTo(rr, t, rr, t + r); bracketPath.lineTo(rr, t + len)
        bracketPath.moveTo(rr, b - len); bracketPath.lineTo(rr, b - r)
        bracketPath.quadTo(rr, b, rr - r, b); bracketPath.lineTo(rr - len, b)
        bracketPath.moveTo(l + len, b); bracketPath.lineTo(l + r, b)
        bracketPath.quadTo(l, b, l, b - r); bracketPath.lineTo(l, b - len)
        canvas.drawPath(bracketPath, bracketPaint)
    }

    private fun drawSweep(canvas: Canvas) {
        canvas.save()
        canvas.concat(camMatrix)
        val band = bh * 0.22f
        val y = -band * 0.2f + (bh + band * 0.4f) * sweep
        shaderMatrix.setScale(1f, band)
        shaderMatrix.postTranslate(0f, y - band)
        sweepPaint.shader?.setLocalMatrix(shaderMatrix)
        sweepPaint.alpha = (sweepAlpha * 255).toInt()
        canvas.drawRect(0f, y - band, bw, y, sweepPaint)

        val lw = 1.6f * dp / cam.s
        linePaint.alpha = (sweepAlpha * 70).toInt()
        canvas.drawRect(0f, y - lw * 4f, bw, y + lw * 4f, linePaint)
        linePaint.alpha = (sweepAlpha * 255).toInt()
        canvas.drawRect(0f, y - lw, bw, y + lw, linePaint)
        canvas.restore()
    }

    /**
     * A stone-drop ripple on a field of particles. A single wave *packet* expands from the center:
     * the leading crest (right at the wavefront) is the tallest, and each trailing ring behind it
     * is smaller — real water loses energy outward, so the first wave dominates. Particles not yet
     * reached sit still as a calm, dark water surface; the packet rides over them and lifts them as
     * it passes. Pure math, ~1000 dots, one FILL paint.
     */
    private fun drawRipple(canvas: Canvas) {
        canvas.save()
        canvas.concat(camMatrix)
        ripplePaint.style = Paint.Style.FILL
        val cx = bw / 2f
        val cy = bh / 2f
        val maxR = hypot(bw.toDouble(), bh.toDouble()).toFloat() / 2f
        val sp = bw / 28f                 // grid spacing (bitmap px) — finer water
        val amp = sp * 1.6f               // max radial displacement
        val twoPi = (Math.PI * 2).toFloat()
        val front = sweep * 1.30f         // expanding wavefront position, in normalized-radius units
        val waveK = 3.0f                  // number of rings in the trailing packet
        val trailDecay = 1.9f             // how fast trailing rings shrink (bigger -> first wave dominates more)
        val life = 1f - smooth(0.95f, 1.30f, front)   // fade the packet out as it leaves the screen, for a clean loop
        var y = sp * 0.5f
        while (y < bh) {
            var x = sp * 0.5f
            while (x < bw) {
                val dx = x - cx
                val dy = y - cy
                val d = hypot(dx.toDouble(), dy.toDouble()).toFloat() + 0.001f
                val nd = d / maxR
                val rel = front - nd      // how far behind the wavefront this particle is (>0 = already reached)
                var drawn = false
                if (rel > 0f && rel < 1.6f) {
                    val trail = exp(-rel.toDouble() * trailDecay).toFloat()  // 1 at the front, decaying inward
                    val lead = smooth(0f, 0.04f, rel)                        // soften the very leading edge
                    val osc = kotlin.math.cos((rel * waveK * twoPi).toDouble()).toFloat()  // crest sits exactly at the front
                    val distFade = (1f - nd * 0.30f).coerceIn(0f, 1f)
                    val height = osc * trail * lead * life * distFade
                    if (abs(height) > 0.03f) {
                        val crest = max(0f, height)
                        val bright = min(1f, Math.pow(crest.toDouble(), 1.2).toFloat() * 1.25f)
                        val disp = height * amp
                        val ux = dx / d
                        val uy = dy / d
                        val px = x + ux * disp
                        val py = y + uy * disp
                        val rad = sp * (0.07f + bright * 0.30f)
                        val col = lerpColor(Theme.ACCENT_A, Theme.ACCENT_B, (0.05f + nd * 0.85f).coerceIn(0f, 1f))
                        if (bright > 0.06f) {
                            ripplePaint.color = col
                            ripplePaint.alpha = (sweepAlpha * bright * 0.14f * 255f).toInt().coerceIn(0, 255)
                            canvas.drawCircle(px, py, rad * 3.4f, ripplePaint)
                            ripplePaint.alpha = (sweepAlpha * bright * 0.30f * 255f).toInt().coerceIn(0, 255)
                            canvas.drawCircle(px, py, rad * 1.9f, ripplePaint)
                        }
                        ripplePaint.color = col
                        ripplePaint.alpha = (sweepAlpha * (0.10f + bright * 0.90f) * 255f).toInt().coerceIn(0, 255)
                        canvas.drawCircle(px, py, rad, ripplePaint)
                        if (bright > 0.45f) {
                            ripplePaint.color = CORE
                            ripplePaint.alpha = (sweepAlpha * 235f * min(1f, (bright - 0.45f) * 3f)).toInt().coerceIn(0, 255)
                            canvas.drawCircle(px, py, rad * 0.55f, ripplePaint)
                        }
                        drawn = true
                    }
                }
                if (!drawn) {
                    // calm water: a dim resting dot so the surface reads even before the wave arrives
                    ripplePaint.color = lerpColor(Theme.ACCENT_A, Theme.ACCENT_B, nd.coerceIn(0f, 1f))
                    ripplePaint.alpha = (sweepAlpha * 0.09f * 255f).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x, y, sp * 0.07f, ripplePaint)
                }
                x += sp
            }
            y += sp
        }
        // the drop splash at the center — a bright flash at the instant the wave is born
        val birth = 1f - smooth(0f, 0.22f, front)
        ripplePaint.color = Theme.ACCENT_A
        ripplePaint.alpha = (sweepAlpha * (0.10f + 0.34f * birth + 0.10f * pulse) * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, sp * (0.9f + 1.6f * birth), ripplePaint)
        ripplePaint.color = CORE
        ripplePaint.alpha = (sweepAlpha * (0.45f + 0.55f * birth) * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, sp * (0.18f + 0.36f * birth), ripplePaint)
        canvas.restore()
    }

    /** Smoothstep (Hermite) from 0 at [e0] to 1 at [e1]. */
    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // nd (fraction of max radius), angle (radians) for the radar's "detections"
    private val radarBlips = floatArrayOf(
        0.60f, 0.80f,
        0.42f, 2.75f,
        0.80f, 4.05f,
        0.33f, 5.30f,
        0.68f, 3.55f,
        0.52f, 1.70f,
        0.25f, 0.30f,
        0.74f, 5.90f
    )
    private var radarT0 = 0L
    private val radarOval = RectF()
    private val radarPeriod = 2.4f   // seconds per full rotation (calm, deliberate)

    /**
     * A sonar/radar sweep: a beam rotating from the center at a steady pace over faint range rings,
     * leaving a phosphor trail behind it, with "detections" that flash as the beam passes and then
     * fade — exactly the metaphor of a scanner searching the frozen screen for a code.
     */
    private fun drawRadar(canvas: Canvas) {
        canvas.save()
        canvas.concat(camMatrix)
        val cx = bw / 2f
        val cy = bh / 2f
        val maxR = hypot(bw.toDouble(), bh.toDouble()).toFloat() / 2f
        val a = sweepAlpha
        val twoPi = (Math.PI * 2).toFloat()
        if (radarT0 == 0L) radarT0 = System.nanoTime()
        val t = ((System.nanoTime() - radarT0) / 1_000_000_000.0).toFloat()
        val ang = (t / radarPeriod) * twoPi % twoPi      // steady linear rotation, independent of easing
        val angDeg = Math.toDegrees(ang.toDouble()).toFloat()
        val px1 = dp / cam.s                             // 1dp in bitmap space
        val p = ripplePaint

        // phosphor wedge: nested pie slices that all end at the beam, so overlap builds a smooth
        // falloff (bright at the beam, fading ~80° behind it) with no seams. Violet first, cyan on top.
        p.style = Paint.Style.FILL
        radarOval.set(cx - maxR, cy - maxR, cx + maxR, cy + maxR)
        val n = 40
        for (k in n downTo 1) {
            val ext = 80f * k / n
            p.color = lerpColor(Theme.ACCENT_A, Theme.ACCENT_B, k.toFloat() / n)
            p.alpha = (a * 0.013f * 255f).toInt().coerceIn(0, 255)
            canvas.drawArc(radarOval, angDeg - ext, ext, true, p)
        }

        // range rings + crosshair (the scope grid)
        p.style = Paint.Style.STROKE
        p.color = Theme.ACCENT_A
        p.strokeWidth = px1
        p.alpha = (a * 0.12f * 255f).toInt().coerceIn(0, 255)
        for (ring in 1..3) canvas.drawCircle(cx, cy, maxR * ring / 4f, p)
        p.alpha = (a * 0.065f * 255f).toInt().coerceIn(0, 255)
        canvas.drawLine(cx - maxR, cy, cx + maxR, cy, p)
        canvas.drawLine(cx, cy - maxR, cx, cy + maxR, p)

        // the beam: soft glow, inner glow, white-hot core
        val bx = cx + kotlin.math.cos(ang.toDouble()).toFloat() * maxR
        val by = cy + kotlin.math.sin(ang.toDouble()).toFloat() * maxR
        p.strokeCap = Paint.Cap.ROUND
        p.color = Theme.ACCENT_A
        p.strokeWidth = 8f * px1; p.alpha = (a * 0.16f * 255f).toInt().coerceIn(0, 255)
        canvas.drawLine(cx, cy, bx, by, p)
        p.strokeWidth = 4f * px1; p.alpha = (a * 0.35f * 255f).toInt().coerceIn(0, 255)
        canvas.drawLine(cx, cy, bx, by, p)
        p.color = CORE
        p.strokeWidth = 2f * px1; p.alpha = (a * 0.94f * 255f).toInt().coerceIn(0, 255)
        canvas.drawLine(cx, cy, bx, by, p)
        p.strokeCap = Paint.Cap.BUTT

        // detections: light up as the beam passes, emit a ping ring, then fade like phosphor
        var bi = 0
        while (bi < radarBlips.size) {
            val nd = radarBlips[bi] * 0.85f
            val ba = radarBlips[bi + 1]
            bi += 2
            val behind = (((ang - ba) % twoPi) + twoPi) % twoPi   // 0 right after the beam passes
            val bright = exp(-behind.toDouble() * 1.6).toFloat()
            if (bright > 0.03f) {
                val r = maxR * nd
                val qx = cx + kotlin.math.cos(ba.toDouble()).toFloat() * r
                val qy = cy + kotlin.math.sin(ba.toDouble()).toFloat() * r
                val dot = 3.3f * px1
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f * px1
                p.color = Theme.ACCENT_A
                p.alpha = (a * bright * 0.55f * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(qx, qy, dot * (2f + 9f * (1f - bright)), p)   // expanding ping
                p.style = Paint.Style.FILL
                p.alpha = (a * bright * 0.25f * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(qx, qy, dot * 3f, p)
                p.color = CORE
                p.alpha = (a * bright * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(qx, qy, dot, p)
            }
        }

        // center hub
        p.style = Paint.Style.FILL
        p.color = Theme.ACCENT_A
        p.alpha = (a * (0.24f + 0.12f * pulse) * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, 6f * px1 * (1f + 0.2f * pulse), p)
        p.color = CORE
        p.alpha = (a * 245f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, 2.4f * px1, p)
        canvas.restore()
    }

    /**
     * A barely-there marker over codes found while the animation finishes: a faint tint and a thin,
     * softly pulsing outline. The scan effect stays fully visible through it.
     */
    private fun drawPending(canvas: Canvas) {
        val hr = 16f * dp
        val a = pendingAlpha
        fillPaint.shader = null
        glowPaint.shader = null
        for (r in pendingRects) {
            mapRect(paddedHole(r), holeView)
            fillPaint.color = withAlpha(Theme.ACCENT_A, a * (0.04f + 0.03f * pulse))
            canvas.drawRoundRect(holeView, hr, hr, fillPaint)
            glowPaint.color = Theme.ACCENT_A
            glowPaint.strokeWidth = 8f * dp
            glowPaint.alpha = (a * (0.04f + 0.05f * pulse) * 255).toInt()
            canvas.drawRoundRect(holeView, hr, hr, glowPaint)
            glowPaint.strokeWidth = 1.2f * dp
            glowPaint.alpha = (a * (0.28f + 0.22f * pulse) * 255).toInt()
            canvas.drawRoundRect(holeView, hr, hr, glowPaint)
        }
    }

    private val CORE = Color.rgb(0xEA, 0xFB, 0xFF)

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ar = (a ushr 16) and 0xFF; val ag = (a ushr 8) and 0xFF; val ab = a and 0xFF
        val br = (b ushr 16) and 0xFF; val bg = (b ushr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt()
        val g = (ag + (bg - ag) * t).toInt()
        val bl = (ab + (bb - ab) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun drawGlow(canvas: Canvas) {
        val h = hole ?: return
        mapRect(paddedHole(h), holeView)
        val hr = 20f * dp
        glowPaint.shader = null
        glowPaint.color = Theme.ACCENT_A
        glowPaint.strokeWidth = 14f * dp
        glowPaint.alpha = (focus * (0.08f + 0.10f * pulse) * 255).toInt()
        canvas.drawRoundRect(holeView, hr, hr, glowPaint)
        glowPaint.strokeWidth = 2f * dp
        glowPaint.color = Theme.ACCENT_B
        glowPaint.alpha = (focus * (0.45f + 0.4f * pulse) * 255).toInt()
        canvas.drawRoundRect(holeView, hr, hr, glowPaint)
    }

    private fun drawBrackets(canvas: Canvas) {
        // Start frame: inset of the screenshot card (breathing slightly while scanning).
        val inset = (18f + 6f * breathe * (1f - bracket)) * dp
        val sx1 = pageRect.left + inset
        val sy1 = pageRect.top + inset
        val sx2 = pageRect.right - inset
        val sy2 = pageRect.bottom - inset
        val h = hole
        if (h != null && bracket > 0f) {
            mapRect(paddedHole(h), tmpRect)
            val o = 10f * dp
            frameRect.set(
                lerp(sx1, tmpRect.left - o, bracket), lerp(sy1, tmpRect.top - o, bracket),
                lerp(sx2, tmpRect.right + o, bracket), lerp(sy2, tmpRect.bottom + o, bracket)
            )
        } else {
            frameRect.set(sx1, sy1, sx2, sy2)
        }
        val len = min(30f * dp, min(frameRect.width(), frameRect.height()) * 0.24f)
        val r = min(14f * dp, len * 0.6f)
        bracketPaint.strokeWidth = 4f * dp
        bracketPaint.alpha = (bracketAlpha * 255).toInt()
        val l = frameRect.left
        val t = frameRect.top
        val rr = frameRect.right
        val b = frameRect.bottom
        bracketPath.reset()
        // top-left
        bracketPath.moveTo(l, t + len); bracketPath.lineTo(l, t + r)
        bracketPath.quadTo(l, t, l + r, t); bracketPath.lineTo(l + len, t)
        // top-right
        bracketPath.moveTo(rr - len, t); bracketPath.lineTo(rr - r, t)
        bracketPath.quadTo(rr, t, rr, t + r); bracketPath.lineTo(rr, t + len)
        // bottom-right
        bracketPath.moveTo(rr, b - len); bracketPath.lineTo(rr, b - r)
        bracketPath.quadTo(rr, b, rr - r, b); bracketPath.lineTo(rr - len, b)
        // bottom-left
        bracketPath.moveTo(l + len, b); bracketPath.lineTo(l + r, b)
        bracketPath.quadTo(l, b, l, b - r); bracketPath.lineTo(l, b - len)
        canvas.drawPath(bracketPath, bracketPaint)
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop) {
                    if (inOverview) {
                        val i = codeAt(event.x, event.y)
                        if (i >= 0) listener?.onCodeTap(i) else listener?.onBackgroundTap()
                        return true
                    }
                    val inHole = focus > 0.6f && holeInView(tmpRect) && tmpRect.contains(event.x, event.y)
                    if (inHole) listener?.onHoleTap() else listener?.onBackgroundTap()
                }
            }
        }
        return true
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun withAlpha(c: Int, a: Float) =
        Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    @Suppress("unused")
    private val linear = LinearInterpolator()
}

private const val SWEEP_MS = 1150L
private const val SWEEP_DELAY = 60L

object Motion {
    val STANDARD: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val EMPHASIZED: TimeInterpolator = PathInterpolator(0.3f, 0f, 0f, 1f)
    val EMPHASIZED_DECEL: TimeInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
}

object Theme {
    val ACCENT_A = Color.rgb(0x6E, 0xE7, 0xF9)   // cyan
    val ACCENT_B = Color.rgb(0xA7, 0x8B, 0xFA)   // violet
    val BG = Color.rgb(0x0A, 0x0B, 0x12)
    val CARD_BG = Color.rgb(0x15, 0x16, 0x20)
    val DANGER = Color.rgb(0xF8, 0x71, 0x71)
    val CAUTION = Color.rgb(0xFB, 0xBF, 0x24)
    val OK = Color.rgb(0x4A, 0xDE, 0x80)
    val CARD = Color.argb(0xEB, 0x16, 0x17, 0x1F)
    val CARD_STROKE = Color.argb(0x26, 0xFF, 0xFF, 0xFF)
    val TEXT = Color.WHITE
    val TEXT_2 = Color.argb(0xA8, 0xFF, 0xFF, 0xFF)
}
