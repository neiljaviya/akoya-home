package com.akoya.home.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.akoya.home.data.ConfigStore
import com.akoya.home.data.LauncherConfig
import com.akoya.home.data.WallpaperMode
import com.akoya.home.ui.theme.AkoyaPalette
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun Backdrop(config: LauncherConfig, store: ConfigStore, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val imageFile: File? = remember(config.wallpaperMode, config.wallpaperFile) {
        if (config.wallpaperMode == WallpaperMode.IMAGE) {
            config.wallpaperFile?.let { File(store.wallpapersDir, it) }?.takeIf { it.exists() }
                ?: store.images(store.wallpapersDir).firstOrNull()
        } else null
    }
    Box(modifier.fillMaxSize()) {
        when {
            config.wallpaperMode == WallpaperMode.SOLID ->
                Box(Modifier.fillMaxSize().background(p.background))
            imageFile != null -> {
                AsyncImage(
                    model = imageFile,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = config.wallpaperDim.coerceIn(0f, 0.9f)))
                        .background(Brush.verticalGradient(0f to Color.Transparent, 1f to p.background.copy(alpha = 0.85f))),
                )
            }
            // The gradients are costly to shade and never change: draw them once into a texture
            // and reuse it, instead of re-shading the whole screen on every frame of a scroll.
            else -> Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawBehind {
                        drawThemeGradient(p)
                        if (config.wallpaperMode != WallpaperMode.THEME) drawMist(p, 0f)
                    },
            )
        }
    }
}

fun DrawScope.drawThemeGradient(p: AkoyaPalette) {
    drawRect(Brush.verticalGradient(listOf(p.backgroundAlt, p.background, p.background)))
}

/**
 * The signage "mist": large soft glows that drift slowly. t in 0..1 is the drift phase
 * (0 = resting position used on the home screen).
 */
fun DrawScope.drawMist(p: AkoyaPalette, t: Float) {
    val a = t * 2f * Math.PI.toFloat()
    val w = size.width
    val h = size.height
    fun glow(color: Color, cx: Float, cy: Float, r: Float) = drawCircle(
        brush = Brush.radialGradient(listOf(color, color.copy(alpha = color.alpha * 0.45f), Color.Transparent), center = Offset(cx, cy), radius = r),
        radius = r,
        center = Offset(cx, cy),
    )
    glow(p.mist[0], w * (0.84f + 0.05f * kotlin.math.sin(a)), h * (0.10f + 0.08f * kotlin.math.cos(a)), w * 0.55f)
    glow(p.mist[1 % p.mist.size], w * (0.08f + 0.06f * kotlin.math.cos(a * 0.7f)), h * (0.98f - 0.06f * kotlin.math.sin(a)), w * 0.45f)
    glow(p.mist[2 % p.mist.size], w * (0.48f + 0.10f * kotlin.math.sin(a * 0.5f)), h * (0.55f + 0.08f * kotlin.math.cos(a * 1.3f)), w * 0.32f)
}

@Composable
fun BrandMark(store: ConfigStore, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val logo = remember { store.logoFile() }
    val name = com.akoya.home.ui.theme.LocalConfig.current.brandName
    if (logo != null) {
        AsyncImage(model = logo, contentDescription = "Logo", modifier = modifier.height(40.dp))
    } else {
        Text(name, style = AkoyaType.wordmark, color = p.text, modifier = modifier)
    }
}

/** Current time, ticking on the second or minute boundary. */
@Composable
fun rememberNow(withSeconds: Boolean): State<LocalDateTime> =
    produceState(LocalDateTime.now(), withSeconds) {
        while (true) {
            value = LocalDateTime.now()
            val step = if (withSeconds) 1_000L else 60_000L
            delay(step - System.currentTimeMillis() % step + 5)
        }
    }

private val fmt24 = DateTimeFormatter.ofPattern("HH:mm")
private val fmt24s = DateTimeFormatter.ofPattern("HH:mm:ss")
private val fmt12 = DateTimeFormatter.ofPattern("h:mm")
private val fmt12s = DateTimeFormatter.ofPattern("h:mm:ss")
private val fmtAmPm = DateTimeFormatter.ofPattern("a")
private val fmtDate = DateTimeFormatter.ofPattern("EEEE, d MMMM")

fun formatTime(t: LocalDateTime, use24h: Boolean, seconds: Boolean): String =
    t.format(if (use24h) (if (seconds) fmt24s else fmt24) else (if (seconds) fmt12s else fmt12))

fun formatAmPm(t: LocalDateTime, use24h: Boolean): String = if (use24h) "" else t.format(fmtAmPm).lowercase()

fun formatDate(t: LocalDateTime): String = t.format(fmtDate)

fun greetingFor(t: LocalDateTime): String = when (t.hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}
