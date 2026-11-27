package com.yaytif.app.service

import com.yaytif.app.data.local.entity.LocalNotificationEntity

/**
 * Pure JVM representation of an Android StatusBarNotification used for
 * deterministic, unit-testable extraction into on-device Room storage.
 *
 * PRIVACY INVARIANT (.cursorrules):
 * Instances of this class MUST NEVER be logged or transmitted over the network.
 */
data class RawNotificationPayload(
    val packageName: String,
    val appLabel: String? = null,
    val statusBarKey: String? = null,
    val notificationId: Int = 0,
    val tag: String? = null,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val conversationTitle: String? = null,
    val senderName: String? = null,
    val category: String? = null,
    val postTimeEpochMs: Long = 0L,
    val isOngoing: Boolean = false,
    val isForegroundService: Boolean = false,
    val isGroupSummary: Boolean = false
)

/**
 * Pure notification filter and normalizer for Yaytif's on-device vault.
 */
object NotificationExtractor {

    const val YAYTIF_PACKAGE_NAME = "com.yaytif.app"
    const val MAX_TITLE_LENGTH = 256
    const val MAX_BODY_LENGTH = 2000
    const val MAX_SENDER_LENGTH = 128

    private const val COLOR_EMERALD = "#10B981"
    private const val COLOR_BLUE = "#3B82F6"
    private const val COLOR_AMBER = "#F59E0B"
    private const val COLOR_RED = "#EF4444"

    fun extract(
        payload: RawNotificationPayload,
        selfPackageName: String = YAYTIF_PACKAGE_NAME
    ): LocalNotificationEntity? {
        val pkg = payload.packageName.trim()
        if (pkg.isEmpty()) return null
        if (pkg.equals(selfPackageName, ignoreCase = true)) return null
        if (payload.isOngoing || payload.isForegroundService || payload.isGroupSummary) return null

        val resolvedBody = firstNonBlank(payload.bigText, payload.text)
            ?.let { normalize(it, MAX_BODY_LENGTH) }
            .orEmpty()

        val resolvedTitle = firstNonBlank(payload.title, payload.conversationTitle, payload.subText)
            ?.let { normalize(it, MAX_TITLE_LENGTH) }
            .orEmpty()

        if (resolvedTitle.isEmpty() && resolvedBody.isEmpty()) {
            return null
        }

        val resolvedSender = resolveSender(
            explicitSender = payload.senderName,
            conversationTitle = payload.conversationTitle,
            subText = payload.subText,
            resolvedTitle = resolvedTitle
        )

        val resolvedAppLabel = payload.appLabel
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: pkg

        val resolvedCategory = payload.category
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val stableKey = buildStableDeduplicationKey(
            packageName = pkg,
            statusBarKey = payload.statusBarKey,
            tag = payload.tag,
            notificationId = payload.notificationId,
            postTimeEpochMs = payload.postTimeEpochMs
        )

        return LocalNotificationEntity(
            notificationKey = stableKey,
            packageName = pkg,
            appLabel = resolvedAppLabel,
            senderName = resolvedSender,
            title = resolvedTitle.ifEmpty { resolvedAppLabel },
            body = resolvedBody,
            category = resolvedCategory,
            priorityColor = priorityColorForCategory(resolvedCategory),
            receivedAtEpochMs = payload.postTimeEpochMs,
            isRead = false,
            isArchived = false
        )
    }

    internal fun buildStableDeduplicationKey(
        packageName: String,
        statusBarKey: String?,
        tag: String?,
        notificationId: Int,
        postTimeEpochMs: Long
    ): String {
        val base = statusBarKey
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "$packageName|${tag.orEmpty()}|$notificationId"
        return "$base|$postTimeEpochMs"
    }

    private fun resolveSender(
        explicitSender: String?,
        conversationTitle: String?,
        subText: String?,
        resolvedTitle: String
    ): String? {
        val candidate = firstNonBlank(explicitSender, conversationTitle, subText)
            ?.let { normalize(it, MAX_SENDER_LENGTH) }
            ?: return null
        return candidate.takeUnless { it.equals(resolvedTitle, ignoreCase = true) }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    private fun normalize(raw: String, maxLength: Int): String {
        val trimmed = raw.trim()
        return if (trimmed.length <= maxLength) trimmed else trimmed.substring(0, maxLength)
    }

    private fun priorityColorForCategory(category: String?): String = when (category?.lowercase()) {
        "msg", "call" -> COLOR_EMERALD
        "email", "social" -> COLOR_BLUE
        "reminder", "event" -> COLOR_AMBER
        "alarm", "err" -> COLOR_RED
        else -> COLOR_EMERALD
    }
}
