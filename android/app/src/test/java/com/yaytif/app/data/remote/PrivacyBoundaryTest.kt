package com.yaytif.app.data.remote

import com.yaytif.app.data.local.entity.LocalNotificationEntity
import com.yaytif.app.data.remote.dto.AlertsResponseDto
import com.yaytif.app.data.remote.dto.CreatePayoutRequest
import com.yaytif.app.data.remote.dto.LedgerEntryDto
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
import com.yaytif.app.data.repository.LocalNotificationRepository
import com.yaytif.app.service.NotificationExtractor
import com.yaytif.app.service.YaytifNotificationListenerService
import com.yaytif.app.worker.LocalVaultRetentionWorker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces .cursorrules Privacy Invariant #1:
 * Personal intercepted notifications stay strictly inside local Room storage and
 * MUST NEVER appear in any network DTO, API endpoint, network-coupled repository,
 * or log statements inside the listener / extractor / local retention worker.
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
        WalletLedgerResponseDto::class.java,
        CreatePayoutRequest::class.java,
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

    @Test
    fun `listener service, extractor, and local retention worker have zero network or logging references`() {
        val inspectedClasses = listOf(
            YaytifNotificationListenerService::class.java,
            NotificationExtractor::class.java,
            LocalVaultRetentionWorker::class.java
        )

        val forbiddenTypePrefixes = listOf(
            "retrofit2.",
            "okhttp3.",
            "com.yaytif.app.data.remote.",
            "android.util.Log",
            "java.util.logging."
        )

        for (clazz in inspectedClasses) {
            for (field in clazz.declaredFields) {
                val typeName = field.type.name
                assertFalse(
                    "${clazz.simpleName} field '${field.name}' must never reference forbidden type $typeName",
                    forbiddenTypePrefixes.any { typeName.startsWith(it) }
                )
            }
            for (ctor in clazz.declaredConstructors) {
                for (param in ctor.parameterTypes) {
                    val typeName = param.name
                    assertFalse(
                        "${clazz.simpleName} constructor must never depend on forbidden type $typeName",
                        forbiddenTypePrefixes.any { typeName.startsWith(it) }
                    )
                }
            }
        }

        val sourcePaths = listOf(
            "com/yaytif/app/service/YaytifNotificationListenerService.kt",
            "com/yaytif/app/service/NotificationExtractor.kt",
            "com/yaytif/app/worker/LocalVaultRetentionWorker.kt"
        )

        val forbiddenSourcePatterns = listOf(
            Regex("""\bimport\s+retrofit2\."""),
            Regex("""\bimport\s+okhttp3\."""),
            Regex("""\bYaytifApiService\b"""),
            Regex("""\bcom\.yaytif\.app\.data\.remote\b"""),
            Regex("""\bandroid\.util\.Log\b"""),
            Regex("""\bLog\.[vdiwea]\s*\("""),
            Regex("""\bLog\.wtf\s*\("""),
            Regex("""\bjava\.util\.logging\b"""),
            Regex("""\bprintln\s*\("""),
            Regex("""\bSystem\.(out|err)\.print""")
        )

        for (relativePath in sourcePaths) {
            val file = resolveMainKotlinSource(relativePath)
            assertTrue("Expected source file to exist: $relativePath", file.exists())
            val source = file.readText()
            for (pattern in forbiddenSourcePatterns) {
                assertFalse(
                    "Privacy boundary violation in $relativePath matching pattern '${pattern.pattern}'",
                    pattern.containsMatchIn(source)
                )
            }
        }
    }

    private fun resolveMainKotlinSource(relativePath: String): File {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(workingDir, "src/main/java/$relativePath"),
            File(workingDir, "app/src/main/java/$relativePath")
        )
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }
}
