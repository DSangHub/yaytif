package com.yaytif.app.data.remote.dto

import com.yaytif.app.data.remote.BigDecimalSerializer
import kotlinx.serialization.Serializable
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

@Serializable
data class SessionHeartbeatResponse(
    val sessionId: String,
    val totalActiveSeconds: Int,
    @Serializable(with = BigDecimalSerializer::class)
    val creditedDelta: BigDecimal,
    @Serializable(with = BigDecimalSerializer::class)
    val totalCreditedAmount: BigDecimal,
    @Serializable(with = BigDecimalSerializer::class)
    val walletBalance: BigDecimal,
    val dailyCapReached: Boolean = false
)
