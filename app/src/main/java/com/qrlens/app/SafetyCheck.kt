package com.qrlens.app

import java.util.Locale

enum class Risk { NONE, CAUTION, DANGER }

data class Safety(val risk: Risk, val reasons: List<String>) {
    val top: String? get() = reasons.firstOrNull()
}

/**
 * Best-effort, fully on-device safety heuristic for a decoded QR code.
 *
 * This is NOT a guarantee. It can't tell a real site from a convincing fake; it only catches
 * common tricks (look-alike domains, @-in-authority, raw IPs, insecure login pages, link
 * shorteners, brand names on the wrong domain) and always cautions on payments. It makes no
 * network calls. Favor warning the user over deciding for them: nothing here blocks an action.
 */
object SafetyCheck {

    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "rebrand.ly", "cutt.ly", "is.gd",
        "rb.gy", "shorturl.at", "ow.ly", "buff.ly", "t.ly", "tiny.cc", "bl.ink", "soo.gd"
    )

    private val BRANDS = listOf(
        "paytm", "phonepe", "gpay", "googlepay", "bhim", "sbi", "hdfc", "icici", "axis",
        "kotak", "amazon", "flipkart", "netflix", "whatsapp", "instagram", "facebook",
        "google", "apple", "microsoft", "paypal"
    )

    // Legit registrable domains we must NOT flag as "pretending to be <brand>".
    private val BRAND_REAL_DOMAINS = setOf(
        "paytm.com", "phonepe.com", "google.com", "pay.google.com", "amazon.in", "amazon.com",
        "flipkart.com", "netflix.com", "whatsapp.com", "instagram.com", "facebook.com",
        "apple.com", "microsoft.com", "paypal.com", "onlinesbi.sbi", "sbi.co.in",
        "hdfcbank.com", "icicibank.com", "axisbank.com", "kotak.com"
    )

    private val LURE_WORDS = listOf(
        "prize", "winner", "won", "reward", "refund", "cashback", "lottery", "lucky",
        "kyc", "gift", "bonus", "claim", "congratulations"
    )

    private val SENSITIVE_WORDS = listOf(
        "login", "signin", "sign-in", "verify", "netbanking", "account", "pay", "kyc",
        "otp", "secure", "bank", "wallet", "update", "password", "credential"
    )

    fun check(c: QrContent): Safety = try {
        when (c.kind) {
            Kind.UPI -> upi(c)
            Kind.LINK, Kind.APP_LINK -> link(c)
            else -> Safety(Risk.NONE, emptyList())
        }
    } catch (t: Throwable) {
        Safety(Risk.NONE, emptyList())   // never let a flagger bug break a scan
    }

    // ---------------------------------------------------------------- UPI

    private fun upi(c: QrContent): Safety {
        val blob = (c.title + " " + c.raw).lowercase(Locale.ROOT)
        val lure = LURE_WORDS.firstOrNull { blob.contains(it) }
        return if (lure != null) {
            Safety(Risk.DANGER, listOf(
                "Payment request mentions “$lure” — a common scam. Don't pay to claim a prize or refund.",
                "Only pay people you know, and check the name and amount."
            ))
        } else {
            Safety(Risk.CAUTION, listOf(
                "You're about to pay money. Check the name and amount, and only pay people you know."
            ))
        }
    }

    // ---------------------------------------------------------------- links

    private fun link(c: QrContent): Safety {
        val url = c.raw.trim()
        val scheme = url.substringBefore("://", "").lowercase(Locale.ROOT)
        val afterScheme = url.substringAfter("://", url)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        // user-info @ host:port  -> split off credentials and port
        val userInfo = if (authority.contains('@')) authority.substringBeforeLast('@') else null
        var host = authority.substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT).trimEnd('.')
        val path = afterScheme.substringAfter('/', "").lowercase(Locale.ROOT)
        val reasons = ArrayList<String>()
        var risk = Risk.NONE
        fun raise(to: Risk, why: String) {
            if (to.ordinal > risk.ordinal) risk = to
            reasons.add(why)
        }

        // Tokenize into whole words so "pay" matches "pay" but not "paytm", "secure" not "secured".
        val tokens = (host + "/" + path).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toHashSet()
        val looksSensitive = SENSITIVE_WORDS.any { it in tokens }

        // 1. credentials before the host: the real site is after the @
        if (userInfo != null && userInfo.isNotBlank()) {
            raise(Risk.DANGER, "The real address is the part after the “@”, not before it.")
        }
        // 2. raw IP address instead of a domain
        if (isIpHost(host)) {
            raise(Risk.DANGER, "Goes to a raw IP address, not a named website.")
        }
        // 3. punycode / look-alike characters
        if (host.split('.').any { it.startsWith("xn--") }) {
            raise(Risk.DANGER, "Web address uses look-alike characters to imitate a real site.")
        }
        // 4. insecure scheme
        if (scheme == "http") {
            if (looksSensitive) raise(Risk.DANGER, "Asks for login or payment over an insecure (http) connection.")
            else raise(Risk.CAUTION, "Not a secure (https) connection.")
        }
        // 5. link shortener: destination hidden
        if (host in SHORTENERS) {
            raise(Risk.CAUTION, "Shortened link — you can't see where it really goes.")
        }
        // 6. brand name on the wrong domain
        val reg = registrableDomain(host)
        if (reg !in BRAND_REAL_DOMAINS) {
            val brand = BRANDS.firstOrNull { b ->
                // brand appears as a label/subdomain or in the path, but not the registrable domain
                val inHost = host.split('.').dropLast(1).any { it.contains(b) }
                val inPath = path.contains(b)
                val regName = reg.substringBefore('.')
                (inHost || inPath) && regName != b
            }
            if (brand != null) {
                raise(if (scheme == "http" || looksSensitive) Risk.DANGER else Risk.CAUTION,
                    "Looks like it's pretending to be ${brand.replaceFirstChar { it.uppercase() }}, but the real site is “$reg”.")
            }
        }

        return Safety(risk, reasons)
    }

    private fun isIpHost(host: String): Boolean {
        if (host.isEmpty()) return false
        // IPv6 literal in brackets
        if (host.startsWith("[")) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } && (p.toIntOrNull() ?: 999) in 0..255 }
    }

    /** Crude registrable-domain guess: last two labels, with a bump for common 2-level TLDs. */
    private fun registrableDomain(host: String): String {
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return host
        val twoLevelTld = setOf("co", "com", "net", "org", "gov", "ac", "edu")
        val last = parts.last()
        val secondLast = parts[parts.size - 2]
        return if (secondLast in twoLevelTld && last.length <= 3 && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }
}
