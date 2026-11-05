package com.yeemusic.android.ui.theme

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

val YeeBlue = Color(0xFF87B9FF)

private val LightColors = lightColorScheme(
    primary = Color(0xFF356AE6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EDFF),
    onPrimaryContainer = Color(0xFF214A9B),
    secondary = Color(0xFF657590),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF0FA),
    onSecondaryContainer = Color(0xFF33445E),
    tertiary = Color(0xFF7361B5),
    tertiaryContainer = Color(0xFFF0EAFE),
    onTertiaryContainer = Color(0xFF493978),
    background = Color(0xFFF7F9FD),
    onBackground = Color(0xFF18243B),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF1F8),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F5FB),
    surfaceContainer = Color(0xFFEBF0F8),
    surfaceContainerHigh = Color(0xFFE4EAF5),
    surfaceContainerHighest = Color(0xFFDDE5F1),
    onSurface = Color(0xFF18243B),
    onSurfaceVariant = Color(0xFF65728A),
    outline = Color(0xFF8C99AF),
    outlineVariant = Color(0xFFDFE6F1),
    error = Color(0xFFBA3545),
)

private val DarkColors = darkColorScheme(
    primary = YeeBlue,
    onPrimary = Color(0xFF132D57),
    primaryContainer = Color(0xFF233D68),
    onPrimaryContainer = Color(0xFFD9E7FF),
    secondary = Color(0xFFB0BFD8),
    onSecondary = Color(0xFF24344C),
    secondaryContainer = Color(0xFF26334A),
    onSecondaryContainer = Color(0xFFDDE6F5),
    tertiary = Color(0xFFC7B4F4),
    tertiaryContainer = Color(0xFF393054),
    onTertiaryContainer = Color(0xFFECDEFF),
    background = Color(0xFF0D1421),
    onBackground = Color(0xFFE5ECF8),
    surface = Color(0xFF141E2E),
    surfaceVariant = Color(0xFF263248),
    surfaceContainerLowest = Color(0xFF0A101B),
    surfaceContainerLow = Color(0xFF172233),
    surfaceContainer = Color(0xFF1C293C),
    surfaceContainerHigh = Color(0xFF243249),
    surfaceContainerHighest = Color(0xFF2D3C54),
    onSurface = Color(0xFFE5ECF8),
    onSurfaceVariant = Color(0xFFA5B3CB),
    outline = Color(0xFF7B8CA8),
    outlineVariant = Color(0xFF2D3B52),
    error = Color(0xFFFFB3BC),
)

private val YeeTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

/** Theme id stored in settings: "light" · "dark" · "system". */
@Composable
fun YeeMusicTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = YeeTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}

/** The lyric font scale multiplier, clamped. */
val Int.lyricScale: Float
    get() = this.coerceIn(60, 200) / 100f
