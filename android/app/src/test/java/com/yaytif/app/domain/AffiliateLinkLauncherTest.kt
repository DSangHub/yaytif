package com.yaytif.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AffiliateLinkLauncherTest {

    @Test
    fun `accepts valid https affiliate URL with resolved click_id`() {
        val result = AffiliateLinkLauncher.validateAffiliateUrl(
            "  https://track.yaytif.com/c/ifood-br?click_id=clk_987654&utm_source=yaytif  "
        )
        assertTrue(result is AffiliateUrlValidationResult.Valid)
        assertEquals(
            "https://track.yaytif.com/c/ifood-br?click_id=clk_987654&utm_source=yaytif",
            (result as AffiliateUrlValidationResult.Valid).normalizedUrl
        )
    }

    @Test
    fun `rejects unresolved click_id placeholder in raw or percent-encoded form`() {
        val raw = AffiliateLinkLauncher.validateAffiliateUrl(
            "https://track.yaytif.com/c/ifood-br?click_id={click_id}"
        )
        assertEquals(
            AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.UNRESOLVED_CLICK_ID_MACRO),
            raw
        )

        val encoded = AffiliateLinkLauncher.validateAffiliateUrl(
            "https://track.yaytif.com/c/ifood-br?click_id=%7Bclick_id%7D"
        )
        assertEquals(
            AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.UNRESOLVED_CLICK_ID_MACRO),
            encoded
        )
    }

    @Test
    fun `rejects non-https schemes such as http, javascript, and intent`() {
        assertEquals(
            AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.INVALID_SCHEME),
            AffiliateLinkLauncher.validateAffiliateUrl("http://track.yaytif.com/c/offer?click_id=123")
        )
        assertEquals(
            AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.INVALID_SCHEME),
            AffiliateLinkLauncher.validateAffiliateUrl("javascript:alert(1)")
        )
        assertEquals(
            AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.INVALID_SCHEME),
            AffiliateLinkLauncher.validateAffiliateUrl("intent://evil.example/#Intent;end")
        )
    }

    @Test
    fun `rejects blank, missing-host, and malformed URLs`() {
        assertEquals(
            AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.BLANK),
            AffiliateLinkLauncher.validateAffiliateUrl(null)
        )
        assertEquals(
            AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.BLANK),
            AffiliateLinkLauncher.validateAffiliateUrl("   ")
        )
        assertEquals(
            AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.MISSING_HOST),
            AffiliateLinkLauncher.validateAffiliateUrl("https:///path-without-host")
        )
        assertEquals(
            AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.MALFORMED_URI),
            AffiliateLinkLauncher.validateAffiliateUrl("https://track.yaytif.com/bad path with spaces")
        )
    }
}
