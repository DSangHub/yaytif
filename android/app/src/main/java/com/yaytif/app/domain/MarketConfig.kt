package com.yaytif.app.domain

import com.yaytif.app.data.remote.dto.PayoutRailDto
import java.math.BigDecimal

data class SupportedLocale(
    val languageTag: String,
    val nativeDisplayName: String,
    val isRtl: Boolean = false
)

data class MarketProfile(
    val countryCode: String,
    val displayName: String,
    val currency: String,
    val callingCode: String,
    val defaultLocaleTag: String,
    val supportedRails: List<PayoutRailDto>,
    val minimumPayoutAmount: BigDecimal
)

object MarketConfig {

    const val DEFAULT_COUNTRY_CODE = "BR"
    const val DEFAULT_LOCALE_TAG = "en"
    const val DEFAULT_CURRENCY = "BRL"

    val DEFAULT_INTERESTS: Set<String> = setOf("ecommerce", "fintech", "food_delivery")

    val AVAILABLE_CATEGORIES: List<String> = listOf(
        "ecommerce",
        "fintech",
        "food_delivery",
        "telecom",
        "entertainment",
        "travel"
    )

    /**
     * 13 locales covering Yaytif's LATAM, Africa, and global markets, including Arabic (RTL).
     */
    val SUPPORTED_LOCALES: List<SupportedLocale> = listOf(
        SupportedLocale(languageTag = "en", nativeDisplayName = "English"),
        SupportedLocale(languageTag = "pt-BR", nativeDisplayName = "Português (Brasil)"),
        SupportedLocale(languageTag = "es", nativeDisplayName = "Español"),
        SupportedLocale(languageTag = "fr", nativeDisplayName = "Français"),
        SupportedLocale(languageTag = "sw", nativeDisplayName = "Kiswahili"),
        SupportedLocale(languageTag = "yo", nativeDisplayName = "Yorùbá"),
        SupportedLocale(languageTag = "ha", nativeDisplayName = "Hausa"),
        SupportedLocale(languageTag = "ig", nativeDisplayName = "Igbo"),
        SupportedLocale(languageTag = "zu", nativeDisplayName = "isiZulu"),
        SupportedLocale(languageTag = "af", nativeDisplayName = "Afrikaans"),
        SupportedLocale(languageTag = "ar", nativeDisplayName = "العربية", isRtl = true),
        SupportedLocale(languageTag = "hi", nativeDisplayName = "हिन्दी"),
        SupportedLocale(languageTag = "id", nativeDisplayName = "Bahasa Indonesia")
    )

    val SUPPORTED_MARKETS: List<MarketProfile> = listOf(
        MarketProfile(
            countryCode = "BR",
            displayName = "Brazil",
            currency = "BRL",
            callingCode = "+55",
            defaultLocaleTag = "pt-BR",
            supportedRails = listOf(PayoutRailDto.PIX, PayoutRailDto.MERCADO_PAGO, PayoutRailDto.PAYPAL),
            minimumPayoutAmount = BigDecimal("5.00")
        ),
        MarketProfile(
            countryCode = "CO",
            displayName = "Colombia",
            currency = "COP",
            callingCode = "+57",
            defaultLocaleTag = "es",
            supportedRails = listOf(PayoutRailDto.MERCADO_PAGO, PayoutRailDto.PAYPAL),
            minimumPayoutAmount = BigDecimal("5000.00")
        ),
        MarketProfile(
            countryCode = "AR",
            displayName = "Argentina",
            currency = "ARS",
            callingCode = "+54",
            defaultLocaleTag = "es",
            supportedRails = listOf(PayoutRailDto.MERCADO_PAGO, PayoutRailDto.PAYPAL),
            minimumPayoutAmount = BigDecimal("1000.00")
        ),
        MarketProfile(
            countryCode = "CL",
            displayName = "Chile",
            currency = "CLP",
            callingCode = "+56",
            defaultLocaleTag = "es",
            supportedRails = listOf(PayoutRailDto.MERCADO_PAGO, PayoutRailDto.PAYPAL),
            minimumPayoutAmount = BigDecimal("1000.00")
        ),
        MarketProfile(
            countryCode = "NG",
            displayName = "Nigeria",
            currency = "NGN",
            callingCode = "+234",
            defaultLocaleTag = "en",
            supportedRails = listOf(PayoutRailDto.PAYSTACK_OPAY, PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL),
            minimumPayoutAmount = BigDecimal("500.00")
        ),
        MarketProfile(
            countryCode = "KE",
            displayName = "Kenya",
            currency = "KES",
            callingCode = "+254",
            defaultLocaleTag = "sw",
            supportedRails = listOf(PayoutRailDto.MPESA, PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL),
            minimumPayoutAmount = BigDecimal("100.00")
        ),
        MarketProfile(
            countryCode = "GH",
            displayName = "Ghana",
            currency = "GHS",
            callingCode = "+233",
            defaultLocaleTag = "en",
            supportedRails = listOf(PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL),
            minimumPayoutAmount = BigDecimal("10.00")
        ),
        MarketProfile(
            countryCode = "ZA",
            displayName = "South Africa",
            currency = "ZAR",
            callingCode = "+27",
            defaultLocaleTag = "en",
            supportedRails = listOf(PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL),
            minimumPayoutAmount = BigDecimal("20.00")
        ),
        MarketProfile(
            countryCode = "TZ",
            displayName = "Tanzania",
            currency = "TZS",
            callingCode = "+255",
            defaultLocaleTag = "sw",
            supportedRails = listOf(PayoutRailDto.MPESA, PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL),
            minimumPayoutAmount = BigDecimal("2000.00")
        ),
        MarketProfile(
            countryCode = "US",
            displayName = "Global (USD)",
            currency = "USD",
            callingCode = "+1",
            defaultLocaleTag = "en",
            supportedRails = listOf(PayoutRailDto.PAYPAL),
            minimumPayoutAmount = BigDecimal("1.00")
        )
    )

    fun isRtlLocale(languageTag: String): Boolean {
        val base = languageTag.substringBefore("-").lowercase()
        return base == "ar" || SUPPORTED_LOCALES.any { it.languageTag.equals(languageTag, ignoreCase = true) && it.isRtl }
    }

    fun marketForCountry(countryCode: String): MarketProfile {
        return SUPPORTED_MARKETS.firstOrNull { it.countryCode.equals(countryCode, ignoreCase = true) }
            ?: SUPPORTED_MARKETS.last()
    }

    fun payoutRailsForCountry(countryCode: String): List<PayoutRailDto> {
        return marketForCountry(countryCode).supportedRails
    }
}
