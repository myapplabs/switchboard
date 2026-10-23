package llc.applabs.design.tokens

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Switchboard's theme (spec: Screens and UX): a dark "control room" — near-black ground, one amber
// accent, status colors that differ in lightness too (readable without color). A light theme too (the
// person's choice in More, or the system setting). Mono (JetBrains Mono in the spec; the platform monospace until fonts are bundled)
// is for status and technical labels.

private val Ink = Color(0xFF0B0D10)
private val Panel = Color(0xFF13161B)
private val PanelHigh = Color(0xFF1B1F25)
private val Line = Color(0xFF2A2F37)
private val Text = Color(0xFFE6E8EB)
private val TextDim = Color(0xFF9AA3AE)
private val Amber = Color(0xFFF5A524)

val AppDarkColorScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1F1400),
    primaryContainer = Color(0xFF3A2A06),
    onPrimaryContainer = Color(0xFFFFD48A),
    secondary = TextDim,
    onSecondary = Ink,
    secondaryContainer = PanelHigh,
    onSecondaryContainer = Text,
    tertiary = Color(0xFF7CC4FF),
    background = Ink,
    onBackground = Text,
    surface = Panel,
    onSurface = Text,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = TextDim,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Color(0xFF101317),
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = Color(0xFF232830),
    outline = Line,
    outlineVariant = Color(0xFF22262D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0000),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFB4AB),
)

val AppLightColorScheme = lightColorScheme(
    primary = Color(0xFF9A5B00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF2E1A00),
    secondary = Color(0xFF5B636E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E7EB),
    onSecondaryContainer = Color(0xFF15181C),
    tertiary = Color(0xFF00629E),
    background = Color(0xFFF6F6F4),
    onBackground = Color(0xFF15181C),
    surface = Color.White,
    onSurface = Color(0xFF15181C),
    surfaceVariant = Color(0xFFECEDEF),
    onSurfaceVariant = Color(0xFF5B636E),
    // Every surface role set, neutral grey: unset ones fall back to Material's lavender defaults.
    surfaceTint = Color(0xFF9A5B00),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDCDDE0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F8F7),
    surfaceContainer = Color(0xFFF0F1F2),
    surfaceContainerHigh = Color(0xFFE8E9EB),
    surfaceContainerHighest = Color.White,   // cards: white on the off-white ground, as on the Agents tab
    tertiaryContainer = Color(0xFFD3E8FF),
    onTertiaryContainer = Color(0xFF001D33),
    inverseSurface = Color(0xFF2B2F35),
    inverseOnSurface = Color(0xFFF0F1F2),
    inversePrimary = Color(0xFFF5A524),
    outline = Color(0xFFC9CDD2),
    outlineVariant = Color(0xFFE0E2E5),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val AppTypography = AppLabsTypography

/** Status and technical labels (agent status, hosts, model names, tool commands). */
val MonoLabel = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp)
val MonoBody = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

/** Agent status colors: green / amber / grey / red, each also a different lightness. */
object StatusColors {
    @Composable @ReadOnlyComposable fun online() = if (llc.applabs.design.LocalDarkTheme.current) Color(0xFF3DD68C) else Color(0xFF167A48)
    @Composable @ReadOnlyComposable fun busy() = if (llc.applabs.design.LocalDarkTheme.current) Amber else Color(0xFF9A5B00)
    @Composable @ReadOnlyComposable fun offline() = if (llc.applabs.design.LocalDarkTheme.current) Color(0xFF5C6370) else Color(0xFF9AA0A8)
    @Composable @ReadOnlyComposable fun problem() = if (llc.applabs.design.LocalDarkTheme.current) Color(0xFFFF6B6B) else Color(0xFFBA1A1A)
}
