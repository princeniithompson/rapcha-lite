package com.princeniithompson.rapcha.ui.components.overlay

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Dynamic Aurora Color Palette extracted directly from the user's Android 12+ wallpaper Monet palette.
 * v26 keeps only the v24 default "luminous" tone (no tone picker). Below Android 12 it uses the
 * fixed v24 emerald palette.
 */
data class AuroraColorPalette(
    val primary: Color,
    val primaryLight: Color,
    val primaryVibrant: Color,
    val secondary: Color,
    val deep: Color
)

/** The fixed palette used below Android 12 and in tests. */
val DefaultAuroraPalette = AuroraColorPalette(
    primary = Color(0xFF10B981),
    primaryLight = Color(0xFF6EE7B7),
    primaryVibrant = Color(0xFF34D399),
    secondary = Color(0xFF06B6D4),
    deep = Color(0xFF064E3B)
)

fun getDynamicTonePalette(context: Context): AuroraColorPalette {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return DefaultAuroraPalette
    return try {
        val c200 = ContextCompat.getColor(context, android.R.color.system_accent1_200)
        val c300 = ContextCompat.getColor(context, android.R.color.system_accent1_300)
        val c400 = ContextCompat.getColor(context, android.R.color.system_accent1_400)
        val c600 = ContextCompat.getColor(context, android.R.color.system_accent1_600)
        val c2_300 = ContextCompat.getColor(context, android.R.color.system_accent2_300)
        AuroraColorPalette(
            primary = Color(c400),
            primaryLight = Color(c200),
            primaryVibrant = Color(c300),
            secondary = Color(c2_300),
            deep = Color(c600)
        )
    } catch (e: Throwable) {
        val darkDyn = dynamicDarkColorScheme(context)
        AuroraColorPalette(
            primary = darkDyn.primary,
            primaryLight = darkDyn.primaryContainer,
            primaryVibrant = darkDyn.primary,
            secondary = darkDyn.secondary,
            deep = darkDyn.surfaceTint
        )
    }
}

@Composable
fun rememberDynamicAuroraPalette(): AuroraColorPalette {
    val context = LocalContext.current
    return remember(context) { getDynamicTonePalette(context) }
}

fun isColorDark(color: Color): Boolean {
    val darkness = 1 - (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue)
    return darkness >= 0.5
}

fun AuroraColorPalette.toDangerPalette(): AuroraColorPalette = AuroraColorPalette(
    primary = Color(0xFFD32F2F),
    primaryLight = Color(0xFFFF5252),
    primaryVibrant = Color(0xFFFF1744),
    secondary = Color(0xFFFF5252),
    deep = Color(0xFFB71C1C)
)
