package com.yaytif.app.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Exact decimal monetary formatter and validator backed strictly by [BigDecimal].
 */
object MoneyFormatter {

    private val HUNDRED = BigDecimal("100")

    fun parseDecimalOrNull(input: String): BigDecimal? {
        val normalized = input.trim().replace(",", ".")
        if (normalized.isEmpty()) return null
        return runCatching { BigDecimal(normalized) }.getOrNull()
    }

    fun formatAmount(amount: BigDecimal, currencyCode: String, fractionDigits: Int = 2): String {
        val scaled = amount.setScale(fractionDigits, RoundingMode.HALF_UP)
        val symbol = currencySymbol(currencyCode)
        return "$symbol ${scaled.toPlainString()}"
    }

    fun formatAmount(amountString: String, currencyCode: String, fractionDigits: Int = 2): String {
        val parsed = parseDecimalOrNull(amountString) ?: BigDecimal.ZERO
        return formatAmount(parsed, currencyCode, fractionDigits)
    }

    fun formatCommissionPercent(commissionShare: String): String {
        val parsed = parseDecimalOrNull(commissionShare) ?: BigDecimal.ZERO
        val percent = parsed.multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP)
        return "${percent.toPlainString()}%"
    }

    fun validatePayoutAmount(
        rawAmount: String,
        availableBalance: BigDecimal,
        minimumAmount: BigDecimal = BigDecimal("1.00")
    ): PayoutValidationResult {
        val parsed = parseDecimalOrNull(rawAmount)
            ?: return PayoutValidationResult.InvalidFormat
        if (parsed.scale() > 6) {
            return PayoutValidationResult.TooManyDecimals
        }
        if (parsed.compareTo(BigDecimal.ZERO) <= 0) {
            return PayoutValidationResult.MustBePositive
        }
        if (parsed.compareTo(minimumAmount) < 0) {
            return PayoutValidationResult.BelowMinimum(minimumAmount)
        }
        if (parsed.compareTo(availableBalance) > 0) {
            return PayoutValidationResult.InsufficientBalance(availableBalance)
        }
        return PayoutValidationResult.Valid(parsed.setScale(6, RoundingMode.DOWN).stripTrailingZeros().let {
            if (it.scale() < 2) it.setScale(2, RoundingMode.UNNECESSARY) else it
        })
    }

    fun currencySymbol(currencyCode: String): String = when (currencyCode.uppercase()) {
        "BRL" -> "R$"
        "COP", "ARS", "CLP", "USD" -> "$"
        "NGN" -> "₦"
        "KES", "TZS" -> "KSh"
        "GHS" -> "GH₵"
        "ZAR" -> "R"
        "INR" -> "₹"
        "IDR" -> "Rp"
        else -> currencyCode.uppercase()
    }
}

sealed interface PayoutValidationResult {
    data class Valid(val amount: BigDecimal) : PayoutValidationResult
    data object InvalidFormat : PayoutValidationResult
    data object TooManyDecimals : PayoutValidationResult
    data object MustBePositive : PayoutValidationResult
    data class BelowMinimum(val minimum: BigDecimal) : PayoutValidationResult
    data class InsufficientBalance(val available: BigDecimal) : PayoutValidationResult
}
