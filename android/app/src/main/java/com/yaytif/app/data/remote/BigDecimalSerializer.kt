package com.yaytif.app.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * Serializes monetary values strictly as exact decimal strings using [BigDecimal].
 * Floating-point types (Float/Double) are never used in conversion.
 */
object BigDecimalSerializer : KSerializer<BigDecimal> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.yaytif.app.BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        val raw = if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            val primitive = element as? JsonPrimitive
                ?: throw SerializationException("Expected JSON primitive for BigDecimal, got: $element")
            primitive.content
        } else {
            decoder.decodeString()
        }.trim()

        if (raw.isEmpty() || raw.equals("NaN", ignoreCase = true) || raw.equals("Infinity", ignoreCase = true) || raw.equals("-Infinity", ignoreCase = true)) {
            throw SerializationException("Invalid decimal monetary value: '$raw'")
        }

        return try {
            BigDecimal(raw)
        } catch (ex: NumberFormatException) {
            throw SerializationException("Malformed decimal monetary value: '$raw'", ex)
        }
    }
}
