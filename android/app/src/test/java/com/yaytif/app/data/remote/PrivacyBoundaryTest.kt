package com.yaytif.app.data.remote

import com.yaytif.app.data.local.entity.LocalNotificationEntity
import com.yaytif.app.data.remote.dto.AlertsResponseDto
import com.yaytif.app.data.remote.dto.CreatePayoutRequest
import com.yaytif.app.data.remote.dto.CreatePayoutResponseDto
import com.yaytif.app.data.remote.dto.LedgerEntryDto
import com.yaytif.app.data.remote.dto.LedgerPaginationDto
import com.yaytif.app.data.remote.dto.PayoutListResponseDto
import com.yaytif.app.data.remote.dto.PayoutRequestDto
import com.yaytif.app.data.remote.dto.RegisterUserRequest
import com.yaytif.app.data.remote.dto.SessionHeartbeatRequest
import com.yaytif.app.data.remote.dto.SessionHeartbeatResponse
import com.yaytif.app.data.remote.dto.SponsoredAlertDto
import com.yaytif.app.data.remote.dto.StartSessionRequest
import com.yaytif.app.data.remote.dto.StartSessionResponse
import com.yaytif.app.data.remote.dto.UpdateInterestsRequest
import com.yaytif.app.data.remote.dto.UserProfileDto
import com.yaytif.app.data.remote.dto.WalletLedgerResponseDto
import com.yaytif.app.data.remote.dto.WalletSummaryDto
import com.yaytif.app.data.repository.LocalNotificationRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Enforces .cursorrules Privacy Invariant #1:
 * Personal intercepted notifications stay strictly inside local Room storage and
 * MUST NEVER appear in any network DTO, API endpoint, or network-coupled repository.
 */
class PrivacyBoundaryTest {

    private val forbiddenPersonalFields = setOf(
        "senderName",
        "sender_name",
        "packageName",
        "package_name",
        "notificationKey",
        "notification_key",
        "appLabel",
        "app_label"
    )

    private val allNetworkDtos = listOf(
        RegisterUserRequest::class.java,
        UpdateInterestsRequest::class.java,
        UserProfileDto::class.java,
        SponsoredAlertDto::class.java,
        AlertsResponseDto::class.java,
        StartSessionRequest::class.java,
        StartSessionResponse::class.java,
        SessionHeartbeatRequest::class.java,
        SessionHeartbeatResponse::class.java,
        LedgerEntryDto::class.java,
        WalletSummaryDto::class.java,
        LedgerPaginationDto::class.java,
        WalletLedgerResponseDto::class.java,
        CreatePayoutRequest::class.java,
        CreatePayoutResponseDto::class.java,
        PayoutRequestDto::class.java,
        PayoutListResponseDto::class.java
    )

    @Test
    fun `no network DTO references LocalNotificationEntity or personal notification fields`() {
        for (dtoClass in allNetworkDtos) {
            for (field in dtoClass.declaredFields) {
                assertNotEquals(
                    "DTO ${dtoClass.simpleName} must never reference LocalNotificationEntity",
                    LocalNotificationEntity::class.java,
                    field.type
                )
                assertFalse(
                    "DTO ${dtoClass.simpleName} must not contain personal notification field '${field.name}'",
                    field.name in forbiddenPersonalFields
                )
                assertFalse(
                    "DTO ${dtoClass.simpleName} must not use Float or Double for financial fields ('${field.name}')",
                    field.type == Float::class.java ||
                        field.type == Double::class.java ||
                        field.type == java.lang.Float::class.java ||
                        field.type == java.lang.Double::class.java
                )
            }
        }
    }

    @Test
    fun `LocalNotificationRepository has zero dependency on YaytifApiService`() {
        val constructors = LocalNotificationRepository::class.java.constructors
        for (ctor in constructors) {
            for (paramType in ctor.parameterTypes) {
                assertNotEquals(
                    "LocalNotificationRepository must never depend on YaytifApiService",
                    YaytifApiService::class.java,
                    paramType
                )
            }
        }
    }
}
