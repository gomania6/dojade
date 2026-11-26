package pl.poznan.offdojade.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// ZTM Poznań Brand & Transit Palette
val ZtmGreen = Color(0xFF006B54)
val ZtmGreenLight = Color(0xFF22C55E)
val ZtmTramColor = Color(0xFF007A5E)
val ZtmBusColor = Color(0xFF1565C0)
val LiveGreen = Color(0xFF16A34A)
val DeparturePillGreen = Color(0xFF16A34A)
val ArrivalPillBlue = Color(0xFF0EA5E9)
val OfflineGray = Color(0xFF6B7280)
val DelayAmber = Color(0xFFD97706)
val DelayRed = Color(0xFFDC2626)

private val LightColors = lightColorScheme(
    primary = ZtmGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2F0DF),
    onPrimaryContainer = Color(0xFF002117),
    secondary = ZtmBusColor,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E3FF),
    onSecondaryContainer = Color(0xFF001C3B),
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF111816),
    surface = Color.White,
    onSurface = Color(0xFF111816),
    surfaceVariant = Color(0xFFE7ECEA),
    onSurfaceVariant = Color(0xFF4A5450),
    outline = Color(0xFFD0D7D4),
    error = DelayRed,
    onError = Color.White
)

// True AMOLED Pitch-Black Dark Theme
private val AmoledDarkColors = darkColorScheme(
    primary = ZtmGreenLight,
    onPrimary = Color(0xFF002914),
    primaryContainer = Color(0xFF0D2E1E),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = ArrivalPillBlue,
    onSecondary = Color(0xFF00263B),
    secondaryContainer = Color(0xFF0C2A3D),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = Color(0xFFFACC15),
    onTertiary = Color(0xFF2A2100),
    tertiaryContainer = Color(0xFF2E260A),
    onTertiaryContainer = Color(0xFFFEF08A),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF121214),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF1C1C20),
    onSurfaceVariant = Color(0xFF9A9AA4),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0C),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF1A1A1E),
    surfaceContainerHighest = Color(0xFF232328),
    outline = Color(0xFF2E2E35),
    outlineVariant = Color(0xFF222228),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

// Типографика увеличена для комфортной читаемости на современных AMOLED-экранах
private val BaseTypography = Typography()
private val OffDojadeTypography = Typography(
    headlineLarge = BaseTypography.headlineLarge.copy(fontSize = 38.sp, lineHeight = 46.sp),
    headlineMedium = BaseTypography.headlineMedium.copy(fontSize = 33.sp, lineHeight = 40.sp),
    headlineSmall = BaseTypography.headlineSmall.copy(fontSize = 29.sp, lineHeight = 36.sp),
    titleLarge = BaseTypography.titleLarge.copy(fontSize = 26.sp, lineHeight = 32.sp),
    titleMedium = BaseTypography.titleMedium.copy(fontSize = 19.5.sp, lineHeight = 26.sp),
    titleSmall = BaseTypography.titleSmall.copy(fontSize = 17.sp, lineHeight = 23.sp),
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 26.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontSize = 17.sp, lineHeight = 23.sp),
    bodySmall = BaseTypography.bodySmall.copy(fontSize = 15.sp, lineHeight = 20.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontSize = 16.5.sp, lineHeight = 22.sp),
    labelMedium = BaseTypography.labelMedium.copy(fontSize = 14.5.sp, lineHeight = 19.sp),
    labelSmall = BaseTypography.labelSmall.copy(fontSize = 13.sp, lineHeight = 17.sp)
)

@Composable
fun OffDojadeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AmoledDarkColors else LightColors,
        typography = OffDojadeTypography,
        content = content
    )
}
