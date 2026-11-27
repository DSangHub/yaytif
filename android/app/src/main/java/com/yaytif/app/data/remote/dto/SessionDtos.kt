package com.yaytif.app.data.remote.dto

import com.yaytif.app.data.remote.BigDecimalSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.math.BigDecimal

@Serializable
data class StartSessionRequest(
    val userId: String,
    val alertId: String
)

@Serializable
data class StartSessionResponse(
    val sessionId: String,
    val clickId: String,
    val resolvedAffiliateUrl: String
)

@Serializable
data class SessionHeartbeatRequest(
    val activeSecondsDelta: Int
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SessionHeartbeatResponse(
    val sessionId: String,
    @JsonNames("totalActiveSeconds")
    val activeSeconds: Int = 0,
    val activeSecondsDelta: Int = 0,
    @Serializable(with = BigDecimalSerializer::class)
    val creditedDelta: BigDecimal = BigDecimal.ZERO,
    @JsonNames("totalCreditedAmount")
    @Serializable(with = BigDecimalSerializer::class)
    val sessionCreditedTotal: BigDecimal = BigDecimal.ZERO,
    @Serializable(with = BigDecimalSerializer::class)
    val walletBalance: BigDecimal = BigDecimal.ZERO,
    @Serializable(with = BigDecimalSerializer::class)
    val dailyTimeEarned: BigDecimal = BigDecimal.ZERO,
    @Serializable(with = BigDecimalSerializer::class)
    val dailyCap: BigDecimal? = null,
    val dailyCapResetAt: String? = null,
    val dailyCapReached: Boolean = false,
    @Serializable(with = BigDecimalSerializer::class)
    val alertBudgetRemaining: BigDecimal? = null,
    val alertActive: Boolean = true,
    val ledgerEntryId: String? = null
)
