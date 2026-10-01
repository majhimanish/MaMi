package app.mami.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mami.R

/** The colour themes people can pick in Settings. */
enum class Palette(
    val label: String,
    internal val hue: Float,
    internal val secondaryHue: Float,
    internal val tertiaryHue: Float,
    val gradient: List<Color>,
) {
    ROSE("Rose", 336f, 285f, 18f, listOf(Color(0xFFFF4F8B), Color(0xFFD12FB4), Color(0xFF9B30FF))),
    LAVENDER("Lavender", 265f, 320f, 195f, listOf(Color(0xFF8E7CFF), Color(0xFFB36BFF), Color(0xFFE76BD8))),
    OCEAN("Ocean", 205f, 175f, 330f, listOf(Color(0xFF00C6FB), Color(0xFF2E8BFF), Color(0xFF5B5BFF))),
    SUNSET("Sunset", 16f, 340f, 45f, listOf(Color(0xFFFFA25F), Color(0xFFFF5F6D), Color(0xFFD9468F))),
    MINT("Mint", 162f, 195f, 330f, listOf(Color(0xFF2BD9A5), Color(0xFF14B8A6), Color(0xFF0E8FB5))),

    /** Material You colours from the wallpaper (Android 12+). */
    WALLPAPER("Wallpaper", 336f, 285f, 18f, emptyList()),
}

enum class DarkMode(val label: String) { SYSTEM("Auto"), LIGHT("Light"), DARK("Dark") }

@Immutable
data class Appearance(
    val palette: Palette = Palette.ROSE,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val chatWallpaper: Boolean = true,
)

/** Brand colours Material's scheme has no slot for. */
@Immutable
data class MamiColors(
    val gradient: List<Color>,
    val onGradient: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val online: Color,
    val isDark: Boolean,
    val wallpaper: Boolean,
) {
    val brush: Brush get() = Brush.linearGradient(gradient)
}

private val LocalMamiColors = staticCompositionLocalOf {
    MamiColors(
        gradient = Palette.ROSE.gradient,
        onGradient = Color.White,
        good = Color(0xFF2EC27E),
        warn = Color(0xFFF5A524),
        bad = Color(0xFFE5484D),
        online = Color(0xFF2EC27E),
        isDark = false,
        wallpaper = true,
    )
}

object Mami {
    val colors: MamiColors
        @Composable get() = LocalMamiColors.current
}

/** Rounded, warm and very readable. */
val Nunito = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
)

/** Playful display face for big titles. */
val Fredoka = FontFamily(
    Font(R.font.fredoka_medium, FontWeight.Medium),
    Font(R.font.fredoka_semibold, FontWeight.SemiBold),
    Font(R.font.fredoka_bold, FontWeight.Bold),
)

/** Handwriting, for love letters. */
val Caveat = FontFamily(Font(R.font.caveat_semibold, FontWeight.SemiBold))

