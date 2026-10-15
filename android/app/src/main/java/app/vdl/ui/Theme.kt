package app.vdl.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette VDL (bleu de l'icône), identique sur tous les téléphones
private val Light = lightColorScheme(
    primary = Color(0xFF2D6CDF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FC),
    onPrimaryContainer = Color(0xFF0B2C6B),
    secondary = Color(0xFF5B6478),
    secondaryContainer = Color(0xFFE3E7F0),
    onSecondaryContainer = Color(0xFF1B2232),
    tertiary = Color(0xFF1D7A46),
    tertiaryContainer = Color(0xFFD5F2E0),
    background = Color(0xFFF7F8FB),
    surface = Color(0xFFF7F8FB),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F2F7),
    surfaceContainerHigh = Color(0xFFE9ECF3),
    surfaceContainerHighest = Color(0xFFE2E6EE),
    onSurface = Color(0xFF15171C),
    onSurfaceVariant = Color(0xFF5D6270),
    outlineVariant = Color(0xFFE1E4EB),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFBE4E2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FB0F5),
    onPrimary = Color(0xFF0B2C6B),
    primaryContainer = Color(0xFF1E3F82),
    onPrimaryContainer = Color(0xFFDCE6FC),
    secondary = Color(0xFFB9C1D3),
    secondaryContainer = Color(0xFF2C3241),
    onSecondaryContainer = Color(0xFFE3E7F0),
    tertiary = Color(0xFF7DD3A0),
    tertiaryContainer = Color(0xFF14462B),
    background = Color(0xFF0F1115),
    surface = Color(0xFF0F1115),
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceContainerLow = Color(0xFF171A21),
    surfaceContainer = Color(0xFF1C2028),
    surfaceContainerHigh = Color(0xFF242934),
    surfaceContainerHighest = Color(0xFF2D3340),
    onSurface = Color(0xFFECEEF3),
    onSurfaceVariant = Color(0xFF9AA0AD),
    outlineVariant = Color(0xFF2A2F3A),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF5C1A16),
)

private val base = Typography()
private val VdlTypography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
)

private val VdlShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun VdlTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = VdlTypography,
        shapes = VdlShapes,
        content = content,
    )
}
