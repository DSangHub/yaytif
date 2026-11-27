package com.yaytif.app.domain

import java.security.MessageDigest

sealed interface PhoneNormalizationResult {
    data class Valid(val e164: String) : PhoneNormalizationResult
    data object Empty : PhoneNormalizationResult
    data object InvalidCharacters : PhoneNormalizationResult
    data object TooShort : PhoneNormalizationResult
    data object TooLong : PhoneNormalizationResult
}

sealed interface PhoneHashResult {
    data class Success(val phoneHash: String) : PhoneHashResult
    data class Invalid(val reason: PhoneNormalizationResult) : PhoneHashResult
}

/**
 * Client-side E.164 phone normalization and SHA-256 hashing.
 *
 * PRIVACY INVARIANT (.cursorrules):
 * Raw phone numbers must never be transmitted to Yaytif backend endpoints.
 * Only the resulting 64-character lowercase hex SHA-256 [phoneHash] is sent.
 */
object PhoneHasher {

    private const val MIN_E164_DIGITS = 7
    private const val MAX_E164_DIGITS = 15

    private val E164_REGEX = Regex("^\\+[1-9]\\d{6,14}$")

    // Directional marks and visual separators commonly inserted by phone keyboards / RTL locales.
    private val IGNORED_FORMAT_CHARS = setOf(
        ' ', '-', '(', ')', '.', '\t', '\n', '\r',
        '\u00A0', '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069'
    )

    fun normalizeToE164(
        rawInput: String,
        defaultCountryCode: String = MarketConfig.DEFAULT_COUNTRY_CODE
    ): PhoneNormalizationResult {
        val cleaned = buildString(rawInput.length) {
            for (ch in rawInput) {
                if (ch !in IGNORED_FORMAT_CHARS) {
                    append(ch)
                }
            }
        }

        if (cleaned.isEmpty()) {
            return PhoneNormalizationResult.Empty
        }

        val normalizedInput = when {
            cleaned.startsWith("+") -> cleaned
            cleaned.startsWith("00") -> "+${cleaned.drop(2)}"
            else -> {
                if (!cleaned.all { it.isDigit() }) {
                    return PhoneNormalizationResult.InvalidCharacters
                }
                val callingDigits = MarketConfig.marketForCountry(defaultCountryCode)
                    .callingCode
                    .removePrefix("+")
                val subscriberDigits = cleaned.dropWhile { it == '0' }
                if (subscriberDigits.isEmpty()) {
                    return PhoneNormalizationResult.TooShort
                }
                "+$callingDigits$subscriberDigits"
            }
        }

        val digitsOnly = normalizedInput.removePrefix("+")
        if (digitsOnly.isEmpty()) {
            return PhoneNormalizationResult.TooShort
        }
        if (!digitsOnly.all { it.isDigit() }) {
            return PhoneNormalizationResult.InvalidCharacters
        }
        if (digitsOnly.startsWith("0")) {
            return PhoneNormalizationResult.InvalidCharacters
        }
        if (digitsOnly.length < MIN_E164_DIGITS) {
            return PhoneNormalizationResult.TooShort
        }
        if (digitsOnly.length > MAX_E164_DIGITS) {
            return PhoneNormalizationResult.TooLong
        }

        val e164 = "+$digitsOnly"
        return if (E164_REGEX.matches(e164)) {
            PhoneNormalizationResult.Valid(e164)
        } else {
            PhoneNormalizationResult.InvalidCharacters
        }
    }

    fun hashE164(e164Phone: String): String {
        require(E164_REGEX.matches(e164Phone)) {
            "Phone number must be valid E.164 before hashing"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(e164Phone.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    fun normalizeAndHash(
        rawInput: String,
        defaultCountryCode: String = MarketConfig.DEFAULT_COUNTRY_CODE
    ): PhoneHashResult {
        return when (val normalized = normalizeToE164(rawInput, defaultCountryCode)) {
            is PhoneNormalizationResult.Valid -> PhoneHashResult.Success(hashE164(normalized.e164))
            else -> PhoneHashResult.Invalid(normalized)
        }
    }
}
