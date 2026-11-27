package com.yaytif.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHasherTest {

    @Test
    fun normalizeToE164_prependsCountryCallingCodeForLocalNumber() {
        val result = PhoneHasher.normalizeToE164("11 98765-4321", "BR")
        assertEquals(PhoneNormalizationResult.Valid("+5511987654321"), result)
    }

    @Test
    fun normalizeToE164_stripsLeadingTrunkZeroForAfricanMarkets() {
        val nigeria = PhoneHasher.normalizeToE164("0803 123 4567", "NG")
        val kenya = PhoneHasher.normalizeToE164("0712-345-678", "KE")
        val southAfrica = PhoneHasher.normalizeToE164("(082) 123 4567", "ZA")

        assertEquals(PhoneNormalizationResult.Valid("+2348031234567"), nigeria)
        assertEquals(PhoneNormalizationResult.Valid("+254712345678"), kenya)
        assertEquals(PhoneNormalizationResult.Valid("+27821234567"), southAfrica)
    }

    @Test
    fun normalizeToE164_handlesExplicitPlusAndDoubleZeroPrefixes() {
        val withPlus = PhoneHasher.normalizeToE164("+57 300 123 4567", "BR")
        val withDoubleZero = PhoneHasher.normalizeToE164("0055 (11) 98765-4321", "US")

        assertEquals(PhoneNormalizationResult.Valid("+573001234567"), withPlus)
        assertEquals(PhoneNormalizationResult.Valid("+5511987654321"), withDoubleZero)
    }

    @Test
    fun normalizeToE164_stripsRtlAndBidiFormattingCharacters() {
        val bidiPhone = "\u202A+55 11 98765-4321\u202C\u200F"
        val result = PhoneHasher.normalizeToE164(bidiPhone, "BR")

        assertEquals(PhoneNormalizationResult.Valid("+5511987654321"), result)
    }

    @Test
    fun normalizeToE164_rejectsEmptyInvalidShortAndLongInputs() {
        assertEquals(PhoneNormalizationResult.Empty, PhoneHasher.normalizeToE164("   ", "BR"))
        assertEquals(
            PhoneNormalizationResult.InvalidCharacters,
            PhoneHasher.normalizeToE164("11-98765-ABCD", "BR")
        )
        assertEquals(
            PhoneNormalizationResult.TooShort,
            PhoneHasher.normalizeToE164("1234", "US")
        )
        assertEquals(
            PhoneNormalizationResult.TooLong,
            PhoneHasher.normalizeToE164("+551234567890123456", "BR")
        )
        assertEquals(
            PhoneNormalizationResult.InvalidCharacters,
            PhoneHasher.normalizeToE164("+0123456789", "US")
        )
    }

    @Test
    fun normalizeAndHash_producesDeterministicSha256HexMatchingEquivalentFormats() {
        val local = PhoneHasher.normalizeAndHash("(11) 98765-4321", "BR")
        val explicitE164 = PhoneHasher.normalizeAndHash("+5511987654321", "BR")
        val doubleZero = PhoneHasher.normalizeAndHash("0055 11 98765 4321", "US")

        assertTrue(local is PhoneHashResult.Success)
        assertTrue(explicitE164 is PhoneHashResult.Success)
        assertTrue(doubleZero is PhoneHashResult.Success)

        val localSuccess = local as PhoneHashResult.Success
        val explicitSuccess = explicitE164 as PhoneHashResult.Success
        val doubleZeroSuccess = doubleZero as PhoneHashResult.Success

        assertEquals(localSuccess.phoneHash, explicitSuccess.phoneHash)
        assertEquals(localSuccess.phoneHash, doubleZeroSuccess.phoneHash)
        assertEquals(64, localSuccess.phoneHash.length)
        assertTrue(localSuccess.phoneHash.matches(Regex("^[0-9a-f]{64}$")))
        assertFalse(localSuccess.phoneHash.contains("987654321"))
        // SHA-256("+5511987654321") verified vector
        assertEquals(
            "38225ec3dccec4189659c110ddc4f3dc9c27539850cb6a9ddae31ae03a5cf441",
            PhoneHasher.hashE164("+5511987654321")
        )
    }

    @Test
    fun normalizeAndHash_returnsInvalidForMalformedNumber() {
        val result = PhoneHasher.normalizeAndHash("abc", "NG")
        assertEquals(
            PhoneHashResult.Invalid(PhoneNormalizationResult.InvalidCharacters),
            result
        )
    }
}
