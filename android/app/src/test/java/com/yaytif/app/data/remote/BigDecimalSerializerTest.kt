package com.yaytif.app.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class BigDecimalSerializerTest {

    @Serializable
    private data class MoneyBox(
        @Serializable(with = BigDecimalSerializer::class)
        val amount: BigDecimal
    )

    private val json = YaytifApiFactory.createJson()

    @Test
    fun `serializes BigDecimal as exact plain decimal string`() {
        val box = MoneyBox(BigDecimal("14.500000"))
        val encoded = json.encodeToString(box)
        assertEquals("""{"amount":"14.500000"}""", encoded)
    }

    @Test
    fun `deserializes quoted decimal string preserving 6 decimal precision`() {
        val decoded = json.decodeFromString<MoneyBox>("""{"amount":"0.180000"}""")
        assertEquals(0, BigDecimal("0.180000").compareTo(decoded.amount))
        assertEquals("0.180000", decoded.amount.toPlainString())
    }

    @Test
    fun `deserializes unquoted json decimal literal without floating point conversion`() {
        val decoded = json.decodeFromString<MoneyBox>("""{"amount":4250.123456}""")
        assertEquals(BigDecimal("4250.123456"), decoded.amount)
    }

    @Test
    fun `rejects NaN and malformed monetary strings`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<MoneyBox>("""{"amount":"NaN"}""")
        }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<MoneyBox>("""{"amount":"not-a-number"}""")
        }
    }
}
