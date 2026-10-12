package com.qrlens.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for the link/payment flagger. No Android dependencies, so they run on the
 * JVM with `./gradlew test`. QrContent is constructed directly (uri is unused by SafetyCheck).
 */
class SafetyCheckTest {

    private fun link(url: String): QrContent {
        val kind = if (url.contains("://") && !url.startsWith("http")) Kind.APP_LINK else Kind.LINK
        return QrContent(url, kind, "L", url, url, null, "txt", "text/plain", url)
    }

    private fun upi(raw: String, title: String) =
        QrContent(raw, Kind.UPI, "UPI", title, "", null, "txt", "text/plain", raw)

    private fun risk(c: QrContent) = SafetyCheck.check(c).risk

    @Test fun rawIpIsDanger() =
        assertEquals(Risk.DANGER, risk(link("http://192.168.43.17:8080/login")))

    @Test fun atTrickIsDanger() =
        assertEquals(Risk.DANGER, risk(link("https://sbi.co.in@secure-verify.example/login")))

    @Test fun punycodeIsDanger() =
        assertEquals(Risk.DANGER, risk(link("https://xn--pytm-xqa.example/pay")))

    @Test fun httpLoginIsDanger() =
        assertEquals(Risk.DANGER, risk(link("http://icici-netbanking-secure.example/signin")))

    @Test fun brandOnSensitiveDomainIsDanger() =
        assertEquals(Risk.DANGER, risk(link("https://paytm.account-verify.example/kyc")))

    @Test fun shortenerIsCaution() =
        assertEquals(Risk.CAUTION, risk(link("https://bit.ly/3xRq9Zk")))

    @Test fun brandOnPlainWrongDomainIsCaution() =
        assertEquals(Risk.CAUTION, risk(link("https://paytm.deals-today.example/sale")))

    @Test fun plainHttpIsCaution() =
        assertEquals(Risk.CAUTION, risk(link("http://example.org/page")))

    @Test fun upiNormalIsCaution() =
        assertEquals(Risk.CAUTION, risk(upi("upi://pay?pa=ram@okaxis&pn=Ram&am=50", "Ram")))

    @Test fun upiLureIsDanger() =
        assertEquals(Risk.DANGER, risk(upi("upi://pay?pa=x@okaxis&pn=You%20Won&am=4999", "You Won")))

    // ---- false-positive guards: these must stay NONE ----

    @Test fun realRepoIsNone() =
        assertEquals(Risk.NONE, risk(link("https://github.com/CoolNinja2009/QRLens")))

    @Test fun realPaytmIsNone() =
        assertEquals(Risk.NONE, risk(link("https://paytm.com/")))

    @Test fun realSbiIsNone() =
        assertEquals(Risk.NONE, risk(link("https://www.onlinesbi.sbi/login")))

    @Test fun amazonSubdomainIsNone() =
        assertEquals(Risk.NONE, risk(link("https://www.amazon.in/deal")))

    @Test fun newsArticleIsNone() =
        assertEquals(Risk.NONE, risk(link("https://www.thehindu.com/news/national/article12345.ece")))

    @Test fun paytmSubstringNotTreatedAsPayWord() =
        // "paytm" must not match the sensitive word "pay"
        assertEquals(Risk.CAUTION, risk(link("https://paytm.deals-today.example/sale")))

    @Test fun nonLinkKindsAreNone() {
        assertEquals(Risk.NONE, risk(QrContent("hi", Kind.TEXT, "", "", "", null, "txt", "text/plain", "hi")))
        assertEquals(Risk.NONE, risk(QrContent("WIFI:...", Kind.WIFI, "", "", "", null, "txt", "text/plain", "")))
    }
}
