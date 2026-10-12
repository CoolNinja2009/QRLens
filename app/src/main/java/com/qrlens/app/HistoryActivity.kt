package com.qrlens.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Scan history (on-device). Tap an entry to act on it; long-press to remove. */
class HistoryActivity : Activity() {

    private val d by lazy { Ui.dp(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val (scroll, col) = Ui.page(this)

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(this, "History", 26f, Color.WHITE, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val entries = HistoryStore.load(this)
        if (entries.isNotEmpty()) {
            header.addView(Ui.button(this, "Clear all", primary = false) {
                HistoryStore.clear(this)
                render()
            })
        }
        col.addView(header)

        if (entries.isEmpty()) {
            col.addView(Ui.text(this, "Nothing scanned yet.\nUse your shortcut on any screen with a QR code and it'll show up here.",
                15f, Theme.TEXT_2).apply { setPadding(0, (16 * d).toInt(), 0, 0) })
        } else {
            for (e in entries) col.addView(entryCard(e), Ui.cardLp(this, 12))
        }
        setContentView(scroll)
    }

    private fun entryCard(e: HistoryEntry): LinearLayout {
        val content = QrParser.parse(e.text)
        val cardView = Ui.card(this).apply {
            isClickable = true
            setOnClickListener { QrActions.open(this@HistoryActivity, content) }
            setOnLongClickListener {
                HistoryStore.remove(this@HistoryActivity, e)
                render()
                true
            }
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(this, e.label.ifBlank { "CODE" }, 11f, Theme.ACCENT_A, bold = true).apply {
            letterSpacing = 0.1f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val ago = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        top.addView(Ui.text(this, ago.toString(), 12f, Theme.TEXT_2))
        cardView.addView(top)
        cardView.addView(Ui.text(this, e.title.ifBlank { e.text }, 17f, Color.WHITE).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (3 * d).toInt(), 0, 0)
        })
        if (e.text != e.title) {
            cardView.addView(Ui.text(this, e.text, 12f, Theme.TEXT_2).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        val actions = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, (10 * d).toInt(), 0, 0)
        }
        actions.addView(Ui.button(this, "Copy", primary = false) {
            QrActions.copy(this, e.text)
        })
        actions.addView(Ui.button(this, "Open", primary = true) {
            QrActions.open(this, content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (8 * d).toInt()
        })
        cardView.addView(actions)
        return cardView
    }
}
