package app.cmuxpocket.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

/**
 * Single source of truth for every colour rendered by the app.
 *
 * Values come from the canonical palette **P3 · Neon Grid** documented in `DESIGN.md`; that file
 * is the reference for the token table and the binding usage rules (semantic accents are
 * state-only, light mode re-tunes lightness without shifting hue).
 *
 * UI code MUST read from [CmuxTheme.colors]; a literal `Color(0x…)` in a UI file is a bug,
 * because it silently ignores the light/dark switch. Translucent variants of the semantic
 * accents are derived at the call site with `Color.copy(alpha = …)` so that a single opaque
 * accent value per theme stays authoritative.
 *
 * Terminal canvas colours ([terminalBg]/[terminalFg]) are the theme-side well and default
 * foreground of the canvas. The live well is currently driven by the user's terminal palette
 * selection (`terminalBgTheme`), which is independent of the app theme.
 */
data class CmuxColors(
    val isDark: Boolean,
    // --- Base surfaces ---
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    /** Brand accent violet. Dark `#A68CFF` / light `#6D28D9`. */
    val primary: Color,
    val primaryContainer: Color,
    val onPrimary: Color,
    val terminalBg: String,
    val terminalFg: String,
    val tabRowBg: Color,
    val tabActiveBg: Color,
    val divider: Color,
    val accessoryBg: Color,
    val accessoryKeyBg: Color,
    val accessoryKeyText: Color,
    // --- Floating chrome (island pill, bubble, drawer, dialogs) ---
    /** Frosted pill over the terminal: translucent black in dark, frosted white in light. */
    val islandBg: Color,
    val islandBorder: Color,
    val onIsland: Color,
    val onIslandMuted: Color,
    /** Modal container body (drawer, dialog, sheet). */
    val containerBg: Color,
    val containerBorder: Color,
    val onContainer: Color,
    val onContainerMuted: Color,
    /** Contextual action bubble body. */
    val bubbleBg: Color,
    /** Card sitting inside a [containerBg] surface. */
    val cardBg: Color,
    val cardBorder: Color,
    /** Subtle chip / icon-button background and border drawn on floating chrome. */
    val controlBg: Color,
    val controlBorder: Color,
    /** Dimming layer between the terminal and a modal. */
    val scrim: Color,
    // --- Semantic accents (state only, never decorative) ---
    val accentGreen: Color,
    val accentOrange: Color,
    val accentRed: Color,
    val accentPurple: Color,
    /** Neon cyan: info / in-progress state. */
    val accentBlue: Color,
    /**
     * Text/icon ink for a chip whose fill is the same accent at low alpha ([accentGreen] @20 % …).
     * Dark mode reuses the accent; light mode darkens it until the 4.5:1 floor holds, because the
     * tinted fill lightens the background and the raw accent drops to ~2.7:1 there.
     */
    val accentGreenInk: Color,
    val accentOrangeInk: Color,
    val accentRedInk: Color,
    val accentBlueInk: Color,
    /** GitHub brand tile; light theme uses the brand black, dark theme an inverted light tile. */
    val brandGithub: Color,
    /** Glyph colour for [brandGithub]. */
    val onBrandGithub: Color,
    /** Content colour for a bright accent fill, where [onPrimary] would be unreadable. */
    val onAccentBright: Color,
    /** Ink for a fill chosen at runtime; pick with [Color.luminance] (terminal palette swatches). */
    val inkOnLightFill: Color,
    val inkOnDarkFill: Color
)

/** Canonical palette P3 · Neon Grid — see `DESIGN.md` for the full table and usage rules. */
val DarkCmuxColors = CmuxColors(
    isDark = true,
    background = Color(0xFF090911),
    surface = Color(0xFF12121E),
    surfaceVariant = Color(0xFF1A1A2B),
    onBackground = Color(0xFFF0F0F8),
    onSurface = Color(0xFFF0F0F8),
    onSurfaceVariant = Color(0xFF8888A3),
    primary = Color(0xFFA68CFF),
    primaryContainer = Color(0xFF3D2E73),
    onPrimary = Color(0xFF150B33),
    terminalBg = "#0C0C16",
    terminalFg = "#CDCDDE",
    tabRowBg = Color(0xFF1A1A2B),
    tabActiveBg = Color(0xFF12121E),
    divider = Color(0xFF242438),
    accessoryBg = Color(0xFF12121E),
    accessoryKeyBg = Color(0xFF202033),
    accessoryKeyText = Color(0xFFDBDBEA),
    islandBg = Color(0xE612121E),
    islandBorder = Color(0xFF2E2E48),
    onIsland = Color(0xFFF0F0F8),
    onIslandMuted = Color(0xFF8888A3),
    containerBg = Color(0xFF12121E),
    containerBorder = Color(0xFF242438),
    onContainer = Color(0xFFF0F0F8),
    onContainerMuted = Color(0xFF8888A3),
    bubbleBg = Color(0xF212121E),
    cardBg = Color(0xFF1A1A2B),
    cardBorder = Color(0xFF242438),
    controlBg = Color(0x14FFFFFF),
    controlBorder = Color(0xFF2E2E48),
    scrim = Color(0xB3090911),
    accentGreen = Color(0xFFA6E22E),
    accentOrange = Color(0xFFFFC857),
    accentRed = Color(0xFFFF6E7F),
    accentPurple = Color(0xFFA68CFF),
    accentBlue = Color(0xFF3EE6D8),
    accentGreenInk = Color(0xFFA6E22E),
    accentOrangeInk = Color(0xFFFFC857),
    accentRedInk = Color(0xFFFF6E7F),
    accentBlueInk = Color(0xFF3EE6D8),
    brandGithub = Color(0xFFE8EAED),
    onBrandGithub = Color(0xFF1C1C1E),
    onAccentBright = Color(0xFF150B33),
    inkOnLightFill = Color(0xFF150B33),
    inkOnDarkFill = Color(0xFFF0F0F8)
)

