package app.mami.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFFC2185B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E3),
    onPrimaryContainer = Color(0xFF3E0020),
    secondary = Color(0xFF7B3FA0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2DAFF),
    onSecondaryContainer = Color(0xFF2E004E),
    tertiary = Color(0xFF00897B),
    background = Color(0xFFFBF7F8),
    onBackground = Color(0xFF221A1D),
    surface = Color(0xFFFFFBFC),
    onSurface = Color(0xFF221A1D),
    surfaceVariant = Color(0xFFF4E4E8),
    onSurfaceVariant = Color(0xFF5A4248),
    surfaceContainer = Color(0xFFF7EEF0),
    surfaceContainerHigh = Color(0xFFF1E6E9),
    outline = Color(0xFF8D7178),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB0C9),
    onPrimary = Color(0xFF5F1134),
    primaryContainer = Color(0xFF7D2A4B),
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = Color(0xFFE2B6FF),
    onSecondary = Color(0xFF47196C),
    secondaryContainer = Color(0xFF5F3284),
    onSecondaryContainer = Color(0xFFF2DAFF),
    tertiary = Color(0xFF6FD9C8),
    background = Color(0xFF17121A),
    onBackground = Color(0xFFEDE0E3),
    surface = Color(0xFF1C1619),
    onSurface = Color(0xFFEDE0E3),
    surfaceVariant = Color(0xFF3A2E33),
    onSurfaceVariant = Color(0xFFDDC0C7),
    surfaceContainer = Color(0xFF261E22),
    surfaceContainerHigh = Color(0xFF30272B),
    outline = Color(0xFFA68A91),
    error = Color(0xFFFFB4AB),
)

private val MamiTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(lineHeight = 22.sp),
        labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.2.sp, fontWeight = FontWeight.Medium),
    )
}

private val MamiShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

/** The brand gradient used on the welcome screen and the partner's avatar. */
val BrandGradient = Brush.linearGradient(listOf(Color(0xFFE91E63), Color(0xFF8E24AA)))

@Composable
fun MamiTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = MamiTypography,
        shapes = MamiShapes,
        content = content,
    )
}
