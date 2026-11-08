package com.binteum.app.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppPalette(
    val dark: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val text: Int,
    val textMuted: Int,
    val accent: Int,
    val onAccent: Int,
    val border: Int,
    val warning: Int,
    val warningSurface: Int,
)

object AppTheme {
    // Muted text on the darkest light surface has a calculated ratio of 4.71:1.
    // Accent is a filled-surface color; use onAccent for text/icons on it.
    private val light = AppPalette(
        dark = false,
        background = Color.rgb(245, 246, 240),
        surface = Color.WHITE,
        surfaceAlt = Color.rgb(236, 239, 230),
        text = Color.rgb(32, 40, 32),
        textMuted = Color.rgb(94, 109, 97),
        accent = Color.rgb(201, 245, 143),
        onAccent = Color.rgb(21, 32, 15),
        border = Color.rgb(216, 224, 210),
        warning = Color.rgb(141, 74, 23),
        warningSurface = Color.rgb(255, 240, 220),
    )

    private val dark = AppPalette(
        dark = true,
        background = Color.rgb(16, 20, 17),
        surface = Color.rgb(26, 32, 28),
        surfaceAlt = Color.rgb(21, 26, 22),
        text = Color.rgb(241, 244, 237),
        textMuted = Color.rgb(163, 177, 167),
        accent = Color.rgb(201, 245, 143),
        onAccent = Color.rgb(21, 32, 15),
        border = Color.rgb(52, 67, 56),
        warning = Color.rgb(255, 195, 134),
        warningSurface = Color.rgb(51, 40, 27),
    )

    fun palette(context: Context, mode: ThemeMode): AppPalette {
        val useDark = when (mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        return if (useDark) dark else light
    }
}
