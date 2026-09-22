package app.gather.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Capture = Color(0xFFFF593E)
val CameraBackdrop = Color(0xFF17251F)
val AvatarInk = Color(0xFF252925)
private val Light =
    lightColorScheme(
        primary = Color(0xFF206F60),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCEBE3),
        onPrimaryContainer = Color(0xFF174E43),
        secondary = Color(0xFF206F60),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCEBE3),
        onSecondaryContainer = Color(0xFF174E43),
        background = Color(0xFFF5F3EE),
        onBackground = AvatarInk,
        surface = Color(0xFFFFFDFA),
        onSurface = AvatarInk,
        surfaceVariant = Color(0xFFECEAE3),
        onSurfaceVariant = Color(0xFF686B64),
        surfaceContainer = Color(0xFFECEAE3),
        surfaceContainerLow = Color(0xFFFFFDFA),
        surfaceContainerHigh = Color(0xFFECEAE3),
        surfaceContainerHighest = Color(0xFFECEAE3),
        outline = Color(0xFF6B756D),
        outlineVariant = Color(0xFFDFDED7),
        error = Color(0xFFB3261E),
        onError = Color.White,
    )
private val Dark =
    darkColorScheme(
        primary = Color(0xFF9AD4BE),
        onPrimary = CameraBackdrop,
        primaryContainer = Color(0xFF304D40),
        onPrimaryContainer = Color(0xFFDCEBE3),
        secondary = Color(0xFF9AD4BE),
        onSecondary = CameraBackdrop,
        secondaryContainer = Color(0xFF304D40),
        onSecondaryContainer = Color(0xFFDCEBE3),
        background = CameraBackdrop,
        onBackground = Color(0xFFEDEFE9),
        surface = Color(0xFF22332A),
        onSurface = Color(0xFFEDEFE9),
        surfaceVariant = Color(0xFF304037),
        onSurfaceVariant = Color(0xFFB2BDB4),
        surfaceContainer = Color(0xFF22332A),
        surfaceContainerLow = Color(0xFF22332A),
        surfaceContainerHigh = Color(0xFF304037),
        surfaceContainerHighest = Color(0xFF304037),
        outline = Color(0xFF95A69A),
        outlineVariant = Color(0xFF47564C),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )

private fun type(
    size: Int,
    line: Int,
    weight: FontWeight = FontWeight.Normal,
    serif: Boolean = false,
) =
    TextStyle(
        fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
    )

@Composable
fun GenoaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes =
            Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(24.dp),
            ),
        typography =
            Typography(
                headlineLarge = type(32, 40, serif = true),
                headlineMedium = type(28, 34, serif = true),
                headlineSmall = type(28, 34, serif = true),
                titleLarge = type(20, 28, FontWeight.SemiBold),
                titleMedium = type(16, 22, FontWeight.SemiBold),
                titleSmall = type(14, 20, FontWeight.SemiBold),
                bodyLarge = type(16, 24),
                bodyMedium = type(14, 20),
                bodySmall = type(14, 20),
                labelLarge = type(15, 20, FontWeight.SemiBold),
                labelMedium = type(12, 16, FontWeight.Medium),
                labelSmall = type(12, 16, FontWeight.Medium),
            ),
        content = content,
    )
}
