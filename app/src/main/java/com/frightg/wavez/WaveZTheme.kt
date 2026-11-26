package com.frightg.wavez

import android.os.Build
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class WaveZPalette(
    val chrome: Color,
    val panel: Color,
    val workspace: Color,
    val lane: Color,
    val border: Color,
    val wave: Color,
    val waveSel: Color,
    val waveRms: Color,
    val selectionBg: Color,
    val neon: Color,
    val cyan: Color,
    val amber: Color,
    val magenta: Color,
    val text: Color,
    val isDark: Boolean,
    val isMaterial3: Boolean
)

val LocalWaveZPalette = staticCompositionLocalOf {
    WaveZPalette(
        chrome = Color(0xFF22272E),
        panel = Color(0xFF2B323B),
        workspace = Color(0xFF181C22),
        lane = Color(0xFF11151B),
        border = Color(0xFF485362),
        wave = Color(0xFF5C85FF),
        waveSel = Color(0xFF7FA3FF),
        waveRms = Color(0xFF9BB8FF),
        selectionBg = Color(0xFF23324D),
        neon = Color(0xFF00E676),
        cyan = Color(0xFF00E5FF),
        amber = Color(0xFFFFB74D),
        magenta = Color(0xFFFF6EC7),
        text = Color(0xFFF2F6FA),
        isDark = true,
        isMaterial3 = false
    )
}

@Composable
fun WaveZTheme(
    isDark: Boolean,
    isMaterial3: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = if (isMaterial3) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (isDark) {
                darkColorScheme(
                    primary = Color(0xFFD0BCFF),
                    onPrimary = Color(0xFF381E72),
                    secondary = Color(0xFFCCC2DC),
                    background = Color(0xFF141218),
                    surface = Color(0xFF211F26),
                    surfaceVariant = Color(0xFF2B2930),
                    onBackground = Color(0xFFE6E0E9),
                    onSurface = Color(0xFFE6E0E9),
                    outline = Color(0xFF938F99)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF6750A4),
                    onPrimary = Color.White,
                    secondary = Color(0xFF625B71),
                    background = Color(0xFFFEF7FF),
                    surface = Color(0xFFF3EDF7),
                    surfaceVariant = Color(0xFFE7E0EC),
                    onBackground = Color(0xFF1D1B20),
                    onSurface = Color(0xFF1D1B20),
                    outline = Color(0xFF79747E)
                )
            }
        }
    } else {
        if (isDark) {
            darkColorScheme(
                primary = Color(0xFF00E676),
                onPrimary = Color.Black,
                secondary = Color(0xFF00E5FF),
                onSecondary = Color.Black,
                background = Color(0xFF181C22),
                onBackground = Color(0xFFF2F6FA),
                surface = Color(0xFF2B323B),
                onSurface = Color(0xFFF2F6FA),
                surfaceVariant = Color(0xFF22272E),
                onSurfaceVariant = Color(0xFFD2DCE6),
                outline = Color(0xFF485362)
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF2962FF),
                onPrimary = Color.White,
                secondary = Color(0xFF00838F),
                onSecondary = Color.White,
                background = Color(0xFFE8ECF2),
                onBackground = Color(0xFF111827),
                surface = Color(0xFFF5F7FA),
                onSurface = Color(0xFF111827),
                surfaceVariant = Color(0xFFDDE3EC),
                onSurfaceVariant = Color(0xFF334155),
                outline = Color(0xFF94A3B8)
            )
        }
    }

    val palette = if (isMaterial3) {
        WaveZPalette(
            chrome = colorScheme.surfaceContainerLow,
            panel = colorScheme.surfaceContainer,
            workspace = colorScheme.background,
            lane = colorScheme.surfaceContainerLowest,
            border = colorScheme.outlineVariant,
            wave = colorScheme.primary,
            waveSel = colorScheme.tertiary,
            waveRms = colorScheme.secondary.copy(alpha = 0.75f),
            selectionBg = colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.16f),
            neon = colorScheme.primary,
            cyan = colorScheme.tertiary,
            amber = if (isDark) Color(0xFFFFB74D) else Color(0xFFD97706),
            magenta = colorScheme.secondary,
            text = colorScheme.onSurface,
            isDark = isDark,
            isMaterial3 = true
        )
    } else {
        if (isDark) {
            WaveZPalette(
                chrome = Color(0xFF22272E),
                panel = Color(0xFF2B323B),
                workspace = Color(0xFF181C22),
                lane = Color(0xFF11151B),
                border = Color(0xFF485362),
                wave = Color(0xFF5C85FF),
                waveSel = Color(0xFF7FA3FF),
                waveRms = Color(0xFF9BB8FF),
                selectionBg = Color(0xFF23324D),
                neon = Color(0xFF00E676),
                cyan = Color(0xFF00E5FF),
                amber = Color(0xFFFFB74D),
                magenta = Color(0xFFFF6EC7),
                text = Color(0xFFF2F6FA),
                isDark = true,
                isMaterial3 = false
            )
        } else {
            // Classic Audacity Light Studio
            WaveZPalette(
                chrome = Color(0xFFDDE3EC),
                panel = Color(0xFFF3F6FA),
                workspace = Color(0xFFC8D0DC),
                lane = Color(0xFFF9FBFF),
                border = Color(0xFF94A3B8),
                wave = Color(0xFF3B5BDB),
                waveSel = Color(0xFF1D4ED8),
                waveRms = Color(0xFF748FFC),
                selectionBg = Color(0xFFBFDBFE),
                neon = Color(0xFF059669),
                cyan = Color(0xFF0284C7),
                amber = Color(0xFFD97706),
                magenta = Color(0xFFDB2777),
                text = Color(0xFF111827),
                isDark = false,
                isMaterial3 = false
            )
        }
    }

    val shapes = if (isMaterial3) {
        Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(22.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp)
        )
    } else {
        Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(10.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp)
        )
    }

    val typography = Typography(
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
    )

    CompositionLocalProvider(LocalWaveZPalette provides palette) {
        MaterialTheme(colorScheme = colorScheme, shapes = shapes, typography = typography, content = content)
    }
}
