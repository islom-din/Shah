package rynfell.islom.shah.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB4D9C5),
    onPrimary = Color(0xFF17382B),
    tertiary = Color(0xFFF0C17E),
    background = Color(0xFF101C18),
    onBackground = Color(0xFFF0F4EE),
    surface = Color(0xFF16251F),
    onSurface = Color(0xFFF0F4EE),
    onSurfaceVariant = Color(0xFFBECBC3),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF285D48),
    onPrimary = Color.White,
    tertiary = Color(0xFF9C5A20),
    background = Color(0xFFF7F5EE),
    onBackground = Color(0xFF182C23),
    surface = Color(0xFFFFFDF5),
    onSurface = Color(0xFF263C30),
    onSurfaceVariant = Color(0xFF495B51),
)

@Composable
fun ShahTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
