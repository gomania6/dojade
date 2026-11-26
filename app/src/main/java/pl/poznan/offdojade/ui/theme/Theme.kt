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

// ZTM Poznań Brand & Transit Palette (softened, eye-friendly AMOLED colors)
val ZtmGreen = Color(0xFF1F7A63)
val ZtmGreenLight = Color(0xFF52C48A)
val ZtmTramColor = Color(0xFF2B8A72)
val ZtmBusColor = Color(0xFF3B74B8)
val NightBusWhite = Color(0xFFEAEFF5)
val NightBusOnWhite = Color(0xFF141820)
val LiveGreen = Color(0xFF44B87D)
val DeparturePillGreen = Color(0xFF359E6B)
val ArrivalPillBlue = Color(0xFF4898C9)
val OfflineGray = Color(0xFF7B828E)
val DelayAmber = Color(0xFFD4974A)
val DelayRed = Color(0xFFD96B6B)

private val LightColors = lightColorScheme(
    primary = ZtmGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0EFE3),
    onPrimaryContainer = Color(0xFF0A291F),
    secondary = ZtmBusColor,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6F7),
    onSecondaryContainer = Color(0xFF0E2340),
    background = Color(0xFFF6F7F8),
    onBackground = Color(0xFF181C20),
    surface = Color(0xFFFCFDFD),
    onSurface = Color(0xFF181C20),
    surfaceVariant = Color(0xFFE9ECEF),
    onSurfaceVariant = Color(0xFF525A62),
    outline = Color(0xFFD2D8DD),
    error = DelayRed,
    onError = Color.White
)

// True AMOLED Pitch-Black Dark Theme with soft, low-glare accents
private val AmoledDarkColors = darkColorScheme(
    primary = ZtmGreenLight,
    onPrimary = Color(0xFF072617),
    primaryContainer = Color(0xFF132C21),
    onPrimaryContainer = Color(0xFFB7EAD0),
    secondary = ArrivalPillBlue,
    onSecondary = Color(0xFF082233),
    secondaryContainer = Color(0xFF132736),
    onSecondaryContainer = Color(0xFFC5E1F4),
    tertiary = Color(0xFFE5C76B),
    onTertiary = Color(0xFF2B230A),
    tertiaryContainer = Color(0xFF2D2714),
    onTertiaryContainer = Color(0xFFF4E4AE),
    background = Color(0xFF000000),
    onBackground = Color(0xFFECEEF2),
    surface = Color(0xFF111215),
    onSurface = Color(0xFFECEEF2),
    surfaceVariant = Color(0xFF1B1D22),
    onSurfaceVariant = Color(0xFF9AA0AC),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0B0D),
    surfaceContainer = Color(0xFF111215),
    surfaceContainerHigh = Color(0xFF191B20),
    surfaceContainerHighest = Color(0xFF22252C),
    outline = Color(0xFF2E313A),
    outlineVariant = Color(0xFF21242B),
    error = Color(0xFFE07A7A),
    onError = Color(0xFF3E1010)
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
