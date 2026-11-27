package com.yaytif.app.data.remote.dto

import com.yaytif.app.data.local.entity.SponsoredAlertEntity
import com.yaytif.app.data.remote.BigDecimalSerializer
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class SponsoredAlertDto(
    val id: String,
    val advertiserName: String,
    val title: String,
    val body: String,
    val category: String,
    val countryCodes: List<String> = emptyList(),
    val priorityColor: String = "#10B981",
    @Serializable(with = BigDecimalSerializer::class)
    val payoutPerMinute: BigDecimal,
    val affiliateUrl: String,
    @Serializable(with = BigDecimalSerializer::class)
    val commissionShare: BigDecimal,
    @Serializable(with = BigDecimalSerializer::class)
    val budgetRemaining: BigDecimal,
    val active: Boolean = true,
    val createdAt: String = ""
) {
    fun toEntity(syncedAtEpochMs: Long): SponsoredAlertEntity = SponsoredAlertEntity(
        id = id,
        advertiserName = advertiserName,
        title = title,
        body = body,
        category = category,
        countryCodes = countryCodes,
        priorityColor = priorityColor,
        payoutPerMinute = payoutPerMinute.toPlainString(),
        affiliateUrl = affiliateUrl,
        commissionShare = commissionShare.toPlainString(),
        budgetRemaining = budgetRemaining.toPlainString(),
        active = active,
        createdAtIso = createdAt,
        syncedAtEpochMs = syncedAtEpochMs
    )
}

@Serializable
data class AlertsResponseDto(
    val alerts: List<SponsoredAlertDto> = emptyList()
)
