package com.yaytif.app.domain

import com.yaytif.app.data.remote.dto.PayoutRailDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class MoneyFormatterAndMarketTest {

    @Test
    fun `formats monetary values using BigDecimal without floating point rounding bugs`() {
        assertEquals("R$ 14.50", MoneyFormatter.formatAmount(BigDecimal("14.500000"), "BRL"))
        assertEquals("₦ 4250.00", MoneyFormatter.formatAmount("4250.000000", "NGN"))
        assertEquals("KSh 4.50", MoneyFormatter.formatAmount("4.500000", "KES"))
        assertEquals("50%", MoneyFormatter.formatCommissionPercent("0.5000"))
    }

    @Test
    fun `validates payout requests strictly against minimum and available BigDecimal balance`() {
        val balance = BigDecimal("25.000000")
        val min = BigDecimal("5.00")

        val valid = MoneyFormatter.validatePayoutAmount("12.50", balance, min)
        assertTrue(valid is PayoutValidationResult.Valid)
        assertEquals(BigDecimal("12.50"), (valid as PayoutValidationResult.Valid).amount)

        assertTrue(
            MoneyFormatter.validatePayoutAmount("3.00", balance, min) is PayoutValidationResult.BelowMinimum
        )
        assertTrue(
            MoneyFormatter.validatePayoutAmount("30.00", balance, min) is PayoutValidationResult.InsufficientBalance
        )
        assertEquals(
            PayoutValidationResult.TooManyDecimals,
            MoneyFormatter.validatePayoutAmount("10.1234567", balance, min)
        )
        assertEquals(
            PayoutValidationResult.InvalidFormat,
            MoneyFormatter.validatePayoutAmount("abc", balance, min)
        )
    }

    @Test
    fun `supports all 13 required locales with Arabic RTL flag`() {
        val tags = MarketConfig.SUPPORTED_LOCALES.map { it.languageTag }
        assertEquals(13, tags.size)
        assertEquals(
            listOf("en", "pt-BR", "es", "fr", "sw", "yo", "ha", "ig", "zu", "af", "ar", "hi", "id"),
            tags
        )
        assertTrue(MarketConfig.isRtlLocale("ar"))
        assertTrue(MarketConfig.isRtlLocale("ar-EG"))
        assertFalse(MarketConfig.isRtlLocale("en"))
        assertFalse(MarketConfig.isRtlLocale("pt-BR"))
    }

    @Test
    fun `maps regional markets to expected local payout rails`() {
        assertTrue(MarketConfig.payoutRailsForCountry("BR").contains(PayoutRailDto.PIX))
        assertTrue(MarketConfig.payoutRailsForCountry("CO").contains(PayoutRailDto.MERCADO_PAGO))
        assertTrue(MarketConfig.payoutRailsForCountry("KE").contains(PayoutRailDto.MPESA))
        assertTrue(MarketConfig.payoutRailsForCountry("NG").contains(PayoutRailDto.PAYSTACK_OPAY))
        assertTrue(MarketConfig.payoutRailsForCountry("GH").contains(PayoutRailDto.AIRTIME))
        assertEquals(listOf(PayoutRailDto.PAYPAL), MarketConfig.payoutRailsForCountry("US"))
    }
}
