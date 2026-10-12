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

    // Overview of several codes
    private var overview = 0f
    private var holes: List<RectF> = emptyList()
    private var labels: List<String> = emptyList()
    var inOverview = false
        private set

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
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
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
        anim(0f, 1f, 220, Motion.STANDARD, key = "bgAlpha") { bgAlpha = it }
        anim(0f, 1f, 420, Motion.EMPHASIZED_DECEL, key = "corner") { corner = it }
        anim(0.55f, 0f, 380, Motion.STANDARD) { flash = it }
        anim(0f, 1f, 360, Motion.STANDARD, delay = 120, key = "scanDim") { scanDim = it }
        anim(0f, 1f, 320, Motion.STANDARD, delay = 160, key = "bracketAlpha") { bracketAlpha = it }
        anim(0f, 1f, 260, Motion.STANDARD, delay = 80, key = "sweepAlpha") { sweepAlpha = it }
        animateCam(introCam(), bw / 2f, bh / 2f, 460, Motion.EMPHASIZED_DECEL, null)

        sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1150
            startDelay = 60
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

    /** Lock the brackets onto [r] (bitmap coords) and zoom into it. */
    fun focusOn(r: RectF, onDone: () -> Unit) {
        hole = RectF(r)
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

        if (scanDim > 0f) canvas.drawColor(Color.argb((scanDim * 0.22f * 255).toInt(), 0, 0, 0))

        if (sweepAlpha > 0.01f) drawSweep(canvas)

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

object Motion {
    val STANDARD: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val EMPHASIZED: TimeInterpolator = PathInterpolator(0.3f, 0f, 0f, 1f)
    val EMPHASIZED_DECEL: TimeInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
}

object Theme {
    val ACCENT_A = Color.rgb(0x6E, 0xE7, 0xF9)   // cyan
    val ACCENT_B = Color.rgb(0xA7, 0x8B, 0xFA)   // violet
    val CARD = Color.argb(0xEB, 0x16, 0x17, 0x1F)
    val CARD_STROKE = Color.argb(0x26, 0xFF, 0xFF, 0xFF)
    val TEXT = Color.WHITE
    val TEXT_2 = Color.argb(0xA8, 0xFF, 0xFF, 0xFF)
}
