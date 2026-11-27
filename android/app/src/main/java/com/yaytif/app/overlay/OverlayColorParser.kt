package com.yaytif.app.overlay

/**
 * Pure JVM hex color parser for sponsored alert priority accents with safe fallback to `#10B981`.
 */
object OverlayColorParser {

    const val DEFAULT_ACCENT_HEX = "#10B981"
    const val DEFAULT_ACCENT_ARGB: Int = 0xFF10B981.toInt()

    fun parsePriorityColorArgb(
        hexColor: String?,
        fallbackArgb: Int = DEFAULT_ACCENT_ARGB
    ): Int {
        val raw = hexColor?.trim().orEmpty()
        if (!raw.startsWith("#")) {
            return fallbackArgb
        }

        val digits = raw.substring(1)
        val expanded = when (digits.length) {
            3 -> {
                val r = digits[0]
                val g = digits[1]
                val b = digits[2]
                "FF$r$r$g$g$b$b"
            }
            6 -> "FF$digits"
            8 -> digits
            else -> return fallbackArgb
        }

        if (!expanded.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return fallbackArgb
        }

        val parsedLong = expanded.toLongOrNull(16) ?: return fallbackArgb
        return parsedLong.toInt()
    }

    /**
     * Integer-only luminance check (no Float/Double) to select contrasting foreground text color.
     */
    fun isDarkColor(argb: Int): Boolean {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val perceivedBrightness = (299 * r) + (587 * g) + (114 * b)
        return perceivedBrightness < 128_000
    }
}
