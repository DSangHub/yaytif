package com.yaytif.app.domain

import com.yaytif.app.data.remote.dto.PayoutRailDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PayoutRailValidatorTest {

    @Test
    fun `orderedRailsForCountry prioritizes regional rails for all primary markets and global fallback`() {
        assertEquals(
            listOf(PayoutRailDto.PIX, PayoutRailDto.MERCADO_PAGO, PayoutRailDto.PAYPAL),
            PayoutRailValidator.orderedRailsForCountry("BR")
        )
        assertEquals(
            listOf(PayoutRailDto.MERCADO_PAGO, PayoutRailDto.PAYPAL),
            PayoutRailValidator.orderedRailsForCountry("CO")
        )
        assertEquals(
            listOf(PayoutRailDto.MERCADO_PAGO, PayoutRailDto.PAYPAL),
            PayoutRailValidator.orderedRailsForCountry("AR")
        )
        assertEquals(
            listOf(PayoutRailDto.MERCADO_PAGO, PayoutRailDto.PAYPAL),
            PayoutRailValidator.orderedRailsForCountry("CL")
        )
        assertEquals(
            listOf(PayoutRailDto.PAYSTACK_OPAY, PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL),
            PayoutRailValidator.orderedRailsForCountry("NG")
        )
        assertEquals(
            listOf(PayoutRailDto.MPESA, PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL),
            PayoutRailValidator.orderedRailsForCountry("KE")
        )
        assertEquals(
            listOf(PayoutRailDto.MPESA, PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL),
            PayoutRailValidator.orderedRailsForCountry("TZ")
        )
        assertEquals(
            listOf(PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL),
            PayoutRailValidator.orderedRailsForCountry("GH")
        )
        assertEquals(
            listOf(PayoutRailDto.AIRTIME, PayoutRailDto.PAYPAL),
            PayoutRailValidator.orderedRailsForCountry("ZA")
        )
        assertEquals(
            listOf(PayoutRailDto.PAYPAL),
            PayoutRailValidator.orderedRailsForCountry("DE")
        )
    }

    @Test
    fun `PIX accepts CPF, CNPJ, email, E164 phone, and UUID EVP keys and rejects invalid inputs`() {
        assertEquals(
            DestinationValidationResult.Valid("12345678901"),
            PayoutRailValidator.validateDestination(PayoutRailDto.PIX, "123.456.789-01", "BR")
        )
        assertEquals(
            DestinationValidationResult.Valid("12345678000199"),
            PayoutRailValidator.validateDestination(PayoutRailDto.PIX, "12.345.678/0001-99", "BR")
        )
        assertEquals(
            DestinationValidationResult.Valid("joao@yaytif.com"),
            PayoutRailValidator.validateDestination(PayoutRailDto.PIX, "  JOAO@yaytif.com ", "BR")
        )
        assertEquals(
            DestinationValidationResult.Valid("+5511999887766"),
            PayoutRailValidator.validateDestination(PayoutRailDto.PIX, "+55 11 99988-7766", "BR")
        )
        assertEquals(
            DestinationValidationResult.Valid("550e8400-e29b-41d4-a716-446655440000"),
            PayoutRailValidator.validateDestination(
                PayoutRailDto.PIX,
                "550E8400-E29B-41D4-A716-446655440000",
                "BR"
            )
        )

        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.EMPTY),
            PayoutRailValidator.validateDestination(PayoutRailDto.PIX, "   ", "BR")
        )
        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_PIX_KEY),
            PayoutRailValidator.validateDestination(PayoutRailDto.PIX, "11111111111", "BR")
        )
        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_PIX_KEY),
            PayoutRailValidator.validateDestination(PayoutRailDto.PIX, "12345", "BR")
        )
        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_PIX_KEY),
            PayoutRailValidator.validateDestination(PayoutRailDto.PIX, "not-a-pix-key", "BR")
        )
    }

    @Test
    fun `MERCADO_PAGO validates email, 22-digit CVU, and alias`() {
        assertEquals(
            DestinationValidationResult.Valid("maria@mercadopago.com"),
            PayoutRailValidator.validateDestination(PayoutRailDto.MERCADO_PAGO, "Maria@MercadoPago.com", "AR")
        )
        assertEquals(
            DestinationValidationResult.Valid("0000003100012345678901"),
            PayoutRailValidator.validateDestination(
                PayoutRailDto.MERCADO_PAGO,
                "0000 0031 0001 2345 6789 01",
                "AR"
            )
        )
        assertEquals(
            DestinationValidationResult.Valid("maria.yaytif.mp"),
            PayoutRailValidator.validateDestination(PayoutRailDto.MERCADO_PAGO, "Maria.Yaytif.MP", "AR")
        )

        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_MERCADO_PAGO_DESTINATION),
            PayoutRailValidator.validateDestination(PayoutRailDto.MERCADO_PAGO, "ab", "AR")
        )
        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_MERCADO_PAGO_DESTINATION),
            PayoutRailValidator.validateDestination(PayoutRailDto.MERCADO_PAGO, "1234567890", "AR")
        )
        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_MERCADO_PAGO_DESTINATION),
            PayoutRailValidator.validateDestination(PayoutRailDto.MERCADO_PAGO, "bad..alias", "AR")
        )
    }

    @Test
    fun `MPESA accepts +254 and +255 E164 numbers and normalizes local 07 and 01 numbers`() {
        assertEquals(
            DestinationValidationResult.Valid("+254712345678"),
            PayoutRailValidator.validateDestination(PayoutRailDto.MPESA, "+254 712 345 678", "KE")
        )
        assertEquals(
            DestinationValidationResult.Valid("+254712345678"),
            PayoutRailValidator.validateDestination(PayoutRailDto.MPESA, "0712345678", "KE")
        )
        assertEquals(
            DestinationValidationResult.Valid("+254112345678"),
            PayoutRailValidator.validateDestination(PayoutRailDto.MPESA, "0112345678", "KE")
        )
        assertEquals(
            DestinationValidationResult.Valid("+255712345678"),
            PayoutRailValidator.validateDestination(PayoutRailDto.MPESA, "0712345678", "TZ")
        )
        assertEquals(
            DestinationValidationResult.Valid("+255612345678"),
            PayoutRailValidator.validateDestination(PayoutRailDto.MPESA, "+255612345678", "TZ")
        )

        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_MPESA_MSISDN),
            PayoutRailValidator.validateDestination(PayoutRailDto.MPESA, "+2348012345678", "KE")
        )
        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_MPESA_MSISDN),
            PayoutRailValidator.validateDestination(PayoutRailDto.MPESA, "07123", "KE")
        )
    }

    @Test
    fun `PAYSTACK_OPAY accepts 10-digit NUBAN and +234 wallet phone numbers`() {
        assertEquals(
            DestinationValidationResult.Valid("0123456789"),
            PayoutRailValidator.validateDestination(PayoutRailDto.PAYSTACK_OPAY, "0123456789", "NG")
        )
        assertEquals(
            DestinationValidationResult.Valid("+2348012345678"),
            PayoutRailValidator.validateDestination(PayoutRailDto.PAYSTACK_OPAY, "+234 801 234 5678", "NG")
        )
        assertEquals(
            DestinationValidationResult.Valid("+2348012345678"),
            PayoutRailValidator.validateDestination(PayoutRailDto.PAYSTACK_OPAY, "08012345678", "NG")
        )

        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_PAYSTACK_OPAY_ACCOUNT),
            PayoutRailValidator.validateDestination(PayoutRailDto.PAYSTACK_OPAY, "12345", "NG")
        )
        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_PAYSTACK_OPAY_ACCOUNT),
            PayoutRailValidator.validateDestination(PayoutRailDto.PAYSTACK_OPAY, "+254712345678", "NG")
        )
    }

    @Test
    fun `AIRTIME validates strict E164 mobile numbers`() {
        assertEquals(
            DestinationValidationResult.Valid("+233241234567"),
            PayoutRailValidator.validateDestination(PayoutRailDto.AIRTIME, "+233 24 123 4567", "GH")
        )
        assertEquals(
            DestinationValidationResult.Valid("+27821234567"),
            PayoutRailValidator.validateDestination(PayoutRailDto.AIRTIME, "+27821234567", "ZA")
        )

        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_AIRTIME_PHONE),
            PayoutRailValidator.validateDestination(PayoutRailDto.AIRTIME, "0241234567", "GH")
        )
        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_AIRTIME_PHONE),
            PayoutRailValidator.validateDestination(PayoutRailDto.AIRTIME, "+123", "GH")
        )
    }

    @Test
    fun `PAYPAL validates standard email addresses`() {
        assertEquals(
            DestinationValidationResult.Valid("user.name+yaytif@example.com"),
            PayoutRailValidator.validateDestination(PayoutRailDto.PAYPAL, "User.Name+Yaytif@Example.COM", "US")
        )

        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_PAYPAL_EMAIL),
            PayoutRailValidator.validateDestination(PayoutRailDto.PAYPAL, "user@localhost", "US")
        )
        assertEquals(
            DestinationValidationResult.Invalid(DestinationErrorReason.INVALID_PAYPAL_EMAIL),
            PayoutRailValidator.validateDestination(PayoutRailDto.PAYPAL, "not-an-email", "US")
        )
    }

    @Test
    fun `validateAmount enforces currency minimum thresholds and overdraft bounds with BigDecimal`() {
        // BRL minimum is 5.00
        assertEquals(BigDecimal("5.00"), PayoutRailValidator.minimumWithdrawalAmount("BRL"))
        // NGN minimum is 500.00
        assertEquals(BigDecimal("500.00"), PayoutRailValidator.minimumWithdrawalAmount("NGN"))
        // KES minimum is 100.00
        assertEquals(BigDecimal("100.00"), PayoutRailValidator.minimumWithdrawalAmount("KES"))

        val valid = PayoutRailValidator.validateAmount("5.00", BigDecimal("5.000000"), "BRL")
        assertTrue(valid is PayoutValidationResult.Valid)
        assertEquals(BigDecimal("5.00"), (valid as PayoutValidationResult.Valid).amount)

        val belowMin = PayoutRailValidator.validateAmount("4.999999", BigDecimal("50.000000"), "BRL")
        assertTrue(belowMin is PayoutValidationResult.BelowMinimum)

        val overdraft = PayoutRailValidator.validateAmount("10.000001", BigDecimal("10.000000"), "BRL")
        assertTrue(overdraft is PayoutValidationResult.InsufficientBalance)

        val zero = PayoutRailValidator.validateAmount("0", BigDecimal("50.00"), "BRL")
        assertEquals(PayoutValidationResult.MustBePositive, zero)

        val negative = PayoutRailValidator.validateAmount("-5.00", BigDecimal("50.00"), "BRL")
        assertEquals(PayoutValidationResult.MustBePositive, negative)

        val badScale = PayoutRailValidator.validateAmount("10.1234567", BigDecimal("50.00"), "BRL")
        assertEquals(PayoutValidationResult.TooManyDecimals, badScale)
    }

    @Test
    fun `calculateQuickAmount computes 25 percent, 50 percent, and Max without exceeding balance`() {
        assertEquals(BigDecimal("25.00"), PayoutRailValidator.calculateQuickAmount(BigDecimal("100.000000"), 25))
        assertEquals(BigDecimal("50.00"), PayoutRailValidator.calculateQuickAmount(BigDecimal("100.000000"), 50))
        assertEquals(BigDecimal("100.00"), PayoutRailValidator.calculateQuickAmount(BigDecimal("100.000000"), 100))
        // RoundingMode.DOWN ensures quick amount never exceeds available balance
        assertEquals(BigDecimal("2.50"), PayoutRailValidator.calculateQuickAmount(BigDecimal("10.01"), 25))
        assertEquals(BigDecimal("0.00"), PayoutRailValidator.calculateQuickAmount(BigDecimal.ZERO, 50))
    }

    @Test
    fun `maskDestination obscures sensitive digits and email locals for payout history`() {
        assertEquals("jo•••@yaytif.com", PayoutRailValidator.maskDestination(PayoutRailDto.PAYPAL, "joao@yaytif.com"))
        assertEquals("+254••••5678", PayoutRailValidator.maskDestination(PayoutRailDto.MPESA, "+254712345678"))
        assertEquals("01••••6789", PayoutRailValidator.maskDestination(PayoutRailDto.PAYSTACK_OPAY, "0123456789"))
        assertEquals("12••••8901", PayoutRailValidator.maskDestination(PayoutRailDto.PIX, "12345678901"))
    }
}
