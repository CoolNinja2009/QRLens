package com.qrlens.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File
import java.io.FileOutputStream

/** Create a QR code from text or a link, then show / share / save it. All on-device. */
class GenerateActivity : Activity() {

    private val d by lazy { Ui.dp(this) }
    private lateinit var image: ImageView
    private lateinit var input: EditText
    private var current: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (scroll, col) = Ui.page(this)
        col.addView(Ui.text(this, "Generate", 26f, Color.WHITE, bold = true))
        col.addView(Ui.text(this, "Make a QR code from any text or link.", 14f, Theme.TEXT_2)
            .apply { setPadding(0, (4 * d).toInt(), 0, 0) })

        input = EditText(this).apply {
            hint = "Type text or paste a link…"
            setHintTextColor(Theme.TEXT_2)
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(Theme.CARD_BG)
                setStroke((1 * d).toInt().coerceAtLeast(1), Theme.CARD_STROKE)
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = update()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        col.addView(input, Ui.cardLp(this, 18))

        val imgCard = Ui.card(this).apply { gravity = Gravity.CENTER }
        image = ImageView(this)
        imgCard.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (300 * d).toInt()))
        col.addView(imgCard, Ui.cardLp(this, 18))

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        actions.addView(Ui.button(this, "Share", primary = false) { shareOrSave(share = true) })
        actions.addView(Ui.button(this, "Save image", primary = true) { shareOrSave(share = false) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = (10 * d).toInt()
            })
        col.addView(actions, Ui.cardLp(this, 16))

        setContentView(scroll)
        // Prefill if launched via Share-to-QR Lens
        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!shared.isNullOrBlank()) input.setText(shared)
        update()
    }

    private fun update() {
        val txt = input.text?.toString().orEmpty()
        current = if (txt.isBlank()) null else QrGen.encode(txt, sizePx = 720)
        image.setImageBitmap(current)
    }

    private fun shareOrSave(share: Boolean) {
        val bmp = current ?: run { toast("Type something first"); return }
        val file = try {
            val dir = File(cacheDir, ShareProvider.DIR).apply { mkdirs() }
            File(dir, "qr_${System.currentTimeMillis()}.png").also { f ->
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } catch (e: Exception) {
            toast("Couldn't create image"); return
        }
        val uri = ShareProvider.uriFor(this, file)
        val i = if (share) {
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(if (share) Intent.createChooser(i, "Share QR code") else i)
        } catch (e: Exception) {
            toast("No app to handle that")
        }
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
}
