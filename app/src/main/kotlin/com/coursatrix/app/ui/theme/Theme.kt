package com.coursatrix.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coursatrix.core.model.AccentColor
import com.coursatrix.core.model.ThemeMode

/** Primary / container pairs for light and dark mode; neutrals are shared by all accents. */
private class Accent(val primary: Long, val container: Long, val onContainer: Long,
                     val darkPrimary: Long, val darkOnPrimary: Long, val darkContainer: Long, val darkOnContainer: Long)

private val accents = mapOf(
    AccentColor.VIOLET to Accent(0xFF6257BF, 0xFFEAE6FF, 0xFF352B70, 0xFFC5BAFF, 0xFF302563, 0xFF41357C, 0xFFEAE2FF),
    AccentColor.BLUE to Accent(0xFF2F64B5, 0xFFDCE7FF, 0xFF0D2F66, 0xFFAEC8FF, 0xFF06306B, 0xFF1F4A8F, 0xFFD9E5FF),
    AccentColor.TEAL to Accent(0xFF2E7D73, 0xFFD6F0EA, 0xFF0B3D37, 0xFF95D4C8, 0xFF00382F, 0xFF1D5A51, 0xFFC7EFE6),
    AccentColor.ORANGE to Accent(0xFFB25A1E, 0xFFFFE3D1, 0xFF5A2602, 0xFFFFB78D, 0xFF552100, 0xFF7C3A0E, 0xFFFFDCC7),
    AccentColor.PINK to Accent(0xFFA8466F, 0xFFFFDDE8, 0xFF55102F, 0xFFFFB0CB, 0xFF5A1131, 0xFF7E2D50, 0xFFFFD9E4),
)

/** Swatch shown in the appearance settings. */
fun accentSwatch(accent: AccentColor): Color = Color(accents.getValue(if (accent == AccentColor.DYNAMIC) AccentColor.VIOLET else accent).primary)

private fun lightScheme(a: Accent) = lightColorScheme(
    primary = Color(a.primary), onPrimary = Color.White,
    primaryContainer = Color(a.container), onPrimaryContainer = Color(a.onContainer),
    secondary = Color(0xFF397E75), secondaryContainer = Color(a.container),
    background = Color(0xFFF7F7FC), onBackground = Color(0xFF202332),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF202332),
    surfaceVariant = Color(0xFFEFEEF6), onSurfaceVariant = Color(0xFF666579),
    outlineVariant = Color(0xFFE0DFEB),
)

private fun darkScheme(a: Accent) = darkColorScheme(
    primary = Color(a.darkPrimary), onPrimary = Color(a.darkOnPrimary),
    primaryContainer = Color(a.darkContainer), onPrimaryContainer = Color(a.darkOnContainer),
    secondary = Color(0xFFA3D4C8), secondaryContainer = Color(a.darkContainer),
    background = Color(0xFF14141D), onBackground = Color(0xFFE8E6F3),
    surface = Color(0xFF20202D), onSurface = Color(0xFFE8E6F3),
    surfaceVariant = Color(0xFF2C2B3A), onSurfaceVariant = Color(0xFFBDB9CF),
    outlineVariant = Color(0xFF414051),
)

@Composable
fun CoursatrixTheme(mode: ThemeMode, accent: AccentColor, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val context = LocalContext.current
    val scheme = if (accent == AccentColor.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val palette = accents.getValue(if (accent == AccentColor.DYNAMIC) AccentColor.VIOLET else accent)
        if (dark) darkScheme(palette) else lightScheme(palette)
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content,
    )
}
