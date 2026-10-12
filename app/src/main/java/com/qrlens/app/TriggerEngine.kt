package com.qrlens.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.view.KeyEvent

/** The hardware-button gestures that can open the scanner. */
enum class Trigger(val id: String, val title: String, val short: String, val hint: String, val sequence: List<Int>?) {
    UP3("up3", "3× Volume Up", "Vol Up ×3",
        "Default. Very hard to trigger by accident.", listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP)),
    UP2("up2", "2× Volume Up", "Vol Up ×2",
        "Quicker. Can fire if you tap volume up twice to raise it.", listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP)),
    DOWN3("down3", "3× Volume Down", "Vol Down ×3",
        "Same as the default, on the other key.", listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)),
    DOWN2("down2", "2× Volume Down", "Vol Down ×2",
        "Quicker. Can fire if you tap volume down twice to lower it.", listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)),
    BOTH("both", "Both volume keys together", "both volume keys",
        "Press and release both at once. Don't hold them 3 seconds: that's Android's own accessibility shortcut.", null),
    UP_DOWN("updown", "Volume Up, then Volume Down", "Vol Up → Down",
        "Two quick presses in that order.", listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)),
    HOLD_UP("holdup", "Long-press Volume Up", "hold Vol Up",
        "Hold for about half a second. A short press still changes the volume.", null);

    val keys: Set<Int>
        get() = sequence?.toSet() ?: if (this == BOTH)
            setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
        else setOf(KeyEvent.KEYCODE_VOLUME_UP)

    companion object {
        fun byId(id: String?): Trigger = values().firstOrNull { it.id == id } ?: UP3
    }
}

/**
 * Turns raw volume-key events into "open the scanner" for the selected [Trigger].
 * When volume presses are held back, every swallowed press that doesn't end up being part of
 * the gesture is replayed as a normal volume change, so the keys keep working as usual.
 */
class TriggerEngine(private val ctx: Context, private val handler: Handler, private val fire: () -> Unit) {

    private class Press(val key: Int, val heldBack: Boolean)

    private val pending = ArrayList<Press>()
    private val consumedDown = HashSet<Int>()
    private val held = HashSet<Int>()
    private var lastDown = 0L
    private var longFired = false
    private var chordFired = false

    private val flush = Runnable { replayAll() }
    private val longPress = Runnable {
        longFired = true
        pending.clear()
        fire()
    }

    fun onKey(e: KeyEvent): Boolean {
        val key = e.keyCode
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val t = Trigger.byId(Prefs.triggerId(ctx))
        val hold = Prefs.holdBackVolume(ctx) || t == Trigger.HOLD_UP

        if (key !in t.keys) {
            if (pending.isNotEmpty()) replayAll()
            return false
        }

        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return onRepeat(t, key, e.repeatCount)
                held.add(key)
                when {
                    t == Trigger.HOLD_UP -> {
                        longFired = false
                        handler.removeCallbacks(longPress)
                        handler.postDelayed(longPress, LONG_PRESS_MS)
                    }
                    t == Trigger.BOTH -> {
                        if (held.containsAll(t.keys)) {
                            handler.removeCallbacks(flush)
                            pending.clear()
                            chordFired = true
                            fire()
                        } else {
                            pending.add(Press(key, hold))
                            handler.removeCallbacks(flush)
                            handler.postDelayed(flush, CHORD_WINDOW_MS)
                        }
                    }
                    else -> onSequencePress(t.sequence ?: return false, key, hold, e.eventTime)
                }
                if (hold) consumedDown.add(key)
                return hold
            }
            KeyEvent.ACTION_UP -> {
                held.remove(key)
                if (t == Trigger.HOLD_UP) {
                    handler.removeCallbacks(longPress)
                    if (!longFired && key in consumedDown) adjust(key) // short press = normal volume step
                    longFired = false
                }
                if (t == Trigger.BOTH && held.isEmpty()) chordFired = false
                return consumedDown.remove(key)
            }
        }
        return false
    }

    private fun onSequencePress(seq: List<Int>, key: Int, hold: Boolean, time: Long) {
        if (time - lastDown > SEQUENCE_WINDOW_MS) replayAll()
        lastDown = time
        pending.add(Press(key, hold))
        // Drop presses from the front until what's left could still become the gesture.
        while (pending.isNotEmpty() && !isPrefix(pending, seq)) replay(pending.removeAt(0))
        handler.removeCallbacks(flush)
        if (pending.size == seq.size) {
            pending.clear()
            fire()
        } else if (pending.isNotEmpty()) {
            handler.postDelayed(flush, SEQUENCE_WINDOW_MS)
        }
    }

    /** Key held down (auto-repeat): behave like a normal volume key unless it's our gesture. */
    private fun onRepeat(t: Trigger, key: Int, repeat: Int): Boolean {
        if (t == Trigger.HOLD_UP) return key in consumedDown
        if (t == Trigger.BOTH && chordFired) return true
        if (key !in consumedDown) return false
        handler.removeCallbacks(flush)
        replayAll()
        if (repeat % 2 == 0) adjust(key)
        return true
    }

    private fun isPrefix(p: List<Press>, seq: List<Int>): Boolean {
        if (p.size > seq.size) return false
        for (i in p.indices) if (p[i].key != seq[i]) return false
        return true
    }

    private fun replayAll() {
        handler.removeCallbacks(flush)
        val copy = ArrayList(pending)
        pending.clear()
        for (p in copy) replay(p)
    }

    private fun replay(p: Press) {
        if (p.heldBack) adjust(p.key)
    }

    private fun adjust(key: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.adjustSuggestedStreamVolume(
            if (key == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    companion object {
        private const val SEQUENCE_WINDOW_MS = 450L
        private const val CHORD_WINDOW_MS = 350L
        private const val LONG_PRESS_MS = 550L
    }
}
