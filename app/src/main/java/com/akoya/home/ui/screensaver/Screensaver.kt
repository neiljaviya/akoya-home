package com.akoya.home.ui.screensaver

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.akoya.home.data.ConfigStore
import com.akoya.home.data.LauncherConfig
import com.akoya.home.data.ScreensaverStyle
import com.akoya.home.data.Festivals
import com.akoya.home.ui.components.drawThemeGradient
import com.akoya.home.ui.components.drawMist
import com.akoya.home.ui.components.formatAmPm
import com.akoya.home.ui.components.formatDate
import com.akoya.home.ui.components.formatTime
import com.akoya.home.ui.components.rememberNow
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Shared by the in-launcher idle screensaver and the system DreamService. */
@Composable
fun ScreensaverContent(config: LauncherConfig, store: ConfigStore, modifier: Modifier = Modifier) {
    val ss = config.screensaver
    val p = LocalPalette.current
    val festival = remember(config.festivalMode, config.seasonalArt) {
        Festivals.active(java.time.LocalDate.now(), config.festivalMode, config.seasonalArt)
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        when (ss.style) {
            ScreensaverStyle.SIGNAGE -> SignageRotation(config, store, festival)
            ScreensaverStyle.DAY_PROGRESS -> DayProgressScene(config, p)
            ScreensaverStyle.ZEN -> ZenScene(config, p)
            ScreensaverStyle.FESTIVE -> FestiveScene(config, festival ?: Festivals.season(java.time.LocalDate.now()), p)
            ScreensaverStyle.PHOTOS -> {
                val photos = remember { store.images(store.screensaverDir).ifEmpty { store.images(store.wallpapersDir) } }
                if (photos.isEmpty()) {
                    AmbientPearl()
                    CenterClock(config)
                } else {
                    Slideshow(photos.map { it as Any }, ss.photoSeconds)
                    if (ss.showClock) CornerClock(config, Modifier.align(Alignment.BottomStart))
                }
            }
            ScreensaverStyle.CLOCK -> FloatingClock(config)
            ScreensaverStyle.AMBIENT -> {
                AmbientPearl()
                if (ss.showClock) CenterClock(config)
            }
        }
    }
}

@Composable
internal fun Slideshow(photos: List<Any>, seconds: Int) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(photos) {
        while (true) {
            delay(seconds.coerceAtLeast(3) * 1000L)
            index = (index + 1) % photos.size
        }
    }
    Crossfade(targetState = index, animationSpec = tween(1600), label = "slides") { i ->
        // Slow Ken Burns zoom on each photo.
        val zoom = remember { Animatable(1f) }
        val drift = remember { Random.nextFloat() - 0.5f }
        LaunchedEffect(Unit) {
            zoom.animateTo(1.12f, tween(seconds * 1000 + 2000, easing = LinearEasing))
        }
        AsyncImage(
            model = photos[i % photos.size],
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom.value; scaleY = zoom.value
                    translationX = drift * (zoom.value - 1f) * size.width
                },
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))),
    )
}

@Composable
private fun AmbientPearl() {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "ambient")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(90_000, easing = LinearEasing)), label = "phase")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(p.background)
        drawMist(p, phase)
    }
}

@Composable
private fun CenterClock(config: LauncherConfig) {
    val p = LocalPalette.current
    val now by rememberNow(config.showSeconds)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatTime(now, config.use24h, config.showSeconds), style = AkoyaType.display.copy(fontSize = 120.sp), color = p.text.copy(alpha = 0.92f))
                val ampm = formatAmPm(now, config.use24h)
                if (ampm.isNotEmpty()) Text(ampm, style = AkoyaType.title, color = p.accent, modifier = Modifier.padding(start = 10.dp, bottom = 28.dp))
            }
            Text(formatDate(now), style = AkoyaType.title, color = p.muted)
        }
        Text(config.brandName.uppercase(), style = AkoyaType.section, color = p.accent.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
    }
}

@Composable
internal fun CornerClock(config: LauncherConfig, modifier: Modifier) {
    val now by rememberNow(false)
    Column(modifier.padding(start = 64.dp, bottom = 48.dp)) {
        Text(formatTime(now, config.use24h, false) + " " + formatAmPm(now, config.use24h), style = AkoyaType.display.copy(fontSize = 56.sp), color = Color.White)
        Text(formatDate(now), style = AkoyaType.body, color = Color.White.copy(alpha = 0.8f))
    }
}

/** Dim clock that glides to a new spot every 30 s (prevents burn-in). */
@Composable
private fun FloatingClock(config: LauncherConfig) {
    val p = LocalPalette.current
    val now by rememberNow(config.showSeconds)
    val x = remember { Animatable(0.3f) }
    val y = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            val tx = Random.nextFloat(); val ty = Random.nextFloat()
            coroutineScope {
                launch { x.animateTo(tx, tween(6_000)) }
                launch { y.animateTo(ty, tween(6_000)) }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth * 0.55f
        val h = constraints.maxHeight * 0.65f
        Column(Modifier.offset { IntOffset((x.value * w).toInt(), (y.value * h).toInt()) }.padding(32.dp)) {
            Text(formatTime(now, config.use24h, config.showSeconds) + " " + formatAmPm(now, config.use24h), style = AkoyaType.display, color = p.text.copy(alpha = 0.75f))
            Text(formatDate(now), style = AkoyaType.body, color = p.muted)
        }
    }
}
