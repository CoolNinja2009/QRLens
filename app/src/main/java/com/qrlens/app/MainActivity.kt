package com.qrlens.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The app shell. Two sibling sections, History (default) and Generate, swapped under a persistent
 * bottom ribbon that hides while scrolling down. Tapping a history item opens an immersive,
 * gallery-style QR viewer that expands from the tapped thumbnail.
 */
class MainActivity : Activity() {

    private val d by lazy { Ui.dp(this) }
    private lateinit var root: FrameLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var ribbon: LinearLayout
    private var topInset = 0
    private var bottomInset = 0
    private val ribbonH get() = (64 * d).toInt() + bottomInset

    private var section = "history"
    private var ribbonHidden = false
    private var detail: View? = null

    private lateinit var histItem: View
    private lateinit var genItem: View
    private var historyPage: View? = null
    private var generatePage: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG

        root = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        pageHost = FrameLayout(this)
        root.addView(pageHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ribbon = buildRibbon()
        root.addView(ribbon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            topInset = bars.top
            bottomInset = bars.bottom
            ribbon.setPadding(0, 0, 0, bottomInset)
            ribbon.layoutParams.height = ribbonH
            ribbon.requestLayout()
            applyPageInsets()
            insets
        }
        setContentView(root)

        val toGenerate = intent?.action == Intent.ACTION_SEND
        showSection(if (toGenerate) "generate" else "history", animate = false)
    }

    override fun onResume() {
        super.onResume()
        if (section == "history" && detail == null) refreshHistory()
    }

    override fun onBackPressed() {
        when {
            detail != null -> closeDetail()
            section != "history" -> showSection("history", animate = true)
            else -> super.onBackPressed()
        }
    }

    private fun applyPageInsets() {
        val pad = ribbonH + (8 * d).toInt()
        // Pad every page present, not just the first: during a section transition both the
        // incoming and outgoing pages live in the host, and findViewWithTag would only reach one.
        for (i in 0 until pageHost.childCount) {
            (pageHost.getChildAt(i).findViewWithTag<View>("scroll") as? ScrollView)
                ?.setPadding(0, topInset, 0, pad)
        }
    }

    // ---------------------------------------------------------------- section switching

