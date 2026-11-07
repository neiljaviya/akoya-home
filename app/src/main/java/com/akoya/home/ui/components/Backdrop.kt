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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
            else -> Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawThemeGradient(p)
                        if (config.wallpaperMode != WallpaperMode.THEME) drawZenPattern(p, breathe = 0f)
                    },
            )
        }
    }
}

fun DrawScope.drawThemeGradient(p: AkoyaPalette) {
    drawRect(Brush.verticalGradient(listOf(p.backgroundAlt, p.background, p.background)))
    drawRect(
        Brush.radialGradient(
            colors = listOf(p.accent.copy(alpha = if (p.isLight) 0.22f else 0.14f), Color.Transparent),
            center = Offset(size.width * 0.86f, size.height * 0.08f),
            radius = size.width * 0.55f,
        ),
    )
}

/** Enso-like concentric rings around a "pearl", plus faint shoji lines. breathe in 0..1. */
fun DrawScope.drawZenPattern(p: AkoyaPalette, breathe: Float) {
    val center = Offset(size.width * 0.80f, size.height * 0.30f)
    val base = size.minDimension * 0.16f * (1f + breathe * 0.04f)
    for (i in 0 until 7) {
        val r = base + i * size.minDimension * 0.075f
        drawCircle(
            color = p.accent.copy(alpha = (0.16f - i * 0.02f).coerceAtLeast(0.025f)),
            radius = r,
            center = center,
            style = Stroke(width = if (i == 0) 2.5f else 1.2f),
        )
    }
    drawCircle(
        brush = Brush.radialGradient(
            listOf(p.text.copy(alpha = 0.30f), p.accent.copy(alpha = 0.10f), Color.Transparent),
            center = center,
            radius = base * 0.9f,
        ),
        radius = base * 0.9f,
        center = center,
    )
    val lineGap = size.height * 0.035f
    var y = size.height * 0.74f
    while (y < size.height) {
        drawLine(p.text.copy(alpha = 0.035f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += lineGap
    }
}

@Composable
fun BrandMark(store: ConfigStore, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val logo = remember { store.logoFile() }
    if (logo != null) {
        AsyncImage(model = logo, contentDescription = "Logo", modifier = modifier.height(40.dp))
    } else {
        Text("AKOYA", style = AkoyaType.wordmark, color = p.accent, modifier = modifier)
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
