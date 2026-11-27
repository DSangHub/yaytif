package com.yaytif.app.domain

import com.yaytif.app.data.remote.dto.PayoutRailDto
import java.math.BigDecimal
import java.math.RoundingMode

sealed interface DestinationValidationResult {
    data class Valid(val normalizedDestination: String) : DestinationValidationResult
    data class Invalid(val reason: DestinationErrorReason) : DestinationValidationResult
}

enum class DestinationErrorReason {
    EMPTY,
    INVALID_PIX_KEY,
    INVALID_MERCADO_PAGO_DESTINATION,
    INVALID_MPESA_MSISDN,
    INVALID_PAYSTACK_OPAY_ACCOUNT,
    INVALID_AIRTIME_PHONE,
    INVALID_PAYPAL_EMAIL
}

/**
 * Regional payout rail ordering, strict destination validation, currency-specific
 * BigDecimal withdrawal thresholds, and sensitive destination masking.
 */
object PayoutRailValidator {

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val E164_PHONE_REGEX = Regex("^\\+[1-9]\\d{7,14}$")
    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    private val CPF_FORMATTED_REGEX = Regex("^\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}$")
    private val CNPJ_FORMATTED_REGEX = Regex("^\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}$")
    private val CVU_REGEX = Regex("^\\d{22}$")
    private val MERCADO_PAGO_ALIAS_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]{4,18})[A-Za-z0-9]$")
    private val MPESA_E164_REGEX = Regex("^\\+(?:254[17]|255[67])\\d{8}$")
    private val MPESA_LOCAL_REGEX = Regex("^0[167]\\d{8}$")
    private val NUBAN_REGEX = Regex("^\\d{10}$")
    private val NIGERIA_PHONE_REGEX = Regex("^\\+234[789]\\d{9}$")
    private val NIGERIA_LOCAL_PHONE_REGEX = Regex("^0[789]\\d{9}$")

    private val HUNDRED = BigDecimal("100")

    fun orderedRailsForCountry(countryCode: String): List<PayoutRailDto> {
        return when (countryCode.trim().uppercase()) {
            "BR" -> listOf(PayoutRailDto.PIX, PayoutRailDto.MERCADO_PAGO, PayoutRailDto.PAYPAL)
            "CO", "AR", "CL" -> listOf(PayoutRailDto.MERCADO_PAGO, PayoutRailDto.PAYPAL)
            "NG" -> listOf(PayoutRailDto.PAYSTACK_OPAY, PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL)
            "KE", "TZ" -> listOf(PayoutRailDto.MPESA, PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL)
            "GH", "ZA" -> listOf(PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL)
            else -> listOf(PayoutRailDto.PAYPAL)
        }
    }

    fun minimumWithdrawalAmount(currencyCode: String): BigDecimal {
        return MarketConfig.marketForCurrency(currencyCode).minimumPayoutAmount
    }

    fun dailyAttentionCap(currencyCode: String): BigDecimal {
        return MarketConfig.marketForCurrency(currencyCode).dailyAttentionCap
    }

    fun validateAmount(
        rawAmount: String,
        availableBalance: BigDecimal,
        currencyCode: String
    ): PayoutValidationResult {
        return MoneyFormatter.validatePayoutAmount(
            rawAmount = rawAmount,
            availableBalance = availableBalance,
            minimumAmount = minimumWithdrawalAmount(currencyCode)
        )
    }

    fun calculateQuickAmount(availableBalance: BigDecimal, percentage: Int): BigDecimal {
        require(percentage in 1..100) { "percentage must be between 1 and 100" }
        if (availableBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.DOWN)
        }
        return if (percentage == 100) {
            availableBalance.setScale(2, RoundingMode.DOWN)
        } else {
            availableBalance
                .multiply(BigDecimal(percentage))
                .divide(HUNDRED, 2, RoundingMode.DOWN)
        }
    }

    fun validateDestination(
        rail: PayoutRailDto,
        rawDestination: String,
        countryCode: String? = null
    ): DestinationValidationResult {
        val trimmed = rawDestination.trim()
        if (trimmed.isEmpty()) {
            return DestinationValidationResult.Invalid(DestinationErrorReason.EMPTY)
        }

        return when (rail) {
            PayoutRailDto.PIX -> validatePixDestination(trimmed)
            PayoutRailDto.MERCADO_PAGO -> validateMercadoPagoDestination(trimmed)
            PayoutRailDto.MPESA -> validateMpesaDestination(trimmed, countryCode)
            PayoutRailDto.PAYSTACK_OPAY -> validatePaystackOpayDestination(trimmed)
            PayoutRailDto.AIRTIME -> validateAirtimeDestination(trimmed)
            PayoutRailDto.PAYPAL -> validatePayPalDestination(trimmed)
        }
    }

    private fun validatePixDestination(input: String): DestinationValidationResult {
        if (EMAIL_REGEX.matches(input)) {
            return DestinationValidationResult.Valid(input.lowercase())
        }
        if (UUID_REGEX.matches(input)) {
            return DestinationValidationResult.Valid(input.lowercase())
        }

        val compactPhone = stripPhoneSeparators(input)
        if (E164_PHONE_REGEX.matches(compactPhone)) {
            return DestinationValidationResult.Valid(compactPhone)
        }

        if (CPF_FORMATTED_REGEX.matches(input)) {
            val digits = input.filter { it.isDigit() }
            if (digits.length == 11 && !allSameDigit(digits)) {
                return DestinationValidationResult.Valid(digits)
            }
        }

        if (CNPJ_FORMATTED_REGEX.matches(input)) {
            val digits = input.filter { it.isDigit() }
            if (digits.length == 14 && !allSameDigit(digits)) {
                return DestinationValidationResult.Valid(digits)
            }
        }

        return DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_PIX_KEY)
    }

    private fun validateMercadoPagoDestination(input: String): DestinationValidationResult {
        if (EMAIL_REGEX.matches(input)) {
            return DestinationValidationResult.Valid(input.lowercase())
        }

        val compactDigits = input.replace(" ", "").replace("-", "")
        if (CVU_REGEX.matches(compactDigits) && !allSameDigit(compactDigits)) {
            return DestinationValidationResult.Valid(compactDigits)
        }

        val hasConsecutivePunctuation = input.contains("..") || input.contains("--") ||
            input.contains(".-") || input.contains("-.")
        val hasLetter = input.any { it.isLetter() }
        if (!hasConsecutivePunctuation && hasLetter && MERCADO_PAGO_ALIAS_REGEX.matches(input)) {
            return DestinationValidationResult.Valid(input.lowercase())
        }

        return DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_MERCADO_PAGO_DESTINATION)
    }

    private fun validateMpesaDestination(
        input: String,
        countryCode: String?
    ): DestinationValidationResult {
        val compact = stripPhoneSeparators(input)

        val candidate = when {
            compact.startsWith("+") -> compact
            compact.startsWith("254") || compact.startsWith("255") -> "+$compact"
            MPESA_LOCAL_REGEX.matches(compact) -> {
                val dialPrefix = if (countryCode.equals("TZ", ignoreCase = true)) "+255" else "+254"
                dialPrefix + compact.substring(1)
            }
            else -> compact
        }

        return if (MPESA_E164_REGEX.matches(candidate)) {
            DestinationValidationResult.Valid(candidate)
        } else {
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_MPESA_MSISDN)
        }
    }

    private fun validatePaystackOpayDestination(input: String): DestinationValidationResult {
        val compact = stripPhoneSeparators(input)

        if (NUBAN_REGEX.matches(compact) && !allSameDigit(compact)) {
            return DestinationValidationResult.Valid(compact)
        }

        val phoneCandidate = when {
            compact.startsWith("234") && !compact.startsWith("+") -> "+$compact"
            NIGERIA_LOCAL_PHONE_REGEX.matches(compact) -> "+234" + compact.substring(1)
            else -> compact
        }

        if (NIGERIA_PHONE_REGEX.matches(phoneCandidate)) {
            return DestinationValidationResult.Valid(phoneCandidate)
        }

        return DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_PAYSTACK_OPAY_ACCOUNT)
    }

    private fun validateAirtimeDestination(input: String): DestinationValidationResult {
        val compact = stripPhoneSeparators(input)
        return if (E164_PHONE_REGEX.matches(compact)) {
            DestinationValidationResult.Valid(compact)
        } else {
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_AIRTIME_PHONE)
        }
    }

    private fun validatePayPalDestination(input: String): DestinationValidationResult {
        return if (EMAIL_REGEX.matches(input)) {
            DestinationValidationResult.Valid(input.lowercase())
        } else {
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_PAYPAL_EMAIL)
        }
    }

    fun maskDestination(rail: PayoutRailDto, destination: String): String {
        val trimmed = destination.trim()
        if (trimmed.isEmpty()) return ""

        if (trimmed.contains("@")) {
            val parts = trimmed.split("@", limit = 2)
            val local = parts[0]
            val domain = parts[1]
            val visiblePrefix = local.take(2.coerceAtMost(local.length))
            return "$visiblePrefix•••@$domain"
        }

        if (rail == PayoutRailDto.PIX && UUID_REGEX.matches(trimmed)) {
            return "${trimmed.take(8)}-••••-${trimmed.takeLast(4)}"
        }

        if (trimmed.startsWith("+") && trimmed.length > 7) {
            return "${trimmed.take(4)}••••${trimmed.takeLast(4)}"
        }

        if (trimmed.length >= 8) {
            return "${trimmed.take(2)}••••${trimmed.takeLast(4)}"
        }

        if (trimmed.length > 3) {
            return "${trimmed.take(2)}•••${trimmed.takeLast(1)}"
        }

        return "•••"
    }

    private fun stripPhoneSeparators(value: String): String {
        return value.replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
    }

    private fun allSameDigit(digits: String): Boolean {
        return digits.isNotEmpty() && digits.all { it == digits[0] }
    }
}
