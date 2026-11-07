package com.akoya.home.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.akoya.home.data.LauncherConfig

/**
 * Akoya palettes. Akoya Living's identity is zen / Japanese-inspired and named after the
 * Akoya pearl, so the presets lean on pearl, ink, stone, sage and lotus tones.
 */
data class AkoyaPalette(
    val id: String,
    val name: String,
    val background: Color,
    val backgroundAlt: Color,
    val surface: Color,
    val accent: Color,
    val text: Color,
    val muted: Color,
    val isLight: Boolean = false,
)

val Palettes = listOf(
    AkoyaPalette(
        id = "midnight_pearl", name = "Midnight Pearl",
        background = Color(0xFF0E1116), backgroundAlt = Color(0xFF1C2431),
        surface = Color(0x1FFFFFFF), accent = Color(0xFFD6C4A5),
        text = Color(0xFFF3EEE6), muted = Color(0xFFA9A39A),
    ),
    AkoyaPalette(
        id = "pearl", name = "Pearl",
        background = Color(0xFFF3EEE6), backgroundAlt = Color(0xFFE2D8C8),
        surface = Color(0x14000000), accent = Color(0xFF8C6F4E),
        text = Color(0xFF1E1B18), muted = Color(0xFF6E665C), isLight = true,
    ),
    AkoyaPalette(
        id = "sumi_gold", name = "Sumi & Gold",
        background = Color(0xFF090909), backgroundAlt = Color(0xFF1C1814),
        surface = Color(0x1AFFFFFF), accent = Color(0xFFC9A14A),
        text = Color(0xFFF5F1E8), muted = Color(0xFF9E978A),
    ),
    AkoyaPalette(
        id = "zen_garden", name = "Zen Garden",
        background = Color(0xFF121713), backgroundAlt = Color(0xFF263024),
        surface = Color(0x1FFFFFFF), accent = Color(0xFFAFC29F),
        text = Color(0xFFEEF1E8), muted = Color(0xFF9DA596),
    ),
    AkoyaPalette(
        id = "lotus", name = "Lotus",
        background = Color(0xFF1A1216), backgroundAlt = Color(0xFF38242E),
        surface = Color(0x1FFFFFFF), accent = Color(0xFFE6B8C2),
        text = Color(0xFFF8EEF0), muted = Color(0xFFB09CA2),
    ),
    AkoyaPalette(
        id = "harbour", name = "Harbour Blue",
        background = Color(0xFF0A1420), backgroundAlt = Color(0xFF15304A),
        surface = Color(0x1FFFFFFF), accent = Color(0xFF93C5E8),
        text = Color(0xFFEFF5FA), muted = Color(0xFF94A6B6),
    ),
)

fun paletteFor(id: String): AkoyaPalette = Palettes.firstOrNull { it.id == id } ?: Palettes.first()

val LocalPalette = staticCompositionLocalOf { Palettes.first() }
val LocalConfig = staticCompositionLocalOf { LauncherConfig() }

object AkoyaType {
    val display = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Light, fontSize = 88.sp, letterSpacing = 2.sp)
    val wordmark = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 22.sp, letterSpacing = 8.sp)
    val title = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 30.sp, letterSpacing = 0.5.sp)
    val section = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 2.sp)
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp)
    val label = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    val small = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp)
}

@Composable
fun AkoyaTheme(config: LauncherConfig, content: @Composable () -> Unit) {
    val p = paletteFor(config.themeId)
    val scheme = if (p.isLight) {
        lightColorScheme(primary = p.accent, background = p.background, surface = p.backgroundAlt, onSurface = p.text, onBackground = p.text)
    } else {
        darkColorScheme(primary = p.accent, background = p.background, surface = p.backgroundAlt, onSurface = p.text, onBackground = p.text)
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalPalette provides p, LocalConfig provides config, content = content)
    }
}
