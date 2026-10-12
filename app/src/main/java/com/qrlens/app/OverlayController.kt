package com.qrlens.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.view.animation.LinearInterpolator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Owns the full-screen overlay window: detection, animations, card and actions. */
class OverlayController(private val svc: QrLensService) : ScanView.Listener {

    private data class Found(val rect: RectF, val content: QrContent)

    private val ctx: Context = svc
    private val wm: WindowManager = svc.getSystemService(WindowManager::class.java)
    private val dp = ctx.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())

    private var root: OverlayRoot? = null
    private var scan: ScanView? = null
    private var card: View? = null
    private var bar: View? = null
    private var pill: View? = null
    private var topPill: View? = null
    private var picking = false
    private var session = 0
    private var found: List<Found> = emptyList()
    private var index = 0
    private var ready = false
    private var closing = false
    private var closingSince = 0L
    private var shownAt = 0L
    /** Separate handler so removeNow() / animation cancels can't clear the close failsafe. */
    private val watchdog = Handler(Looper.getMainLooper())
    private var autoAnim: ValueAnimator? = null

    val isShowing: Boolean get() = root != null

    // ---------------------------------------------------------------- lifecycle

    fun show(bmp: Bitmap) {
        removeNow()
        val id = ++session
        closing = false
        ready = false
        picking = false
        found = emptyList()
        index = 0

        val r = OverlayRoot(ctx) { back() }
        val sv = ScanView(ctx)
        sv.listener = this
        r.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.fitInsetsTypes = 0
        lp.windowAnimations = 0
        lp.title = "QR Lens"

        try {
            wm.addView(r, lp)
        } catch (e: Exception) {
            return
        }
        root = r
        scan = sv
        shownAt = SystemClock.uptimeMillis()

        // Blur is tiny (screenshot / 14) so this costs a few ms.
        val blurred = Blur.make(bmp)
        sv.start(bmp, blurred)

        val t0 = SystemClock.uptimeMillis()
        Thread {
            val hits = try { QrDetector.detect(bmp) } catch (t: Throwable) { emptyList() }
            main.post { deliver(id, t0, hits) }
        }.apply { name = "qr-detect"; priority = Thread.MAX_PRIORITY }.start()
    }

    private fun deliver(id: Int, t0: Long, hits: List<QrDetector.Hit>) {
        if (id != session || root == null) return
        val items = hits.map { Found(it.rect, QrParser.parse(it.text)) }
            .sortedByDescending { it.rect.width() * it.rect.height() }
        // Let the scan animation breathe for a moment so the transition never feels abrupt.
        val wait = max(0L, MIN_SCAN_MS - (SystemClock.uptimeMillis() - t0))
        main.postDelayed({
            if (id != session || root == null || closing) return@postDelayed
            found = items
            when {
                items.isEmpty() -> noneFound()
                items.size == 1 -> focus(0, first = true)
                else -> overview()
            }
        }, wait)
    }

    fun dismiss(fast: Boolean = false, after: (() -> Unit)? = null) {
        val sv = scan
        if (sv == null || closing) {
            if (sv == null) after?.invoke()
            return
        }
        closing = true
        closingSince = SystemClock.uptimeMillis()
        ready = false
        picking = false
        autoAnim?.cancel()
        hideFloating(if (fast) 90 else 160)
        val mySession = session
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                watchdog.removeCallbacksAndMessages(null)
                if (session == mySession) removeNow()
                after?.invoke()
            }
        }
        sv.exit(fast) { finish() }
        // Failsafe: if the exit animation is ever interrupted, close anyway.
        watchdog.postDelayed({ finish() }, (if (fast) 200L else 380L) + 700L)
    }

    /**
     * True if the overlay claims to be open but is really dead (closing for too long or no
     * longer attached). The key handler uses this to self-heal instead of passing keys through.
     */
    fun isStuck(): Boolean {
        val r = root ?: return false
        if (!r.isAttachedToWindow && SystemClock.uptimeMillis() - shownAt > 1500) return true
        return closing && SystemClock.uptimeMillis() - closingSince > 1500
    }

    fun removeNow() {
        session++
        watchdog.removeCallbacksAndMessages(null)
        ready = false
        autoAnim?.cancel()
        autoAnim = null
        main.removeCallbacksAndMessages(null)
        scan?.cancelAll()
        root?.let { r ->
            try {
                wm.removeViewImmediate(r)
            } catch (_: Exception) {
            }
        }
        root = null
        scan = null
        card = null
        bar = null
        pill = null
        topPill = null
        picking = false
        closing = false
    }

    // ---------------------------------------------------------------- flow

    private fun focus(i: Int, first: Boolean) {
        val sv = scan ?: return
        index = i
        ready = false
        val f = found[i]
        hideCard()
        val done = {
            if (!closing && root != null) {
                ready = true
                svc.haptic(VibrationEffect.EFFECT_TICK)
                showCard(f.content)
                showBar(f.content)
            }
        }
        if (first) sv.focusOn(f.rect, done) else sv.moveTo(f.rect, done)
        if (first) main.postDelayed({ svc.haptic(VibrationEffect.EFFECT_CLICK) }, 260)
    }

    /** Two or more codes: show them all and let the user pick. */
    private fun overview() {
        val sv = scan ?: return
        if (closing) return
        ready = false
        picking = false
        hideCard()
        removeBar()
        val chips = found.map { chipLabel(it.content) }
        sv.showOverview(found.map { it.rect }, chips) {
            if (closing || root == null) return@showOverview
            picking = true
            svc.haptic(VibrationEffect.EFFECT_CLICK)
            showTopPill("${found.size} QR codes \u00B7 tap one")
            showOverviewBar()
        }
    }

    override fun onCodeTap(index: Int) {
        val sv = scan ?: return
        if (!picking || closing) return
        picking = false
        this.index = index
        hideTopPill()
        removeBar()
        svc.haptic(VibrationEffect.EFFECT_TICK)
        val f = found.getOrNull(index) ?: return
        sv.focusFromOverview(index) {
            if (!closing && root != null) {
                ready = true
                showCard(f.content)
                showBar(f.content)
            }
        }
    }

    /** Back key / tap outside: from a zoomed code go back to the overview, otherwise close. */
    fun back() {
        if (closing || scan == null) return
        if (found.size > 1 && ready && scan?.inOverview == false) overview() else dismiss()
    }

    private fun chipLabel(c: QrContent): String {
        val t = c.title.let { if (it.length > 18) it.take(17) + "\u2026" else it }
        return if (t.isBlank() || t == c.label) c.label else "${c.label}  \u00B7  $t"
    }

    private fun showTopPill(msg: String) {
        val r = root ?: return
        hideTopPill()
        val p = TextView(ctx).apply {
            text = msg
            setTextColor(Theme.TEXT)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
            background = glass(100f * dp)
        }
        val (top, _) = insets(r)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        lp.topMargin = top + (14 * dp).toInt()
        r.addView(p, lp)
        topPill = p
        p.alpha = 0f
        p.translationY = -16f * dp
        p.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(OvershootInterpolator(1.1f)).start()
    }

    private fun hideTopPill() {
        val r = root ?: return
        topPill?.let { v ->
            topPill = null
            v.animate().alpha(0f).setDuration(140).withEndAction { r.removeView(v) }.start()
        }
    }

    private fun removeBar() {
        val r = root ?: return
        bar?.let { v ->
            bar = null
            v.animate().alpha(0f).translationY(20f * dp).setStartDelay(0).setDuration(150)
                .withEndAction { r.removeView(v) }.start()
        }
    }

    private fun showOverviewBar() {
        val r = root ?: return
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        row.addView(pillButton("Done") { dismiss() })
        attachBar(r, row)
    }

    private fun noneFound() {
        scan?.stopScanning()
        svc.haptic(VibrationEffect.EFFECT_DOUBLE_CLICK)
        val r = root ?: return
        val p = TextView(ctx).apply {
            text = "No QR code on screen"
            setTextColor(Theme.TEXT)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            background = glass(100f * dp)
        }
        r.addView(p, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        pill = p
        p.alpha = 0f; p.scaleX = 0.9f; p.scaleY = 0.9f
        p.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.4f)).start()
        main.postDelayed({ dismiss() }, 1500)
    }

    override fun onHoleTap() {
        if (ready && !closing) primary(found.getOrNull(index)?.content ?: return)
    }

    override fun onBackgroundTap() {
        if (closing) return
        if (picking || ready || found.isEmpty()) back()
    }

    private fun primary(c: QrContent) {
        autoAnim?.cancel()
        val w = c.wifi
        if (w != null) dismiss(fast = true) { QrActions.connectWifi(svc, w) }
        else dismiss(fast = true) { QrActions.open(svc, c) }
    }

    /** Wi-Fi: the Connect pill fills up, then connects on its own (cancelled by Done / tap outside / Next). */
    private fun startAutoConnect(btn: TextView, fill: ClipDrawable, c: QrContent) {
        autoAnim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = AUTO_CONNECT_MS
        a.startDelay = 380
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            val p = it.animatedValue as Float
            fill.level = (p * 10000).toInt()
            btn.setTextColor(if (p > 0.55f) 0xFF0B0C14.toInt() else Theme.TEXT)
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
                fill.level = 0
                btn.setTextColor(Theme.TEXT)
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled && !closing && root != null && card === btn.parent) primary(c)
            }
        })
        autoAnim = a
        a.start()
    }

    // ---------------------------------------------------------------- card

    @SuppressLint("SetTextI18n")
    private fun showCard(c: QrContent) {
        val r = root ?: return
        val sv = scan ?: return

        val cardView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(24f * dp)
            elevation = 12f * dp
            val p = (16 * dp).toInt()
            setPadding(p, p, (12 * dp).toInt(), p)
        }

        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(ctx).apply {
            text = c.label
            setTextColor(Theme.ACCENT_A)
            textSize = 11f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val title = TextView(ctx).apply {
            text = c.title
            setTextColor(Theme.TEXT)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (3 * dp).toInt(), 0, (2 * dp).toInt())
        }
        val detail = TextView(ctx).apply {
            text = c.detail
            setTextColor(Theme.TEXT_2)
            textSize = 13f
            maxLines = if (c.isLink) 2 else 3
            ellipsize = TextUtils.TruncateAt.END
            if (c.kind == Kind.LINK) typeface = Typeface.MONOSPACE
        }
        texts.addView(label)
        texts.addView(title)
        if (c.detail.isNotBlank() && c.detail != c.title) texts.addView(detail)

        var wifiFill: ClipDrawable? = null
        val open = if (c.wifi != null) {
            pillButton("Connect") { primary(c) }.also { b ->
                // Glass pill with a gradient fill that sweeps across as a countdown.
                val radius = 100f * dp
                val grad = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Theme.ACCENT_A, Theme.ACCENT_B))
                    .apply { cornerRadius = radius }
                val fill = ClipDrawable(grad, Gravity.START, ClipDrawable.HORIZONTAL)
                val mask = GradientDrawable().apply { cornerRadius = radius; setColor(0xFFFFFFFF.toInt()) }
                b.background = RippleDrawable(
                    ColorStateList.valueOf(0x40FFFFFF),
                    LayerDrawable(arrayOf(glass(radius), fill)),
                    mask
                )
                val h = (22 * dp).toInt()
                val v = (12 * dp).toInt()
                b.setPadding(h, v, h, v)
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                wifiFill = fill
            }
        } else {
            pillButton("Open", primary = true) { primary(c) }
        }
        cardView.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cardView.addView(open, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = (12 * dp).toInt() })

        val w = min(r.width - (32 * dp).toInt(), (440 * dp).toInt())
        cardView.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = cardView.measuredHeight
        val gap = (18 * dp).toInt()
        val (topInset, bottomInset) = insets(r)
        val barSpace = (96 * dp).toInt() + bottomInset
        val hole = RectF()
        sv.holeInView(hole)

        val above = hole.top.toInt() - gap - h >= topInset + (8 * dp).toInt()
        val y = when {
            above -> hole.top.toInt() - gap - h
            hole.bottom.toInt() + gap + h <= r.height - barSpace -> hole.bottom.toInt() + gap
            else -> topInset + (12 * dp).toInt()
        }

        val lp = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        lp.topMargin = y
        r.addView(cardView, lp)
        card = cardView

        cardView.alpha = 0f
        cardView.translationY = (if (above) 22f else -22f) * dp
        cardView.scaleX = 0.94f
        cardView.scaleY = 0.94f
        cardView.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(460)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()

        wifiFill?.let { startAutoConnect(open, it, c) }
    }

    private fun showBar(c: QrContent) {
        val r = root ?: return
        bar?.let { old -> bar = null; r.removeView(old) }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun add(v: View) {
            row.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (5 * dp).toInt(); rightMargin = (5 * dp).toInt() })
        }
        add(pillButton("Copy") {
            QrActions.copy(ctx, c.raw)
            svc.haptic(VibrationEffect.EFFECT_TICK)
            flashLabel(it as TextView, "Copied")
        })
        add(pillButton("Share") { dismiss(fast = true) { QrActions.share(svc, c.raw) } })
        if (c.wifi != null) {
            add(pillButton("Details") { autoAnim?.cancel(); dismiss(fast = true) { QrActions.openAsFile(svc, c) } })
        }
        if (found.size > 1) {
            add(pillButton("All ${found.size}") { if (!closing) { autoAnim?.cancel(); overview() } })
        }
        add(pillButton("Done") { dismiss() })

        attachBar(r, row)
    }

    /** Bottom action bar; scrolls sideways if the buttons don't fit on narrow screens. */
    private fun attachBar(r: FrameLayout, row: LinearLayout) {
        val scroller = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            val p = (8 * dp).toInt()
            setPadding(p, 0, p, 0)
            addView(row)
        }
        val (_, bottomInset) = insets(r)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        lp.bottomMargin = bottomInset + (24 * dp).toInt()
        r.addView(scroller, lp)
        bar = scroller

        scroller.alpha = 0f
        scroller.translationY = 40f * dp
        scroller.animate().alpha(1f).translationY(0f).setStartDelay(70).setDuration(420)
            .setInterpolator(OvershootInterpolator(1.0f)).start()
    }

    private fun hideCard() {
        autoAnim?.cancel()
        val r = root ?: return
        card?.let { v ->
            card = null
            v.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(140)
                .withEndAction { r.removeView(v) }.start()
        }
    }

    private fun hideFloating(d: Long) {
        listOfNotNull(card, bar, pill, topPill).forEach { v ->
            v.animate().cancel()
            v.animate().alpha(0f).setStartDelay(0).setDuration(d).start()
        }
    }

    private fun flashLabel(tv: TextView, msg: String) {
        val old = tv.text
        tv.text = msg
        main.postDelayed({ if (tv.isAttachedToWindow) tv.text = old }, 1200)
    }

    // ---------------------------------------------------------------- ui helpers

    private fun insets(r: View): Pair<Int, Int> {
        val wi = r.rootWindowInsets ?: return Pair((32 * dp).toInt(), (24 * dp).toInt())
        val i = wi.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Pair(i.top, i.bottom)
    }

    private fun glass(radius: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Theme.CARD)
        setStroke(max(1, (1 * dp).toInt()), Theme.CARD_STROKE)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun pillButton(text: String, primary: Boolean = false, onClick: (View) -> Unit): TextView {
        val radius = 100f * dp
        val bg = if (primary) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Theme.ACCENT_A, Theme.ACCENT_B))
                .apply { cornerRadius = radius }
        } else glass(radius)
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(0xFFFFFFFF.toInt()) }
        return TextView(ctx).apply {
            this.text = text
            setTextColor(if (primary) 0xFF0B0C14.toInt() else Theme.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (primary) 15f else 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            val h = ((if (primary) 22 else 18) * dp).toInt()
            val v = ((if (primary) 12 else 11) * dp).toInt()
            setPadding(h, v, h, v)
            background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, mask)
            isClickable = true
            setOnClickListener { onClick(it) }
            // Tactile press: quick scale-down and spring back.
            setOnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).setInterpolator(Motion.STANDARD).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(OvershootInterpolator(2.5f)).start()
                }
                false
            }
        }
    }

    companion object {
        private const val MIN_SCAN_MS = 480L
        private const val AUTO_CONNECT_MS = 1600L
    }
}

/** Root of the overlay window; turns the Back key into "dismiss". */
class OverlayRoot(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
