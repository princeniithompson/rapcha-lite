package com.princeniithompson.rapcha.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.hct.Hct
import com.materialkolor.rememberDynamicMaterialThemeState

/** Signature Rapcha teal/emerald seed used on Android <12 or when dynamic wallpaper color is disabled. */
private val RapchaBrandSeed = Color(0xFF00895B)

/**
 * Resolves the seed color for MaterialKolor:
 * - On Android 12+ with [dynamicColor] enabled, extracts the user's live wallpaper primary accent
 *   (`system_accent1_500`) so both Light and Dark Material 3 schemes stay harmonized with Material You.
 * - Falls back cleanly to [RapchaBrandSeed] on older Android versions or in headless test environments.
 */
fun resolveThemeSeedColor(context: Context, dynamicColor: Boolean = true): Color {
    if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            return Color(ContextCompat.getColor(context, android.R.color.system_accent1_500))
        } catch (_: Throwable) {
            // Fall through to brand seed in headless/Robolectric environments.
        }
    }
    return RapchaBrandSeed
}

private fun hctColor(hue: Double, chroma: Double, tone: Double): Color {
    return Color(Hct.from(hue, chroma, tone).toInt())
}

/**
 * Shapes MaterialKolor's base [ColorScheme] into Rapcha's signature full-tint dynamic look:
 * - **Light theme:** Lush pastel wallpaper-tinted canvas (`background`) paired with crisp pure-white
 *   cards (`surface` / `surfaceContainerLowest`) and rich deep-accent actions (`primary`).
 * - **Dark theme:** Very deep, atmospheric wallpaper-tinted night canvas (`background`) paired with
 *   visibly lighter, richly tinted elevated cards (`surface` / `surfaceContainer`), soft tinted rims
 *   (`outlineVariant`), and luminous wallpaper accents (`primary` / `secondaryContainer`).
 *
 * Uses MaterialKolor's perceptual HCT color engine so every wallpaper hue automatically maintains
 * balanced saturation and WCAG AA/AAA text contrast.
 */
