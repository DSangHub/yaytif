package com.yaytif.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal

/**
 * Local Room cache for active sponsored alert campaigns synced from /api/v1/alerts.
 * All monetary amounts are persisted as exact decimal strings (never Float/Double).
 */
@Entity(
    tableName = "sponsored_alerts",
    indices = [
        Index(value = ["active", "category"]),
        Index(value = ["synced_at_epoch_ms"])
    ]
)
data class SponsoredAlertEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "advertiser_name")
    val advertiserName: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "body")
    val body: String,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "country_codes")
    val countryCodes: List<String>,

    @ColumnInfo(name = "priority_color")
    val priorityColor: String = "#10B981",

    @ColumnInfo(name = "payout_per_minute")
    val payoutPerMinute: String,

    @ColumnInfo(name = "affiliate_url")
    val affiliateUrl: String,

    @ColumnInfo(name = "commission_share")
    val commissionShare: String,

    @ColumnInfo(name = "budget_remaining")
    val budgetRemaining: String,

    @ColumnInfo(name = "active")
    val active: Boolean = true,

    @ColumnInfo(name = "created_at_iso")
    val createdAtIso: String,

    @ColumnInfo(name = "synced_at_epoch_ms")
    val syncedAtEpochMs: Long
) {
    fun payoutPerMinuteDecimal(): BigDecimal = BigDecimal(payoutPerMinute)

    fun commissionShareDecimal(): BigDecimal = BigDecimal(commissionShare)

    fun budgetRemainingDecimal(): BigDecimal = BigDecimal(budgetRemaining)
}
