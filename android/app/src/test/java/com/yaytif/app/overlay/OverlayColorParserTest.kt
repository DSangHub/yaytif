package com.yaytif.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayColorParserTest {

    @Test
    fun `parses 6-digit hex color with full alpha`() {
        assertEquals(0xFF10B981.toInt(), OverlayColorParser.parsePriorityColorArgb("#10B981"))
        assertEquals(0xFFFF6B00.toInt(), OverlayColorParser.parsePriorityColorArgb("  #ff6b00  "))
    }

    @Test
    fun `parses 3-digit shorthand hex color`() {
        assertEquals(0xFF00AAFF.toInt(), OverlayColorParser.parsePriorityColorArgb("#0AF"))
        assertEquals(0xFFFFFFFF.toInt(), OverlayColorParser.parsePriorityColorArgb("#fff"))
    }

    @Test
    fun `parses 8-digit ARGB hex color`() {
        assertEquals(0xCC1E293B.toInt(), OverlayColorParser.parsePriorityColorArgb("#CC1E293B"))
    }

    @Test
    fun `falls back safely to emerald #10B981 for null, blank, or malformed colors`() {
        val fallback = OverlayColorParser.DEFAULT_ACCENT_ARGB
        assertEquals(0xFF10B981.toInt(), fallback)
        assertEquals(fallback, OverlayColorParser.parsePriorityColorArgb(null))
        assertEquals(fallback, OverlayColorParser.parsePriorityColorArgb(""))
        assertEquals(fallback, OverlayColorParser.parsePriorityColorArgb("10B981"))
        assertEquals(fallback, OverlayColorParser.parsePriorityColorArgb("#12345"))
        assertEquals(fallback, OverlayColorParser.parsePriorityColorArgb("#ZZZZZZ"))
        assertEquals(fallback, OverlayColorParser.parsePriorityColorArgb("rgb(16,185,129)"))
    }

    @Test
    fun `isDarkColor distinguishes dark and light accent colors using integer luminance`() {
        assertTrue(OverlayColorParser.isDarkColor(0xFF0F172A.toInt()))
        assertTrue(OverlayColorParser.isDarkColor(0xFF1E3A8A.toInt()))
        assertFalse(OverlayColorParser.isDarkColor(0xFF10B981.toInt()))
        assertFalse(OverlayColorParser.isDarkColor(0xFFFACC15.toInt()))
        assertFalse(OverlayColorParser.isDarkColor(0xFFFFFFFF.toInt()))
    }
}
