package pl.poznan.offdojade.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ZTM Poznań Brand & Transit Palette
val ZtmGreen = Color(0xFF006B54)
val ZtmGreenLight = Color(0xFF4ADBA8)
val ZtmTramColor = Color(0xFF007A5E)
val ZtmBusColor = Color(0xFF1565C0)
val LiveGreen = Color(0xFF16A34A)
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
    onSurfaceVariant = Color(0xFF3E4945),
    error = DelayRed,
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = ZtmGreenLight,
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFFC2F0DF),
    secondary = Color(0xFF90CAF9),
    onSecondary = Color(0xFF003258),
    secondaryContainer = Color(0xFF00497D),
    onSecondaryContainer = Color(0xFFD5E3FF),
    background = Color(0xFF0F1412),
    onBackground = Color(0xFFE6ECE9),
    surface = Color(0xFF171E1B),
    onSurface = Color(0xFFE6ECE9),
    surfaceVariant = Color(0xFF232C28),
    onSurfaceVariant = Color(0xFFB7C4BE),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

@Composable
fun OffDojadeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