    private fun showSection(name: String, animate: Boolean) {
        if (detail != null) closeDetail(instant = true)
        val incoming = when (name) {
            "generate" -> buildGeneratePage().also { generatePage = it }
            else -> buildHistoryPage().also { historyPage = it }
        }
        val outgoing = pageHost.getChildAt(0)
        pageHost.addView(incoming, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyPageInsets()
        setRibbonVisible(true, animate = false)

        if (animate && outgoing != null) {
            val dx = if (name == "generate") 1 else -1
            incoming.translationX = dx * 40f * d
            incoming.alpha = 0f
            incoming.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            outgoing.animate().translationX(-dx * 40f * d).alpha(0f).setDuration(200).withEndAction {
                pageHost.removeView(outgoing)
            }.start()
        } else if (outgoing != null) {
            pageHost.removeView(outgoing)
        }
        section = name
        refreshRibbonSelection(name)
    }

    private fun refreshHistory() {
        val fresh = buildHistoryPage()
        val old = pageHost.getChildAt(0)
        historyPage = fresh
        pageHost.addView(fresh)
        applyPageInsets()
        if (old != null && old !== fresh) pageHost.removeView(old)
    }

    // ---------------------------------------------------------------- ribbon

    private fun buildRibbon(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Theme.CARD_BG)
                cornerRadii = floatArrayOf(22 * d, 22 * d, 22 * d, 22 * d, 0f, 0f, 0f, 0f)
            }
            elevation = 14 * d
        }
        histItem = ribbonItem(Icons.history(this, 22f), "History") { if (section != "history") showSection("history", true) }
        genItem = ribbonItem(Icons.plus(this, 22f), "Generate") { if (section != "generate") showSection("generate", true) }
        bar.addView(histItem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(genItem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return bar
    }

    private fun ribbonItem(icon: android.graphics.Bitmap, label: String, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            val mask = GradientDrawable().apply { cornerRadius = 100 * d; setColor(0xFFFFFFFF.toInt()) }
            background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, mask)
            setOnClickListener { onClick() }
        }
        // Icon sits inside a pill that lights up when selected (Material-style indicator).
        val wrap = FrameLayout(this)
        val pill = View(this).apply {
            tag = "pill"
            background = GradientDrawable().apply { cornerRadius = 100 * d; setColor((Theme.ACCENT_A and 0x00FFFFFF) or 0x30000000) }
            alpha = 0f
        }
        wrap.addView(pill, FrameLayout.LayoutParams((56 * d).toInt(), (30 * d).toInt(), Gravity.CENTER))
        wrap.addView(ImageView(this).apply {
            setImageBitmap(icon); tag = "glyph"; setColorFilter(Theme.TEXT_2)
        }, FrameLayout.LayoutParams((21 * d).toInt(), (21 * d).toInt(), Gravity.CENTER))
        col.addView(wrap)
        col.addView(TextView(this).apply {
            text = label; textSize = 11.5f; tag = "label"
            typeface = Fonts.medium(this@MainActivity)
            setTextColor(Theme.TEXT_2)
            gravity = Gravity.CENTER_HORIZONTAL  // text fills a MATCH_PARENT column; center it under the icon
            setPadding(0, (4 * d).toInt(), 0, 0)
        })
        return col
    }

    private fun refreshRibbonSelection(name: String) {
        fun tint(item: View, on: Boolean) {
            val c = if (on) Theme.ACCENT_A else Theme.TEXT_2
            item.findViewWithTag<ImageView>("glyph").setColorFilter(c)
            item.findViewWithTag<View>("pill").animate().alpha(if (on) 1f else 0f).setDuration(180).start()
            item.findViewWithTag<TextView>("label").apply {
                setTextColor(c)
                typeface = if (on) Fonts.bold(this@MainActivity) else Fonts.medium(this@MainActivity)
            }
        }
        tint(histItem, name == "history")
        tint(genItem, name == "generate")
    }

    private fun setRibbonVisible(visible: Boolean, animate: Boolean) {
        if (visible == !ribbonHidden) return
        ribbonHidden = !visible
        val target = if (visible) 0f else ribbonH.toFloat()
        if (animate) ribbon.animate().translationY(target).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        else ribbon.translationY = target
    }

    /** Hide the ribbon on downward scroll, show on upward / at top. */
    private fun scrollAware(scroll: ScrollView) {
        var acc = 0
        val slop = (10 * d).toInt()
        scroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            val dy = y - oldY
            if (y <= slop) { setRibbonVisible(true, true); acc = 0; return@setOnScrollChangeListener }
            acc = if (dy > 0 == acc > 0) acc + dy else dy
            if (acc > slop * 2) { setRibbonVisible(false, true); acc = 0 }
            else if (acc < -slop * 2) { setRibbonVisible(true, true); acc = 0 }
        }
    }

    // ---------------------------------------------------------------- History page

    private fun buildHistoryPage(): View {
        val entries = HistoryStore.load(this)
        val scroll = ScrollView(this).apply {
            tag = "scroll"; isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * d).toInt(); setPadding(p, (12 * d).toInt(), p, 0)
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.text(this, "History", 28f, Color.WHITE, bold = true))
        titles.addView(Ui.text(this, if (entries.isEmpty()) "Your scans will appear here"
            else "${entries.size} ${if (entries.size == 1) "code" else "codes"}", 13.5f, Theme.TEXT_2)
            .apply { setPadding(0, (3 * d).toInt(), 0, 0) })
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (entries.isNotEmpty()) header.addView(iconButton(Icons.trash(this, 20f)) { clearAll() })
        header.addView(iconButton(Icons.gear(this, 20f)) { startActivity(Intent(this, SettingsActivity::class.java)) })
        col.addView(header, lp(bottom = 14))

        if (QrLensService.instance == null) col.addView(enableBanner(), lp(bottom = 14))

        if (entries.isEmpty()) {
            col.addView(emptyState(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * d).toInt()))
        } else {
            val grid = GridLayout(this).apply { columnCount = 2 }
            val gap = (12 * d).toInt()
            entries.forEachIndexed { i, e ->
                val cell = thumbCell(e)
                cell.layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(i % 2, 1f)
                    rowSpec = GridLayout.spec(i / 2)
                    setMargins(if (i % 2 == 0) 0 else gap / 2, 0, if (i % 2 == 1) 0 else gap / 2, gap)
                }
                grid.addView(cell)
            }
            col.addView(grid)
        }
        scroll.addView(col)
        scrollAware(scroll)
        return scroll
    }

    private fun thumbCell(e: HistoryEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = tile(16f)
            isClickable = true
            foreground = ripple(16f)
            setOnClickListener { openDetail(e, this) }
            setOnLongClickListener { confirmRemove(e); true }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(15 * d, 15 * d, 15 * d, 15 * d, 0f, 0f, 0f, 0f)
            }
            val p = (16 * d).toInt(); setPadding(p, p, p, p)
            tag = "thumb"
        }
        QrThumbs.into(img, e.text)
        card.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (150 * d).toInt()))
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * d).toInt(); setPadding(p, (10 * d).toInt(), p, (12 * d).toInt())
        }
        meta.addView(Ui.text(this, e.label.ifBlank { "CODE" }, 10f, Theme.ACCENT_A, bold = true).apply { letterSpacing = 0.08f })
        meta.addView(Ui.text(this, e.title.ifBlank { e.text }, 14f, Color.WHITE).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, (3 * d).toInt(), 0, 0)
        })
        meta.addView(Ui.text(this, ago(e.time), 11f, Theme.TEXT_2).apply { setPadding(0, (3 * d).toInt(), 0, 0) })
        card.addView(meta)
        return card
    }

    private fun emptyState(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        box.addView(ImageView(this).apply {
            setImageBitmap(Icons.history(this@MainActivity, 58f)); setColorFilter(Theme.CARD_STROKE)
        }, LinearLayout.LayoutParams((58 * d).toInt(), (58 * d).toInt()))
        box.addView(Ui.text(this, "No scans yet", 18f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, (16 * d).toInt(), 0, 0)
        })
        box.addView(Ui.text(this, "Press your shortcut on any screen with a QR code.\nWhat you scan is saved here, on your phone.",
            14f, Theme.TEXT_2).apply { gravity = Gravity.CENTER; setPadding(0, (8 * d).toInt(), 0, 0); setLineSpacing(0f, 1.3f) })
        return box
    }

    private fun enableBanner(): View {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor((Theme.CAUTION and 0x00FFFFFF) or 0x24000000)
            }
            val p = (15 * d).toInt(); setPadding(p, (13 * d).toInt(), p, (13 * d).toInt())
            isClickable = true
            foreground = ripple(16f)
            setOnClickListener {
                try { startActivity(Setup.serviceSwitchIntent(this@MainActivity)) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(Ui.text(this, "QR Lens is off", 15f, Color.WHITE, bold = true))
        t.addView(Ui.text(this, "Tap to turn on the scanner shortcut", 12.5f, Theme.TEXT_2).apply { setPadding(0, (1 * d).toInt(), 0, 0) })
        b.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        b.addView(ImageView(this).apply {
            setImageBitmap(Icons.chevron(this@MainActivity, 18f)); setColorFilter(Theme.TEXT_2)
        }, LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()))
        return b
    }

    // ---------------------------------------------------------------- Generate page

    private fun buildGeneratePage(): View {
        val scroll = ScrollView(this).apply {
            tag = "scroll"; isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * d).toInt(); setPadding(p, (12 * d).toInt(), p, 0)
        }
        col.addView(Ui.text(this, "Generate", 28f, Color.WHITE, bold = true))
        col.addView(Ui.text(this, "Make a QR code from any text or link.", 14f, Theme.TEXT_2)
            .apply { setPadding(0, (4 * d).toInt(), 0, 0) })

        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        var current: android.graphics.Bitmap? = null

        val input = EditText(this).apply {
            hint = "Type text or paste a link…"
            setHintTextColor(Theme.TEXT_2); setTextColor(Color.WHITE); textSize = 16f; typeface = Fonts.regular(this@MainActivity)
            setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * d; setColor(Theme.CARD_BG)
                setStroke((1 * d).toInt().coerceAtLeast(1), Theme.CARD_STROKE)
            }
        }
        val imgCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 20 * d; setColor(Color.WHITE) }
            val p = (20 * d).toInt(); setPadding(p, p, p, p)
            minimumHeight = (300 * d).toInt()
        }
        imgCard.addView(image, LinearLayout.LayoutParams((260 * d).toInt(), (260 * d).toInt()))
        val emptyHint = Ui.text(this, "Your QR code will appear here", 14f, Color.rgb(0x9A, 0x9A, 0x9A)).apply { gravity = Gravity.CENTER }
        imgCard.addView(emptyHint)

        fun update() {
            val txt = input.text?.toString().orEmpty()
            current = if (txt.isBlank()) null else QrGen.encode(txt, sizePx = 720)
            image.setImageBitmap(current)
            image.visibility = if (current != null) View.VISIBLE else View.GONE
            emptyHint.visibility = if (current != null) View.GONE else View.VISIBLE
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = update()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        col.addView(input, lp(top = 18))
        col.addView(imgCard, lp(top = 16))

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        actions.addView(Ui.button(this, "Share", primary = false) { current?.let { shareImage(it, true) } ?: toast("Type something first") })
        actions.addView(Ui.button(this, "Save image", primary = true) { current?.let { shareImage(it, false) } ?: toast("Type something first") },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = (10 * d).toInt() })
        col.addView(actions, lp(top = 16))

        scroll.addView(col)
        scrollAware(scroll)
        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!shared.isNullOrBlank()) input.setText(shared)
        update()
        return scroll
    }

    // ---------------------------------------------------------------- Detail viewer

    private fun openDetail(e: HistoryEntry, from: View) {
        if (detail != null) return
        val overlay = FrameLayout(this).apply { isClickable = true }
        val scrim = View(this).apply {
            setBackgroundColor(Color.argb(0xDE, 2, 3, 8)); alpha = 0f
            setOnClickListener { closeDetail() }
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = (24 * d).toInt()
            setPadding(p, topInset + (18 * d).toInt(), p, bottomInset + (22 * d).toInt())
        }
        content.addView(Ui.text(this, e.label.ifBlank { "CODE" }, 11f, Theme.ACCENT_A, bold = true).apply {
            letterSpacing = 0.12f; gravity = Gravity.CENTER
        })
        content.addView(Ui.text(this, e.title.ifBlank { e.text }, 19f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(0, (5 * d).toInt(), 0, 0)
        })
        if (e.text != e.title) content.addView(Ui.text(this, e.text, 12.5f, Theme.TEXT_2).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, (5 * d).toInt(), 0, 0)
        })
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val qrCard = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = 26 * d; setColor(Color.WHITE) }
            val p = (24 * d).toInt(); setPadding(p, p, p, p)
            setImageBitmap(QrGen.encode(e.text, sizePx = 900))
        }
        val side = minOf((resources.displayMetrics.widthPixels - (88 * d)).toInt(), (330 * d).toInt())
        content.addView(qrCard, LinearLayout.LayoutParams(side, side))

        if (QrParser.parse(e.text).uri != null) {
            content.addView(Ui.button(this, "Open", primary = true) { QrActions.open(this, QrParser.parse(e.text)) }, lp(top = 20))
        }
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        actions.addView(detailAction(Icons.share(this, 24f), "Share") { shareImage(QrGen.encode(e.text, 900), true) })
        val hasSource = HistoryStore.sourceFile(this, e) != null
        actions.addView(detailAction(Icons.image(this, 24f), "Source image", enabled = hasSource, leftGap = true) {
            val f = HistoryStore.sourceFile(this, e)
            if (f == null) { toast("The original image wasn't saved for this scan"); return@detailAction }
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(ShareProvider.uriFor(this, f), "image/png")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ex: Exception) { toast("No app to open images") }
        })
        content.addView(actions, lp(top = 6))

        overlay.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        detail = overlay

        val loc = IntArray(2); from.getLocationOnScreen(loc)
        content.post {
            val qloc = IntArray(2); qrCard.getLocationOnScreen(qloc)
            if (qrCard.width == 0) return@post
            val startCx = loc[0] + from.width / 2f
            val startCy = loc[1] + from.height / 2f
            val endCx = qloc[0] + qrCard.width / 2f
            val endCy = qloc[1] + qrCard.height / 2f
            val sc = (from.width.toFloat() / qrCard.width).coerceIn(0.15f, 1f)
            content.pivotX = qrCard.x + qrCard.width / 2f
            content.pivotY = qrCard.y + qrCard.height / 2f
            content.scaleX = sc; content.scaleY = sc
            content.translationX = startCx - endCx
            content.translationY = startCy - endCy
            content.alpha = 0f
            content.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).alpha(1f)
                .setDuration(300).setInterpolator(DecelerateInterpolator(1.4f)).start()
        }
        scrim.animate().alpha(1f).setDuration(240).start()
        ribbon.animate().translationY(ribbonH.toFloat()).setDuration(200).start()
    }

    private fun closeDetail(instant: Boolean = false) {
        val overlay = detail ?: return
        detail = null
        if (!ribbonHidden) ribbon.animate().translationY(0f).setDuration(200).start()
        if (instant) { root.removeView(overlay); return }
        overlay.animate().alpha(0f).setDuration(180).withEndAction { root.removeView(overlay) }.start()
    }

    private fun detailAction(icon: android.graphics.Bitmap, label: String, enabled: Boolean = true, leftGap: Boolean = false, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = if (enabled) 1f else 0.4f
            isClickable = true
            val mask = GradientDrawable().apply { cornerRadius = 18 * d; setColor(0xFFFFFFFF.toInt()) }
            background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, mask)
            val h = (24 * d).toInt(); val v = (12 * d).toInt(); setPadding(h, v, h, v)
            setOnClickListener { onClick() }
        }
        col.addView(ImageView(this).apply { setImageBitmap(icon); setColorFilter(Color.WHITE) },
            LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()))
        col.addView(Ui.text(this, label, 12.5f, Theme.TEXT_2).apply { gravity = Gravity.CENTER; setPadding(0, (6 * d).toInt(), 0, 0) })
        if (leftGap) col.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = (24 * d).toInt() }
        return col
    }

    // ---------------------------------------------------------------- helpers

    private fun shareImage(bmp: android.graphics.Bitmap?, share: Boolean) {
        bmp ?: return
        val file = try {
            val dir = java.io.File(cacheDir, ShareProvider.DIR).apply { mkdirs() }
            java.io.File(dir, "qr_${System.currentTimeMillis()}.png").also { f ->
                java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        } catch (e: Exception) { toast("Couldn't create image"); return }
        val uri = ShareProvider.uriFor(this, file)
        val i = if (share) Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(if (share) Intent.createChooser(i, "Share QR code") else i) }
        catch (e: Exception) { toast("No app to handle that") }
    }

    private fun clearAll() {
        HistoryStore.clear(this)
        toast("History cleared")
        refreshHistory()
    }

    private fun confirmRemove(e: HistoryEntry) {
        HistoryStore.remove(this, e)
        toast("Removed")
        refreshHistory()
    }

    private fun iconButton(icon: android.graphics.Bitmap, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageBitmap(icon); setColorFilter(Theme.TEXT_2)
        scaleType = ImageView.ScaleType.CENTER
        val s = (42 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s).apply { leftMargin = (6 * d).toInt() }
        val mask = GradientDrawable().apply { cornerRadius = 100 * d; setColor(0xFFFFFFFF.toInt()) }
        background = RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), null, mask)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun tile(radius: Float) = GradientDrawable().apply {
        cornerRadius = radius * d
        setColor(Theme.CARD_BG)
        setStroke((1 * d).toInt().coerceAtLeast(1), Theme.CARD_STROKE)
    }
    private fun ripple(radius: Float) = RippleDrawable(
        ColorStateList.valueOf(0x22FFFFFF), null,
        GradientDrawable().apply { cornerRadius = radius * d; setColor(0xFFFFFFFF.toInt()) }
    )
    private fun lp(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = (top * d).toInt(); bottomMargin = (bottom * d).toInt() }

    private fun ago(t: Long): String =
        DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
}
