package app.referencecanvas.core.design

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.referencecanvas.R
import app.referencecanvas.domain.model.AccentOption
import app.referencecanvas.domain.model.ThemeMode

/** Semantic design tokens. Screens read these instead of hardcoding colors. */
@Immutable
class RcColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val isDark: Boolean,
)

fun AccentOption.color(): Color = when (this) {
    AccentOption.Violet -> Color(0xFF8B7CF6)
    AccentOption.Cyan -> Color(0xFF22D3EE)
    AccentOption.Blue -> Color(0xFF4C8DFF)
    AccentOption.Green -> Color(0xFF34D399)
    AccentOption.Orange -> Color(0xFFFB923C)
    AccentOption.Red -> Color(0xFFF87171)
}

@StringRes
fun AccentOption.labelRes(): Int = when (this) {
    AccentOption.Violet -> R.string.accent_violet
    AccentOption.Cyan -> R.string.accent_cyan
    AccentOption.Blue -> R.string.accent_blue
    AccentOption.Green -> R.string.accent_green
    AccentOption.Orange -> R.string.accent_orange
    AccentOption.Red -> R.string.accent_red
}

private val OnAccent = Color(0xFF0B0C0F)
private val Danger = Color(0xFFF87171)

private fun darkTokens(accent: AccentOption) = RcColors(
    background = Color(0xFF0B0C0F),
    surface = Color(0xFF13151A),
    surfaceVariant = Color(0xFF1B1E25),
    border = Color(0xFF292D36),
    textPrimary = Color(0xFFF4F5F7),
    textSecondary = Color(0xFF969BA7),
    textMuted = Color(0xFF8A90A0), // lifted from the suggested #686E7A to keep contrast readable
    accent = accent.color(),
    onAccent = OnAccent,
    danger = Danger,
    isDark = true,
)

private fun lightTokens(accent: AccentOption) = RcColors(
    background = Color(0xFFF7F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEEFF3),
    border = Color(0xFFD6D9E0),
    textPrimary = Color(0xFF15171C),
    textSecondary = Color(0xFF555B6A),
    textMuted = Color(0xFF6B7180),
    accent = accent.color(),
    onAccent = OnAccent,
    danger = Color(0xFFC62828),
    isDark = false,
)

private val LocalRcColors = staticCompositionLocalOf { darkTokens(AccentOption.Violet) }

object RcTheme {
    val colors: RcColors
        @Composable @ReadOnlyComposable get() = LocalRcColors.current
}

@Composable
@ReadOnlyComposable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
    ThemeMode.System -> isSystemInDarkTheme()
}

private val RcTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val RcShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun ReferenceCanvasTheme(
    themeMode: ThemeMode,
    accent: AccentOption,
    content: @Composable () -> Unit,
) {
    val dark = themeMode.isDark()
    val rc = if (dark) darkTokens(accent) else lightTokens(accent)
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = rc.accent,
        onPrimary = rc.onAccent,
        background = rc.background,
        onBackground = rc.textPrimary,
        surface = rc.surface,
        onSurface = rc.textPrimary,
        surfaceVariant = rc.surfaceVariant,
        onSurfaceVariant = rc.textSecondary,
        outline = rc.border,
        outlineVariant = rc.border,
        error = rc.danger,
        surfaceContainer = rc.surface,
        surfaceContainerHigh = rc.surfaceVariant,
        surfaceContainerHighest = rc.surfaceVariant,
    )
    CompositionLocalProvider(LocalRcColors provides rc) {
        MaterialTheme(colorScheme = scheme, typography = RcTypography, shapes = RcShapes, content = content)
    }
}