private val MamiTypography = Typography().let { base ->
    fun TextStyle.body() = copy(fontFamily = Nunito)
    fun TextStyle.display(weight: FontWeight = FontWeight.SemiBold) = copy(fontFamily = Fredoka, fontWeight = weight)
    base.copy(
        displayLarge = base.displayLarge.display(),
        displayMedium = base.displayMedium.display(),
        displaySmall = base.displaySmall.display(),
        headlineLarge = base.headlineLarge.display(),
        headlineMedium = base.headlineMedium.display(),
        headlineSmall = base.headlineSmall.display(FontWeight.Medium),
        titleLarge = base.titleLarge.copy(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold),
        titleMedium = base.titleMedium.copy(fontFamily = Nunito, fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.copy(fontFamily = Nunito, fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.body().copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = base.bodyMedium.body(),
        bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.copy(fontFamily = Nunito, fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.copy(fontFamily = Nunito, fontWeight = FontWeight.Bold),
        labelSmall = base.labelSmall.copy(fontFamily = Nunito, fontWeight = FontWeight.SemiBold),
    )
}

private val MamiShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun MamiTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val dark = when (appearance.darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val wallpaper = appearance.palette == Palette.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        wallpaper && dark -> dynamicDarkColorScheme(LocalContext.current)
        wallpaper -> dynamicLightColorScheme(LocalContext.current)
        else -> schemeFor(appearance.palette.takeIf { it != Palette.WALLPAPER } ?: Palette.ROSE, dark)
    }
    val gradient = appearance.palette.gradient.ifEmpty { listOf(scheme.primary, scheme.tertiary) }
    val extra = MamiColors(
        gradient = gradient,
        onGradient = Color.White,
        good = if (dark) Color(0xFF5BE3A1) else Color(0xFF1F9D63),
        warn = if (dark) Color(0xFFFFC163) else Color(0xFFD9860B),
        bad = if (dark) Color(0xFFFF8A8F) else Color(0xFFD9343B),
        online = Color(0xFF2EC27E),
        isDark = dark,
        wallpaper = appearance.chatWallpaper,
    )
    CompositionLocalProvider(LocalMamiColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = MamiTypography, shapes = MamiShapes, content = content)
    }
}

/** A full Material 3 scheme from the palette's hues. */
private fun schemeFor(palette: Palette, dark: Boolean): ColorScheme {
    val h = palette.hue
    val s2 = palette.secondaryHue
    val t = palette.tertiaryHue
    fun c(hue: Float, saturation: Float, lightness: Float) = Color.hsl(hue, saturation, lightness)
    return if (!dark) {
        lightColorScheme(
            primary = c(h, 0.72f, 0.47f),
            onPrimary = Color.White,
            primaryContainer = c(h, 0.95f, 0.91f),
            onPrimaryContainer = c(h, 0.85f, 0.17f),
            inversePrimary = c(h, 0.9f, 0.82f),
            secondary = c(s2, 0.45f, 0.45f),
            onSecondary = Color.White,
            secondaryContainer = c(s2, 0.75f, 0.92f),
            onSecondaryContainer = c(s2, 0.6f, 0.17f),
            tertiary = c(t, 0.6f, 0.42f),
            onTertiary = Color.White,
            tertiaryContainer = c(t, 0.8f, 0.9f),
            onTertiaryContainer = c(t, 0.6f, 0.16f),
            background = c(h, 0.45f, 0.985f),
            onBackground = c(h, 0.2f, 0.11f),
            surface = c(h, 0.45f, 0.985f),
            onSurface = c(h, 0.2f, 0.11f),
            surfaceVariant = c(h, 0.3f, 0.91f),
            onSurfaceVariant = c(h, 0.12f, 0.34f),
            surfaceTint = c(h, 0.72f, 0.47f),
            inverseSurface = c(h, 0.12f, 0.18f),
            inverseOnSurface = c(h, 0.4f, 0.95f),
            outline = c(h, 0.08f, 0.52f),
            outlineVariant = c(h, 0.2f, 0.84f),
            surfaceBright = c(h, 0.45f, 0.985f),
            surfaceDim = c(h, 0.2f, 0.88f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = c(h, 0.5f, 0.97f),
            surfaceContainer = c(h, 0.42f, 0.955f),
            surfaceContainerHigh = c(h, 0.36f, 0.935f),
            surfaceContainerHighest = c(h, 0.3f, 0.915f),
        )
    } else {
        darkColorScheme(
            primary = c(h, 0.9f, 0.8f),
            onPrimary = c(h, 0.85f, 0.2f),
            primaryContainer = c(h, 0.6f, 0.32f),
            onPrimaryContainer = c(h, 0.95f, 0.91f),
            inversePrimary = c(h, 0.72f, 0.47f),
            secondary = c(s2, 0.6f, 0.8f),
            onSecondary = c(s2, 0.6f, 0.2f),
            secondaryContainer = c(s2, 0.35f, 0.3f),
            onSecondaryContainer = c(s2, 0.75f, 0.92f),
            tertiary = c(t, 0.7f, 0.76f),
            onTertiary = c(t, 0.6f, 0.18f),
            tertiaryContainer = c(t, 0.45f, 0.28f),
            onTertiaryContainer = c(t, 0.8f, 0.9f),
            background = c(h, 0.18f, 0.07f),
            onBackground = c(h, 0.2f, 0.91f),
            surface = c(h, 0.18f, 0.07f),
            onSurface = c(h, 0.2f, 0.91f),
            surfaceVariant = c(h, 0.14f, 0.24f),
            onSurfaceVariant = c(h, 0.16f, 0.78f),
            surfaceTint = c(h, 0.9f, 0.8f),
            inverseSurface = c(h, 0.2f, 0.91f),
            inverseOnSurface = c(h, 0.18f, 0.14f),
            outline = c(h, 0.08f, 0.56f),
            outlineVariant = c(h, 0.1f, 0.3f),
            surfaceBright = c(h, 0.14f, 0.22f),
            surfaceDim = c(h, 0.18f, 0.07f),
            surfaceContainerLowest = c(h, 0.2f, 0.05f),
            surfaceContainerLow = c(h, 0.16f, 0.1f),
            surfaceContainer = c(h, 0.15f, 0.12f),
            surfaceContainerHigh = c(h, 0.14f, 0.16f),
            surfaceContainerHighest = c(h, 0.13f, 0.2f),
        )
    }
}
