package com.binteum.app.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.binteum.core.BackgroundMode

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
    val flat: Boolean = false,
)

object AppTheme {
    private val light = AppPalette(
        dark = false,
        background = Color.rgb(243, 240, 248),
        surface = Color.argb(112, 0, 0, 0),
        surfaceAlt = Color.argb(80, 0, 0, 0),
        text = Color.rgb(248, 245, 255),
        textMuted = Color.rgb(218, 214, 226),
        accent = Color.argb(160, 0, 0, 0),
        onAccent = Color.rgb(248, 248, 250),
        border = Color.argb(38, 255, 255, 255),
        warning = Color.rgb(255, 205, 156),
        warningSurface = Color.argb(112, 0, 0, 0),
    )

    private val dark = AppPalette(
        dark = true,
        background = Color.rgb(31, 37, 52),
        surface = Color.argb(112, 0, 0, 0),
        surfaceAlt = Color.argb(80, 0, 0, 0),
        text = Color.rgb(248, 245, 255),
        textMuted = Color.rgb(197, 189, 211),
        accent = Color.argb(160, 0, 0, 0),
        onAccent = Color.rgb(248, 248, 250),
        border = Color.argb(38, 255, 255, 255),
        warning = Color.rgb(255, 205, 156),
        warningSurface = Color.argb(112, 0, 0, 0),
    )

    private val black = dark.copy(background = Color.BLACK, surface = 0xFF141414.toInt(),
        surfaceAlt = 0xFF242424.toInt(), text = 0xFFF5F5F5.toInt(), textMuted = 0xFFB8B8B8.toInt(),
        accent = 0xFF343434.toInt(), border = 0xFF4A4A4A.toInt(), flat = true)
    private val white = light.copy(background = Color.WHITE, surface = 0xFFF4F4F4.toInt(),
        surfaceAlt = 0xFFE7E7E7.toInt(), text = 0xFF171717.toInt(), textMuted = 0xFF555555.toInt(),
        accent = 0xFF262626.toInt(), onAccent = Color.WHITE, border = 0xFFB8B8B8.toInt(),
        warning = 0xFF8A4700.toInt(), warningSurface = 0xFFFFE9CD.toInt(), flat = true)

    fun palette(context: Context, mode: ThemeMode, backgroundMode: BackgroundMode = BackgroundMode.SYSTEM): AppPalette {
        val useDark = when (mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        if (backgroundMode.isFlat) return if (useDark) black else white
        return if (useDark) dark else light
    }
}
