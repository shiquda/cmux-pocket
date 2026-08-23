package app.cmuxpocket.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

data class CmuxColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
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
    val accessoryKeyText: Color
)

val DarkCmuxColors = CmuxColors(
    isDark = true,
    background = Color(0xFF141416),
    surface = Color(0xFF1E1E22),
    surfaceVariant = Color(0xFF282830),
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFAAAAAA),
    primary = Color(0xFF64B5F6),
    primaryContainer = Color(0xFF1976D2),
    onPrimary = Color.White,
    terminalBg = "#1E1E1E",
    terminalFg = "#D4D4D4",
    tabRowBg = Color(0xFF141416),
    tabActiveBg = Color(0xFF24242A),
    divider = Color(0xFF2B2B32),
    accessoryBg = Color(0xFF1E1E22),
    accessoryKeyBg = Color(0xFF303036),
    accessoryKeyText = Color.White
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
    accessoryKeyText = Color(0xFF1C1C1E)
)

val LocalCmuxColors = staticCompositionLocalOf { DarkCmuxColors }

object CmuxTheme {
    val colors: CmuxColors
        @Composable
        @ReadOnlyComposable
        get() = LocalCmuxColors.current
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

    CompositionLocalProvider(LocalCmuxColors provides colors) {
        content()
    }
}
