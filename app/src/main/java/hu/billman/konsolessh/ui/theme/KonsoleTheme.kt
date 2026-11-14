package hu.billman.konsolessh.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

private val Colors = darkColorScheme(
    primary = KonsoleColors.Accent,
    onPrimary = Color.White,
    secondary = KonsoleColors.Accent,
    onSecondary = Color.White,
    background = KonsoleColors.Background,
    onBackground = KonsoleColors.TextPrimary,
    surface = KonsoleColors.Surface,
    onSurface = KonsoleColors.TextPrimary,
    surfaceVariant = KonsoleColors.Input,
    onSurfaceVariant = KonsoleColors.TextSecondary,
    surfaceContainerHigh = KonsoleColors.Surface,
    surfaceContainerHighest = KonsoleColors.Input,
    outline = KonsoleColors.TextSecondary,
    error = KonsoleColors.Error,
)

/** Monospace for everything a terminal user reads as data: hosts, names, keys. */
val Monospace = FontFamily.Monospace

/**
 * The app always uses its dark terminal palette, whatever the system theme:
 * the terminal itself is dark, and the screens around it match it.
 */
@Composable
fun KonsoleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
