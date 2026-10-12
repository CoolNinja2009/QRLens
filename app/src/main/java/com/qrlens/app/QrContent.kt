package com.qrlens.app

import android.net.Uri
import java.util.Locale

enum class Kind { LINK, APP_LINK, UPI, EMAIL, PHONE, SMS, GEO, WIFI, CONTACT, EVENT, TEXT }

data class WifiInfo(val ssid: String, val password: String, val security: String, val hidden: Boolean)

/** A decoded QR code plus everything the UI needs to present and open it. */
data class QrContent(
    val raw: String,
    val kind: Kind,
    val label: String,
    val title: String,
    val detail: String,
    /** Non-null when the code is directly openable (web link, UPI, mailto, tel...). */
    val uri: Uri?,
    val fileExt: String,
    val mime: String,
    val fileBody: String,
    val wifi: WifiInfo? = null
) {
    val isLink: Boolean get() = uri != null
}

object QrParser {

    private val GENERIC_URI = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://\\S+$")

    fun parse(input: String): QrContent {
        val raw = input.trim()
        val lower = raw.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> link(raw)
            lower.startsWith("www.") && !raw.contains(' ') && !raw.contains('\n') -> link("https://$raw")
            lower.startsWith("upi://") -> upi(raw)
            lower.startsWith("wifi:") -> wifi(raw)
            lower.startsWith("begin:vcard") -> contact(raw)
            lower.startsWith("mecard:") -> contact(mecardToVcard(raw))
            lower.startsWith("begin:vcalendar") || lower.startsWith("begin:vevent") -> event(raw)
            lower.startsWith("mailto:") -> simple(raw, Kind.EMAIL, "EMAIL", raw.substring(7).substringBefore('?'))
            lower.startsWith("matmsg:") -> matmsg(raw)
            lower.startsWith("tel:") -> simple(raw, Kind.PHONE, "PHONE", raw.substring(4))
            lower.startsWith("smsto:") || lower.startsWith("sms:") -> sms(raw)
            lower.startsWith("geo:") -> simple(raw, Kind.GEO, "LOCATION", raw.substring(4).substringBefore('?'))
            GENERIC_URI.matches(raw) && !lower.startsWith("javascript") && !lower.startsWith("intent") ->
                simple(raw, Kind.APP_LINK, "APP LINK", raw.substringBefore("://") + " link")
            else -> text(raw)
        }
    }

    private fun link(url: String): QrContent {
        val uri = Uri.parse(url).normalizeScheme()
        val host = uri.host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url
        return QrContent(
            raw = url, kind = Kind.LINK, label = if (uri.scheme == "https") "SECURE LINK" else "LINK",
            title = host, detail = url, uri = uri,
            fileExt = "txt", mime = "text/plain", fileBody = url + "\n"
        )
    }

    private fun simple(raw: String, kind: Kind, label: String, title: String): QrContent {
        val t = Uri.decode(title).ifBlank { raw }
        return QrContent(raw, kind, label, t, raw, Uri.parse(raw), "txt", "text/plain", raw + "\n")
    }

    private fun upi(raw: String): QrContent {
        val u = Uri.parse(raw)
        fun q(k: String) = try { u.getQueryParameter(k) } catch (_: Exception) { null }
        val payee = q("pn")?.takeIf { it.isNotBlank() }
        val vpa = q("pa") ?: ""
        val amount = q("am")?.takeIf { it.isNotBlank() }
        val detail = listOfNotNull(vpa.ifBlank { null }, amount?.let { "₹$it" }).joinToString("  ·  ")
        return QrContent(
            raw, Kind.UPI, "UPI PAYMENT", payee ?: vpa.ifBlank { "UPI payment" },
            detail.ifBlank { raw }, u, "txt", "text/plain", raw + "\n"
        )
    }

    private fun sms(raw: String): QrContent {
        // smsto:+911234567890:message
        val body = raw.substringAfter(':')
        val number = body.substringBefore(':')
        val msg = if (body.contains(':')) body.substringAfter(':') else ""
        val uri = Uri.parse("smsto:" + Uri.encode(number) + if (msg.isNotEmpty()) "?body=" + Uri.encode(msg) else "")
        return QrContent(raw, Kind.SMS, "SMS", number, msg.ifBlank { raw }, uri, "txt", "text/plain", raw + "\n")
    }

    private fun matmsg(raw: String): QrContent {
        val f = fields(raw.substringAfter(':'))
        val to = f["TO"] ?: ""
        val params = listOfNotNull(
            f["SUB"]?.let { "subject=" + Uri.encode(it) },
            f["BODY"]?.let { "body=" + Uri.encode(it) }
        ).joinToString("&")
        val uri = Uri.parse("mailto:" + to + if (params.isNotEmpty()) "?$params" else "")
        return QrContent(raw, Kind.EMAIL, "EMAIL", to.ifBlank { "Email" }, f["SUB"] ?: raw, uri, "txt", "text/plain", raw + "\n")
    }

    private fun wifi(raw: String): QrContent {
        val f = fields(raw.substring(5))
        val ssid = f["S"] ?: ""
        val pass = f["P"] ?: ""
        val sec = (f["T"] ?: if (pass.isEmpty()) "nopass" else "WPA").ifBlank { "WPA" }
        val hidden = f["H"].equals("true", ignoreCase = true)
        val secLabel = if (sec.equals("nopass", true)) "Open network" else sec.uppercase(Locale.ROOT)
        val detail = if (pass.isNotEmpty()) "Password: $pass  ·  $secLabel" else secLabel
        val body = buildString {
            append("Wi-Fi network\n\n")
            append("Name (SSID): ").append(ssid).append('\n')
            append("Password: ").append(pass.ifEmpty { "(none)" }).append('\n')
            append("Security: ").append(secLabel).append('\n')
            if (hidden) append("Hidden network: yes\n")
        }
        return QrContent(
            raw, Kind.WIFI, "WI-FI", ssid.ifBlank { "Wi-Fi network" }, detail, null,
            "txt", "text/plain", body, WifiInfo(ssid, pass, sec, hidden)
        )
    }

    private fun contact(vcard: String): QrContent {
        val lines = vcard.lines()
        fun prop(name: String) = lines.firstOrNull {
            it.uppercase(Locale.ROOT).startsWith("$name:") || it.uppercase(Locale.ROOT).startsWith("$name;")
        }?.substringAfter(':')?.trim()
        val name = prop("FN") ?: prop("N")?.split(';')?.filter { it.isNotBlank() }?.reversed()?.joinToString(" ")
        val detail = listOfNotNull(prop("TEL"), prop("EMAIL"), prop("ORG")).joinToString("  ·  ")
        val body = vcard.replace("\r\n", "\n").replace("\n", "\r\n").let { if (it.endsWith("\r\n")) it else it + "\r\n" }
        return QrContent(vcard, Kind.CONTACT, "CONTACT", name ?: "Contact", detail.ifBlank { "vCard" }, null,
            "vcf", "text/x-vcard", body)
    }

    private fun event(raw: String): QrContent {
        val lines = raw.lines()
        val summary = lines.firstOrNull { it.uppercase(Locale.ROOT).startsWith("SUMMARY") }?.substringAfter(':')?.trim()
        val start = lines.firstOrNull { it.uppercase(Locale.ROOT).startsWith("DTSTART") }?.substringAfter(':')?.trim()
        val wrapped = if (raw.uppercase(Locale.ROOT).startsWith("BEGIN:VCALENDAR")) raw
        else "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//QR Lens//EN\n$raw\nEND:VCALENDAR"
        val body = wrapped.replace("\r\n", "\n").replace("\n", "\r\n") + "\r\n"
        return QrContent(raw, Kind.EVENT, "EVENT", summary ?: "Calendar event", formatIcsDate(start) ?: "Calendar invite",
            null, "ics", "text/calendar", body)
    }

    private fun text(raw: String): QrContent {
        val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: raw
        val title = if (firstLine.length > 60) firstLine.take(57) + "…" else firstLine
        return QrContent(raw, Kind.TEXT, "TEXT", title.ifBlank { "Text" }, raw, null, "txt", "text/plain", raw + "\n")
    }

    private fun formatIcsDate(s: String?): String? {
        if (s == null || s.length < 8) return s
        return try {
            val y = s.substring(0, 4); val m = s.substring(4, 6); val d = s.substring(6, 8)
            val time = if (s.length >= 13 && s[8] == 'T') " ${s.substring(9, 11)}:${s.substring(11, 13)}" else ""
            "$d-$m-$y$time"
        } catch (_: Exception) { s }
    }

    /** MECARD:N:Doe,John;TEL:123;EMAIL:a@b.c;; -> vCard 3.0 */
    private fun mecardToVcard(raw: String): String {
        val f = fields(raw.substringAfter(':'))
        val n = f["N"] ?: ""
        val parts = n.split(',')
        val fn = if (parts.size >= 2) "${parts[1].trim()} ${parts[0].trim()}" else n
        return buildString {
            append("BEGIN:VCARD\nVERSION:3.0\n")
            append("N:").append(parts.getOrElse(0) { "" }.trim()).append(';').append(parts.getOrElse(1) { "" }.trim()).append(";;;\n")
            append("FN:").append(fn.trim()).append('\n')
            f["TEL"]?.let { append("TEL:").append(it).append('\n') }
            f["EMAIL"]?.let { append("EMAIL:").append(it).append('\n') }
            f["URL"]?.let { append("URL:").append(it).append('\n') }
            f["ADR"]?.let { append("ADR:;;").append(it).append(";;;;\n") }
            f["NOTE"]?.let { append("NOTE:").append(it).append('\n') }
            f["ORG"]?.let { append("ORG:").append(it).append('\n') }
            append("END:VCARD\n")
        }
    }

    /** Splits "K:V;K2:V2;;" honouring backslash escapes. First occurrence of a key wins. */
    private fun fields(s: String): Map<String, String> {
        val map = HashMap<String, String>()
        val sb = StringBuilder()
        var esc = false
        fun flush() {
            val t = sb.toString()
            val i = t.indexOf(':')
            if (i > 0) map.putIfAbsent(t.substring(0, i).trim().uppercase(Locale.ROOT), t.substring(i + 1))
            sb.setLength(0)
        }
        for (ch in s) {
            when {
                esc -> { sb.append(ch); esc = false }
                ch == '\\' -> esc = true
                ch == ';' -> flush()
                else -> sb.append(ch)
            }
        }
        flush()
        return map
    }
}
