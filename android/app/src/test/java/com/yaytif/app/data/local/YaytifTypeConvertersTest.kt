package com.yaytif.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class YaytifTypeConvertersTest {

    private val converters = YaytifTypeConverters()

    @Test
    fun `round-trips country code lists cleanly`() {
        val original = listOf("BR", "CO", "NG", "KE")
        val encoded = converters.fromStringList(original)
        assertEquals(original, converters.toStringList(encoded))
    }

    @Test
    fun `handles empty and malformed serialized strings safely`() {
        assertEquals(emptyList<String>(), converters.toStringList(""))
        assertEquals(emptyList<String>(), converters.toStringList("   "))
        assertEquals(emptyList<String>(), converters.toStringList("not-valid-json"))
    }
}
