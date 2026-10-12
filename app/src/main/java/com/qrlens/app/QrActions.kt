package com.qrlens.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.provider.Settings
import android.widget.Toast
import java.io.File

object QrActions {

    /** Primary action: links open directly; everything else opens as a file in a native viewer. */
    fun open(ctx: Context, c: QrContent) {
        val uri = c.uri
        if (uri != null) {
            val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (uri.scheme == "http" || uri.scheme == "https") i.addCategory(Intent.CATEGORY_BROWSABLE)
            if (start(ctx, i)) return
        }
        openAsFile(ctx, c)
    }

    fun openAsFile(ctx: Context, c: QrContent) {
        val file = try {
            writeFile(ctx, c)
        } catch (e: Exception) {
            toast(ctx, "Couldn't create file")
            return
        }
        val uri = ShareProvider.uriFor(ctx, file)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, c.mime).addFlags(flags)
        if (start(ctx, view)) return
        // Typed viewer missing (e.g. no app for .vcf) - fall back to any text viewer.
        if (c.mime != "text/plain") {
            val asText = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain").addFlags(flags)
            if (start(ctx, asText)) return
        }
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Open with"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (start(ctx, chooser)) return
        copy(ctx, c.raw)
        toast(ctx, "No viewer app found, copied instead")
    }

    /**
     * Saves the network to the phone's saved Wi-Fi list and connects right away if it's in range.
     * Android's own "Save network?" sheet does both for a single network: it calls save() and then
     * connect(); if the network isn't nearby it simply stays saved and auto-joins when it appears.
     * Falls back to an app suggestion + copied password + Wi-Fi panel when that sheet isn't usable
     * (WEP networks, odd passwords, OEMs without the sheet).
     */
    fun connectWifi(ctx: Context, w: WifiInfo) {
        val suggestion = buildSuggestion(w)
        if (suggestion != null) {
            val i = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (start(ctx, i)) return
            // No system sheet on this phone: suggest it instead (auto-joins whenever it's in range).
            try {
                ctx.getSystemService(WifiManager::class.java)?.addNetworkSuggestions(listOf(suggestion))
            } catch (_: Exception) {
            }
        }
        if (w.password.isNotEmpty()) copy(ctx, w.password)
        val panel = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(ctx, panel)) start(ctx, Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        toast(
            ctx,
            if (w.password.isNotEmpty()) "Password copied. Tap \"${w.ssid}\" and paste it"
            else "Tap \"${w.ssid}\" to join"
        )
    }

    private fun buildSuggestion(w: WifiInfo): WifiNetworkSuggestion? = try {
        val b = WifiNetworkSuggestion.Builder().setSsid(w.ssid)
        val t = w.security.uppercase()
        val ok = when {
            w.ssid.isEmpty() -> false
            t.contains("WEP") -> false                       // not supported by the API
            t == "NOPASS" || w.password.isEmpty() -> true     // open network
            t.contains("SAE") || t.contains("WPA3") -> { b.setWpa3Passphrase(w.password); true }
            else -> { b.setWpa2Passphrase(w.password); true } // WPA / WPA2 (and WPA2/3 transition)
        }
        if (ok) {
            if (w.hidden) b.setIsHiddenSsid(true)
            b.build()
        } else null
    } catch (e: Exception) {
        null // e.g. a passphrase shorter than 8 characters
    }

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("QR code", text))
    }

    fun share(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        start(ctx, Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun writeFile(ctx: Context, c: QrContent): File {
        val dir = File(ctx.cacheDir, ShareProvider.DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        val base = when (c.kind) {
            Kind.CONTACT -> "contact"
            Kind.EVENT -> "event"
            Kind.WIFI -> "wifi"
            else -> "qr"
        }
        val f = File(dir, "${base}_${System.currentTimeMillis()}.${c.fileExt}")
        f.writeText(c.fileBody)
        return f
    }

    private fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
