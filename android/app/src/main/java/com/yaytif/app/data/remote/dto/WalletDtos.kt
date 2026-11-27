package com.yaytif.app.data.remote.dto

import com.yaytif.app.data.remote.BigDecimalSerializer
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
enum class LedgerEntryTypeDto {
    ATTENTION_TIME,
    AFFILIATE_CASHBACK,
    PAYOUT_WITHDRAWAL,
    REFUND
}

@Serializable
enum class PayoutRailDto {
    PIX,
    MERCADO_PAGO,
    MPESA,
    PAYSTACK_OPAY,
    AIRTIME,
    PAYPAL
}

@Serializable
enum class PayoutStatusDto {
    PENDING,
    PROCESSING,
    PAID,
    FAILED
}

@Serializable
data class LedgerEntryDto(
    val id: String,
    val userId: String,
    val type: LedgerEntryTypeDto,
    @Serializable(with = BigDecimalSerializer::class)
    val amount: BigDecimal,
    val currency: String,
    val referenceId: String? = null,
    val createdAt: String
)

@Serializable
data class WalletLedgerResponseDto(
    val userId: String,
    val currency: String,
    @Serializable(with = BigDecimalSerializer::class)
    val walletBalance: BigDecimal,
    @Serializable(with = BigDecimalSerializer::class)
    val dailyTimeEarned: BigDecimal,
    val entries: List<LedgerEntryDto> = emptyList()
)

@Serializable
data class CreatePayoutRequest(
    val userId: String,
    val rail: PayoutRailDto,
    val destination: String,
    @Serializable(with = BigDecimalSerializer::class)
    val amount: BigDecimal
)

@Serializable
data class PayoutRequestDto(
    val id: String,
    val userId: String,
    val rail: PayoutRailDto,
    val destination: String,
    @Serializable(with = BigDecimalSerializer::class)
    val amount: BigDecimal,
    val currency: String,
    val status: PayoutStatusDto,
    val externalTxId: String? = null,
    val createdAt: String
)

@Serializable
data class PayoutListResponseDto(
    val payouts: List<PayoutRequestDto> = emptyList()
)
