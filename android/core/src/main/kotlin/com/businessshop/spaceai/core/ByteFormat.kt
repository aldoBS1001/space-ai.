package com.businessshop.spaceai.core

import java.util.Locale

/**
 * Formato de tamaños con base 1000 (la misma que usan Ajustes de iOS y Android),
 * para que las cifras de SPACE AI coincidan con las del sistema.
 */
object ByteFormat {
    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    fun format(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 0) return "-" + format(-bytes, locale)
        if (bytes < 1000) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1000 && unit < UNITS.lastIndex) { value /= 1000; unit++ }
        val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
        return String.format(locale, pattern, value, UNITS[unit])
    }
}
