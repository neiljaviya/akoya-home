package com.akoya.home.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.akoya.home.R
import com.akoya.home.data.Festival
import com.akoya.home.data.LauncherConfig

/**
 * Palettes from the Akoya lobby signage: Obsidian, Kumo White and Sandstone, plus two
 * pearl ("nacre") variants. `mist` are the soft drifting glows used behind each scene.
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
    val mist: List<Color>,
    val isLight: Boolean = false,
)

private val Obsidian = Color(0xFF141311)
private val Kumo = Color(0xFFF1EEE8)
private val Sandstone = Color(0xFFC8B79D)
private val SandstoneDeep = Color(0xFF8C795E)
private val InkSoft = Color(0xFF5A544A)
private val Ash = Color(0xFFA39D91)

val Palettes = listOf(
    AkoyaPalette(
        id = "obsidian", name = "Obsidian",
        background = Obsidian, backgroundAlt = Color(0xFF211F1B),
        surface = Color(0x14F1EEE8), accent = Sandstone,
        text = Kumo, muted = Ash,
        mist = listOf(Sandstone.copy(alpha = 0.17f), Sandstone.copy(alpha = 0.09f), Kumo.copy(alpha = 0.04f)),
    ),
    AkoyaPalette(
        id = "kumo", name = "Kumo White",
        background = Kumo, backgroundAlt = Color(0xFFE7E1D7),
        surface = Color(0x0F141311), accent = SandstoneDeep,
        text = Obsidian, muted = InkSoft,
        mist = listOf(Sandstone.copy(alpha = 0.50f), Sandstone.copy(alpha = 0.30f), Color.White.copy(alpha = 0.5f)),
        isLight = true,
    ),
    AkoyaPalette(
        id = "sandstone", name = "Sandstone",
        background = Sandstone, backgroundAlt = Color(0xFFBBA98C),
        surface = Color(0x1A141311), accent = Obsidian,
        text = Obsidian, muted = Color(0xFF3D382F),
        mist = listOf(Kumo.copy(alpha = 0.40f), Kumo.copy(alpha = 0.22f), SandstoneDeep.copy(alpha = 0.18f)),
        isLight = true,
    ),
    AkoyaPalette(
        id = "nacre", name = "Nacre",
        background = Color(0xFFEFEAE4), backgroundAlt = Color(0xFFE6E0E4),
        surface = Color(0x0F141311), accent = SandstoneDeep,
        text = Obsidian, muted = InkSoft,
        mist = listOf(Color(0xFFE6CFD4).copy(alpha = 0.75f), Color(0xFFCFDDE6).copy(alpha = 0.70f), Color(0xFFE9E0C9).copy(alpha = 0.60f)),
        isLight = true,
    ),
    AkoyaPalette(
        id = "black_pearl", name = "Black Pearl",
        background = Color(0xFF121214), backgroundAlt = Color(0xFF1D1C21),
        surface = Color(0x14F1EEE8), accent = Color(0xFFD8CEC6),
        text = Kumo, muted = Color(0xFF9C99A3),
        mist = listOf(Color(0xFFB79AA2).copy(alpha = 0.16f), Color(0xFF8FA3B3).copy(alpha = 0.14f), Sandstone.copy(alpha = 0.08f)),
    ),
)

fun paletteFor(id: String): AkoyaPalette = Palettes.firstOrNull { it.id == id } ?: Palettes.first()

val LocalPalette = staticCompositionLocalOf { Palettes.first() }
val LocalConfig = staticCompositionLocalOf { LauncherConfig() }

/** Jost: the open stand-in the signage uses for Beausite Classic. */
val Jost = FontFamily(
    Font(R.font.jost_extralight, FontWeight.ExtraLight),
    Font(R.font.jost_light, FontWeight.Light),
    Font(R.font.jost_regular, FontWeight.Normal),
    Font(R.font.jost_medium, FontWeight.Medium),
)

object AkoyaType {
    val display = TextStyle(fontFamily = Jost, fontWeight = FontWeight.ExtraLight, fontSize = 92.sp, lineHeight = 96.sp, letterSpacing = (-2).sp)
    val wordmark = TextStyle(fontFamily = Jost, fontWeight = FontWeight.Light, fontSize = 24.sp, letterSpacing = 0.5.sp)
    val title = TextStyle(fontFamily = Jost, fontWeight = FontWeight.Light, fontSize = 28.sp)
    val section = TextStyle(fontFamily = Jost, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 3.4.sp)
    val body = TextStyle(fontFamily = Jost, fontWeight = FontWeight.Normal, fontSize = 16.sp)
    val label = TextStyle(fontFamily = Jost, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.4.sp)
    val small = TextStyle(fontFamily = Jost, fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.2.sp)
}

val LocalFestival = staticCompositionLocalOf<Festival?> { null }

/** Applies a festival's accent and glow on top of the chosen theme. Seasons only tint the glow. */
fun AkoyaPalette.withFestival(f: Festival?): AkoyaPalette {
    if (f == null) return this
    val glow = Color(f.glow)
    val tinted = listOf(glow.copy(alpha = if (isLight) 0.42f else 0.20f)) + mist.drop(1)
    return if (f.isSeason) copy(mist = tinted)
    else copy(accent = Color(if (isLight) f.accentLight else f.accentDark), mist = tinted)
}

@Composable
fun AkoyaTheme(config: LauncherConfig, festival: Festival? = null, content: @Composable () -> Unit) {
    val p = paletteFor(config.themeId).withFestival(festival)
    val scheme = if (p.isLight) {
        lightColorScheme(primary = p.accent, background = p.background, surface = p.backgroundAlt, onSurface = p.text, onBackground = p.text)
    } else {
        darkColorScheme(primary = p.accent, background = p.background, surface = p.backgroundAlt, onSurface = p.text, onBackground = p.text)
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalPalette provides p, LocalConfig provides config, LocalFestival provides festival, content = content)
    }
}
