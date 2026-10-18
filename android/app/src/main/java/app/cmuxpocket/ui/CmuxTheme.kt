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
 * UI code MUST read from [CmuxTheme.colors]; a literal `Color(0x…)` in a UI file is a bug,
 * because it silently ignores the light/dark switch. Translucent variants of the semantic
 * accents are derived at the call site with `Color.copy(alpha = …)` so that a single opaque
 * accent value per theme stays authoritative.
 *
 * Terminal canvas colours ([terminalBg]/[terminalFg]) are terminal palette entries, not
 * chrome: they are intentionally identical in both themes.
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
    /** Brand accent blue. Dark `#0A84FF` / light `#007AFF`. */
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
    // --- Semantic accents ---
    val accentGreen: Color,
    val accentOrange: Color,
    val accentRed: Color,
    val accentYellow: Color,
    /** Decorative category tiles; saturated in both themes because the glyph is white. */
    val accentPurple: Color,
    val accentTeal: Color,
    /** GitHub brand tile. */
    val brandGithub: Color,
    /** Content colour for a bright accent fill (yellow tiles), where [onPrimary] would be unreadable. */
    val onAccentBright: Color
)

val DarkCmuxColors = CmuxColors(
    isDark = true,
    background = Color(0xFF141416),
    surface = Color(0xFF1E1E22),
    surfaceVariant = Color(0xFF282830),
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFAAAAAA),
    primary = Color(0xFF0A84FF),
    primaryContainer = Color(0xFF1976D2),
    onPrimary = Color.White,
    terminalBg = "#1E1E1E",
    terminalFg = "#D4D4D4",
    tabRowBg = Color(0xFF141416),
    tabActiveBg = Color(0xFF24242A),
    divider = Color(0xFF2B2B32),
    accessoryBg = Color(0xFF1E1E22),
    accessoryKeyBg = Color(0xFF303036),
    accessoryKeyText = Color.White,
    islandBg = Color(0xDD1C1C1E),
    islandBorder = Color(0x33FFFFFF),
    onIsland = Color.White,
    onIslandMuted = Color(0xFF8E8E93),
    containerBg = Color(0xFF1C1C1E),
    containerBorder = Color(0x33FFFFFF),
    onContainer = Color.White,
    onContainerMuted = Color(0xFF8E8E93),
    bubbleBg = Color(0xEE222226),
    cardBg = Color(0xFF2C2C2E),
    cardBorder = Color(0x1FFFFFFF),
    controlBg = Color(0x1FFFFFFF),
    controlBorder = Color(0x33FFFFFF),
    scrim = Color(0xB3212121),
    accentGreen = Color(0xFF30D158),
    accentOrange = Color(0xFFFF9F0A),
    accentRed = Color(0xFFFF453A),
    accentYellow = Color(0xFFFFD60A),
    accentPurple = Color(0xFFBF5AF2),
    accentTeal = Color(0xFF64D2FF),
    brandGithub = Color(0xFF24292E),
    onAccentBright = Color(0xFF1C1C1E)
)

val LightCmuxColors = CmuxColors(
    isDark = false,
    background = Color(0xFFF5F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE5E5EA),
    onBackground = Color(0xFF1C1C1E),
    onSurface = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF636366),
    primary = Color(0xFF007AFF),
    primaryContainer = Color(0xFF007AFF),
    onPrimary = Color.White,
    terminalBg = "#FAFAFC",
    terminalFg = "#1C1C1E",
    tabRowBg = Color(0xFFE5E5EA),
    tabActiveBg = Color(0xFFFFFFFF),
    divider = Color(0xFFD1D1D6),
    accessoryBg = Color(0xFFE5E5EA),
    accessoryKeyBg = Color(0xFFFFFFFF),
    accessoryKeyText = Color(0xFF1C1C1E),
    islandBg = Color(0xE6FFFFFF),
    islandBorder = Color(0x1A000000),
    onIsland = Color(0xFF1C1C1E),
    onIslandMuted = Color(0xFF636366),
    containerBg = Color(0xFFF7F7FA),
    containerBorder = Color(0x1A000000),
    onContainer = Color(0xFF1C1C1E),
    onContainerMuted = Color(0xFF636366),
    bubbleBg = Color(0xF2FFFFFF),
    cardBg = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE5E5EA),
    controlBg = Color(0x14000000),
    controlBorder = Color(0x1A000000),
    scrim = Color(0x66000000),
    accentGreen = Color(0xFF34C759),
    accentOrange = Color(0xFFFF9500),
    accentRed = Color(0xFFFF3B30),
    accentYellow = Color(0xFFFFCC00),
    accentPurple = Color(0xFFAF52DE),
    accentTeal = Color(0xFF00A3C4),
    brandGithub = Color(0xFF24292E),
    onAccentBright = Color(0xFF1C1C1E)
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
        onPrimaryContainer = onPrimary,
        secondary = accentGreen,
        onSecondary = Color.White,
        tertiary = accentTeal,
        onTertiary = if (isDark) Color.Black else Color.White,
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
        onError = Color.White,
        errorContainer = accentRed.copy(alpha = if (isDark) 0.24f else 0.14f),
        onErrorContainer = if (isDark) accentRed else Color(0xFF8A1F17)
    )
}

/**
 * Keeps the system status/navigation bar glyphs legible for the active theme: dark glyphs on a
 * light UI, light glyphs on a dark UI.
 */
@Composable
private fun SystemBarAppearance(isDark: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
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

    SystemBarAppearance(isDark)

    CompositionLocalProvider(LocalCmuxColors provides colors) {
        MaterialTheme(colorScheme = colors.toMaterialScheme(), content = content)
    }
}
