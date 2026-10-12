package com.qrlens.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: shows whether QR Lens is on.
 * Tap while on -> pause it. Tap while off -> jump straight to its on/off switch.
 */
class QrLensTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val svc = QrLensService.instance
        if (svc != null) {
            svc.pause()
            update(forceOff = true)
            return
        }
        val intent = Setup.serviceSwitchIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun update(forceOff: Boolean = false) {
        val t = qsTile ?: return
        val on = !forceOff && QrLensService.instance != null
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "QR Lens"
        t.subtitle = if (on) "On" else "Paused"
        t.updateTile()
    }

    companion object {
        fun refresh(ctx: Context) {
            try {
                requestListeningState(ctx, ComponentName(ctx, QrLensTile::class.java))
            } catch (_: Exception) {
            }
        }
    }
}

object Setup {
    /** Opens QR Lens' own accessibility page (where the switch is) when the phone supports it. */
    fun serviceSwitchIntent(ctx: Context): Intent {
        val cn = ComponentName(ctx, QrLensService::class.java)
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, cn.flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (details.resolveActivity(ctx.packageManager) != null) details
        else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
