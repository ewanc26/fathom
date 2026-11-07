package uk.ewancroft.fathom.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fathom is dark-only on purpose. The palette is built against a deep
// teal-black surface; a light variant would be a second design rather than a
// recolour, and one that is right on every screen beats two that are guesses.
val FathomBackground = Color(0xFF0B1416)
val FathomSurface = Color(0xFF122024)
val FathomSurfaceHigh = Color(0xFF1A2E32)
val FathomTeal = Color(0xFF2DD4BF)
val FathomTealDeep = Color(0xFF0F766E)
val FathomMint = Color(0xFF99F6E4)
val FathomInk = Color(0xFFE7F4F1)
val FathomMuted = Color(0xFF8CA6A3)

private val FathomScheme = darkColorScheme(
    primary = FathomTeal,
    onPrimary = Color(0xFF04211E),
    primaryContainer = FathomTealDeep,
    onPrimaryContainer = FathomMint,
    secondary = FathomMint,
    onSecondary = Color(0xFF04211E),
    background = FathomBackground,
    onBackground = FathomInk,
    surface = FathomSurface,
    onSurface = FathomInk,
    surfaceVariant = FathomSurfaceHigh,
    onSurfaceVariant = FathomMuted,
    outline = Color(0xFF2C464A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF5C0F0B),
)

@Composable
fun FathomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FathomScheme, content = content)
}