val LightCmuxColors = CmuxColors(
    isDark = false,
    background = Color(0xFFF5F5FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8E8F2),
    onBackground = Color(0xFF14141F),
    onSurface = Color(0xFF14141F),
    onSurfaceVariant = Color(0xFF5C5C75),
    primary = Color(0xFF6D28D9),
    primaryContainer = Color(0xFFE6DCFB),
    onPrimary = Color.White,
    terminalBg = "#FBFBFE",
    terminalFg = "#1A1A26",
    tabRowBg = Color(0xFFE8E8F2),
    tabActiveBg = Color(0xFFFFFFFF),
    divider = Color(0xFFD8D8E5),
    accessoryBg = Color(0xFFE8E8F2),
    accessoryKeyBg = Color(0xFFFFFFFF),
    accessoryKeyText = Color(0xFF1A1A26),
    islandBg = Color(0xF2FFFFFF),
    islandBorder = Color(0xFFD8D8E5),
    onIsland = Color(0xFF14141F),
    onIslandMuted = Color(0xFF5C5C75),
    containerBg = Color(0xFFF5F5FA),
    containerBorder = Color(0xFFD8D8E5),
    onContainer = Color(0xFF14141F),
    onContainerMuted = Color(0xFF5C5C75),
    bubbleBg = Color(0xF2FFFFFF),
    cardBg = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE8E8F2),
    controlBg = Color(0x14000000),
    controlBorder = Color(0xFFD8D8E5),
    scrim = Color(0x66000000),
    accentGreen = Color(0xFF5A9E2E),
    accentOrange = Color(0xFFB97F0E),
    accentRed = Color(0xFFD43D51),
    accentPurple = Color(0xFF6D28D9),
    accentBlue = Color(0xFF0D9488),
    accentGreenInk = Color(0xFF35600F),
    accentOrangeInk = Color(0xFF7A4E00),
    accentRedInk = Color(0xFF99182B),
    accentBlueInk = Color(0xFF0A5C55),
    brandGithub = Color(0xFF24292E),
    onBrandGithub = Color.White,
    onAccentBright = Color(0xFF1A1A26),
    inkOnLightFill = Color(0xFF150B33),
    inkOnDarkFill = Color(0xFFF0F0F8)
)

val LocalCmuxColors = staticCompositionLocalOf { DarkCmuxColors }

object CmuxTheme {
    val colors: CmuxColors
        @Composable
        @ReadOnlyComposable
        get() = LocalCmuxColors.current
}

/**
 * Bridges [CmuxColors] onto the Material 3 scheme so that stock components (Switch, TextField,
 * Card, AlertDialog, Button, HorizontalDivider) follow the selected theme instead of falling
 * back to Material's built-in light defaults in dark mode.
 */
private fun CmuxColors.toMaterialScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = if (isDark) Color(0xFFE9E2FF) else Color(0xFF4C1D95),
        secondary = accentGreen,
        onSecondary = onAccentBright,
        secondaryContainer = primaryContainer,
        onSecondaryContainer = if (isDark) Color(0xFFE9E2FF) else Color(0xFF4C1D95),
        tertiary = accentBlue,
        onTertiary = if (isDark) Color.Black else Color.White,
        tertiaryContainer = primaryContainer,
        onTertiaryContainer = if (isDark) Color(0xFFE9E2FF) else Color(0xFF4C1D95),
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainer = cardBg,
        surfaceContainerHigh = cardBg,
        outline = divider,
        outlineVariant = cardBorder,
        error = accentRed,
        onError = if (isDark) onAccentBright else Color.White,
        errorContainer = accentRed.copy(alpha = if (isDark) 0.24f else 0.14f),
        onErrorContainer = accentRedInk
    )
}

/**
 * Keeps the system status/navigation bars legible for the active theme.
 *
 * The platform window theme is a stock dark `Theme.Material.NoActionBar`, so its bar colour is
 * black regardless of the selected app theme. Painting the bars from the palette is what prevents
 * light mode from showing a black band with dark (invisible) glyphs on top.
 */
@Composable
private fun SystemBarAppearance(colors: CmuxColors) {
    val view = LocalView.current
    val context = LocalContext.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !colors.isDark
        controller.isAppearanceLightNavigationBars = !colors.isDark
        @Suppress("DEPRECATION")
        window.statusBarColor = colors.background.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = colors.background.toArgb()
    }
}

@Composable
fun CmuxAppTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colors = if (isDark) DarkCmuxColors else LightCmuxColors

    SystemBarAppearance(colors)

    CompositionLocalProvider(LocalCmuxColors provides colors) {
        MaterialTheme(colorScheme = colors.toMaterialScheme(), content = content)
    }
}
