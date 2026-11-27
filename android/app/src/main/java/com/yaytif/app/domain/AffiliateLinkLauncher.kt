package com.yaytif.app.domain

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI

enum class AffiliateUrlInvalidReason {
    BLANK,
    UNRESOLVED_CLICK_ID_MACRO,
    INVALID_SCHEME,
    MISSING_HOST,
    MALFORMED_URI
}

sealed interface AffiliateUrlValidationResult {
    data class Valid(val normalizedUrl: String) : AffiliateUrlValidationResult
    data class Invalid(val reason: AffiliateUrlInvalidReason) : AffiliateUrlValidationResult
}

/**
 * Validates and launches resolved `{click_id}` affiliate attribution URLs safely via [Intent.ACTION_VIEW].
 */
object AffiliateLinkLauncher {

    private val UNRESOLVED_MACRO_PATTERNS = listOf(
        "{click_id}",
        "%7bclick_id%7d"
    )

    fun validateAffiliateUrl(rawUrl: String?): AffiliateUrlValidationResult {
        val candidate = rawUrl?.trim().orEmpty()
        if (candidate.isEmpty()) {
            return AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.BLANK)
        }

        val lower = candidate.lowercase()
        if (UNRESOLVED_MACRO_PATTERNS.any { lower.contains(it) }) {
            return AffiliateUrlValidationResult.Invalid(
                AffiliateUrlInvalidReason.UNRESOLVED_CLICK_ID_MACRO
            )
        }

        val parsed = runCatching { URI(candidate) }.getOrNull()
            ?: return AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.MALFORMED_URI)

        if (!parsed.scheme.equals("https", ignoreCase = true)) {
            return AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.INVALID_SCHEME)
        }

        val host = parsed.host
        if (host.isNullOrBlank()) {
            return AffiliateUrlValidationResult.Invalid(AffiliateUrlInvalidReason.MISSING_HOST)
        }

        return AffiliateUrlValidationResult.Valid(parsed.toASCIIString())
    }

    fun openAffiliateLink(context: Context, resolvedAffiliateUrl: String?): Boolean {
        val validation = validateAffiliateUrl(resolvedAffiliateUrl)
        if (validation !is AffiliateUrlValidationResult.Valid) {
            return false
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validation.normalizedUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