internal fun applyRapchaTonalPersonality(
    base: ColorScheme,
    seedColor: Color,
    isDark: Boolean
): ColorScheme {
    val seedHct = Hct.fromInt(seedColor.toArgb())
    val hue = seedHct.hue
    // Respect monochrome / near-grayscale wallpapers without injecting artificial color.
    if (seedHct.chroma < 12.0) {
        return if (!isDark) {
            base.copy(
                surface = Color.White,
                surfaceContainerLowest = Color.White
            )
        } else {
            base.copy(
                background = hctColor(hue, 4.0, 7.0),
                surface = hctColor(hue, 5.0, 16.5),
                surfaceContainer = hctColor(hue, 5.0, 16.5),
                surfaceContainerHigh = hctColor(hue, 6.0, 22.0)
            )
        }
    }

    val chroma = seedHct.chroma

    return if (!isDark) {
        val canvasBg = hctColor(hue, chroma.coerceIn(30.0, 48.0), 93.0)
        val deepInk = hctColor(hue, 20.0, 12.0)
        val mutedInk = hctColor(hue, 18.0, 34.0)
        val vibrantPrimary = hctColor(hue, chroma.coerceIn(46.0, 66.0), 29.0)
        val luminousPill = hctColor(hue, chroma.coerceIn(48.0, 70.0), 83.0)
        val pillInk = hctColor(hue, 36.0, 14.0)

        base.copy(
            primary = vibrantPrimary,
            onPrimary = Color.White,
            primaryContainer = luminousPill,
            onPrimaryContainer = pillInk,
            secondaryContainer = luminousPill,
            onSecondaryContainer = pillInk,
            background = canvasBg,
            onBackground = deepInk,
            surface = Color.White,
            onSurface = deepInk,
            surfaceVariant = hctColor(hue, chroma.coerceIn(24.0, 40.0), 89.0),
            onSurfaceVariant = mutedInk,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hctColor(hue, 14.0, 97.5),
            surfaceContainer = hctColor(hue, 22.0, 95.0),
            surfaceContainerHigh = hctColor(hue, chroma.coerceIn(22.0, 38.0), 91.5),
            surfaceContainerHighest = hctColor(hue, chroma.coerceIn(26.0, 42.0), 88.0),
            outline = hctColor(hue, 22.0, 48.0),
            outlineVariant = hctColor(hue, 24.0, 79.0)
        )
    } else {
        val deepCanvasBg = hctColor(hue, chroma.coerceIn(26.0, 40.0), 7.0)
        val liftedCardSurface = hctColor(hue, chroma.coerceIn(28.0, 44.0), 17.5)
        val crispInk = hctColor(hue, 12.0, 94.5)
        val softMutedInk = hctColor(hue, 18.0, 78.0)
        val luminousPrimary = hctColor(hue, chroma.coerceIn(50.0, 70.0), 78.0)
        val onLuminousPrimary = hctColor(hue, 36.0, 12.0)
        val darkAccentContainer = hctColor(hue, chroma.coerceIn(42.0, 60.0), 29.0)
        val onDarkAccentContainer = hctColor(hue, 28.0, 93.5)

        base.copy(
            primary = luminousPrimary,
            onPrimary = onLuminousPrimary,
            primaryContainer = darkAccentContainer,
            onPrimaryContainer = onDarkAccentContainer,
            secondaryContainer = hctColor(hue, chroma.coerceIn(44.0, 62.0), 31.0),
            onSecondaryContainer = onDarkAccentContainer,
            background = deepCanvasBg,
            onBackground = crispInk,
            surface = liftedCardSurface,
            onSurface = crispInk,
            surfaceVariant = hctColor(hue, chroma.coerceIn(28.0, 44.0), 21.0),
            onSurfaceVariant = softMutedInk,
            surfaceContainerLowest = hctColor(hue, chroma.coerceIn(22.0, 34.0), 5.0),
            surfaceContainerLow = hctColor(hue, chroma.coerceIn(26.0, 40.0), 13.0),
            surfaceContainer = liftedCardSurface,
            surfaceContainerHigh = hctColor(hue, chroma.coerceIn(30.0, 46.0), 23.5),
            surfaceContainerHighest = hctColor(hue, chroma.coerceIn(32.0, 48.0), 28.0),
            outline = hctColor(hue, chroma.coerceIn(24.0, 38.0), 46.0),
            outlineVariant = hctColor(hue, chroma.coerceIn(26.0, 40.0), 30.0)
        )
    }
}

/**
 * Generates a complete Material 3 [ColorScheme] using MaterialKolor's HCT engine with Rapcha's
 * full-tint Light and deep-tinted Dark surface personality.
 */
fun getAppDynamicColorScheme(
    context: Context,
    darkTheme: Boolean,
    toneId: String = "luminous"
): ColorScheme {
    val seed = resolveThemeSeedColor(context, dynamicColor = true)
    val style = when (toneId) {
        "muted" -> PaletteStyle.Neutral
        "deep" -> PaletteStyle.Vibrant
        else -> PaletteStyle.TonalSpot
    }
    return dynamicColorScheme(
        seedColor = seed,
        isDark = darkTheme,
        isAmoled = false,
        style = style,
        modifyColorScheme = { base -> applyRapchaTonalPersonality(base, seed, darkTheme) }
    )
}

/**
 * Root Rapcha Material 3 theme powered by MaterialKolor ([DynamicMaterialTheme]).
 *
 * Defaults to Light theme ([darkTheme] = false) and smoothly animates all Material 3 roles when
 * [animate] is enabled and the user toggles Light/Dark in Settings.
 */
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = true,
    toneId: String = "luminous",
    animate: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val seedColor = remember(context, dynamicColor, configuration) {
        resolveThemeSeedColor(context, dynamicColor = dynamicColor)
    }
    val style = when (toneId) {
        "muted" -> PaletteStyle.Neutral
        "deep" -> PaletteStyle.Vibrant
        else -> PaletteStyle.TonalSpot
    }

    val themeState = rememberDynamicMaterialThemeState(
        seedColor = seedColor,
        isDark = darkTheme,
        style = style,
        modifyColorScheme = { base -> applyRapchaTonalPersonality(base, seedColor, darkTheme) }
    )

    DynamicMaterialTheme(
        state = themeState,
        animate = animate,
        typography = Typography,
        content = content
    )
}
