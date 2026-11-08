package com.akoya.home.ui.screensaver

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akoya.home.data.ConfigStore
import com.akoya.home.data.Festival
import com.akoya.home.data.FestivalArt
import com.akoya.home.data.Festivals
import com.akoya.home.data.LauncherConfig
import com.akoya.home.ui.components.drawMist
import com.akoya.home.ui.components.formatAmPm
import com.akoya.home.ui.components.formatDate
import com.akoya.home.ui.components.formatTime
import com.akoya.home.ui.components.greetingFor
import com.akoya.home.ui.components.rememberNow
import com.akoya.home.ui.theme.AkoyaPalette
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.paletteFor
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** Drifting signage mist behind a scene. */
@Composable
fun MistLayer(p: AkoyaPalette) {
    val t by rememberSeconds()
    Canvas(Modifier.fillMaxSize()) {
        drawRect(p.background)
        drawMist(p, t / 90f)
    }
}

// ------------------------------------------------------------------ Day progress clock

@Composable
fun DayProgressScene(config: LauncherConfig, p: AkoyaPalette) {
    val now by rememberNow(withSeconds = true)
    val pct = now.toLocalTime().toSecondOfDay() / 86_400f
    val week = now.get(WeekFields.ISO.weekOfWeekBasedYear())
    BoxWithConstraints(Modifier.fillMaxSize()) {
        MistLayer(p)
        val h = maxHeight.value
        val mh = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = maxWidth * 0.0625f, vertical = maxHeight * 0.09f),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(greetingFor(now).uppercase(), style = AkoyaType.section.copy(fontSize = (h * 0.022f).sp), color = p.accent)
                Spacer(Modifier.weight(1f))
                Text("WEEK $week", style = AkoyaType.section.copy(fontSize = (h * 0.022f).sp), color = p.muted)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatTime(now, config.use24h, false),
                    style = AkoyaType.display.copy(fontSize = (h * 0.30f).sp, lineHeight = (h * 0.30f).sp, letterSpacing = (-(h * 0.008f)).sp),
                    color = p.text,
                )
                val ampm = formatAmPm(now, config.use24h)
                if (ampm.isNotEmpty()) {
                    Text(ampm.uppercase(), style = AkoyaType.title.copy(fontSize = (h * 0.05f).sp), color = p.accent, modifier = Modifier.padding(start = 12.dp, bottom = (h * 0.04f).dp))
                }
            }
            Text(formatDate(now), style = AkoyaType.title.copy(fontSize = (h * 0.045f).sp), color = p.muted)
            Spacer(Modifier.height(mh * 0.07f))
            Text("TODAY IS ${(pct * 100).toInt()}% COMPLETE", style = AkoyaType.section.copy(fontSize = (h * 0.02f).sp), color = p.muted)
            Spacer(Modifier.height(12.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            ) {
                val y = size.height * 0.4f
                drawLine(p.text.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), 2f)
                drawLine(p.accent, Offset(0f, y), Offset(size.width * pct, y), 3f)
                for (hr in 0..24) {
                    val x = size.width * hr / 24f
                    val major = hr % 6 == 0
                    drawLine(p.text.copy(alpha = 0.18f), Offset(x, y + 6f), Offset(x, y + if (major) 22f else 12f), 2f)
                }
                drawCircle(p.accent, 9f, Offset(size.width * pct, y))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("12 AM", "6 AM", "12 PM", "6 PM", "12 AM").forEach {
                    Text(it, style = AkoyaType.section.copy(fontSize = (h * 0.018f).sp), color = p.muted)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Week view

@Composable
fun WeekScene(p: AkoyaPalette) {
    val now by rememberNow(withSeconds = false)
    val today = now.toLocalDate()
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val dark = paletteFor("obsidian")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        MistLayer(p)
        val h = maxHeight.value
        val mh = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = maxWidth * 0.0625f, vertical = maxHeight * 0.1f),
        ) {
            Text(
                today.month.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                style = AkoyaType.display.copy(fontSize = (h * 0.20f).sp, lineHeight = (h * 0.21f).sp),
                color = p.text,
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(mh * 0.34f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (i in 0 until 7) {
                    val d = monday.plusDays(i.toLong())
                    val isToday = d == today
                    val fg = if (isToday) dark.text else p.text
                    val label = if (isToday) dark.accent else p.muted
                    val tag = Festivals.active(d, "auto", false)?.takeIf { !it.isSeason }?.name
                        ?: if (isToday) "Today" else ""
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (isToday) dark.background else Color.Transparent)
                            .drawBehind { drawLine(fg, Offset(0f, 0f), Offset(size.width, 0f), 3f) }
                            .padding(horizontal = 14.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(), style = AkoyaType.section.copy(fontSize = (h * 0.022f).sp), color = label)
                        Column {
                            Text("${d.dayOfMonth}", style = AkoyaType.display.copy(fontSize = (h * 0.12f).sp, lineHeight = (h * 0.12f).sp), color = fg)
                            Text(tag.uppercase(), style = AkoyaType.section.copy(fontSize = (h * 0.017f).sp), color = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Live life zen (breathing ring)

@Composable
fun ZenScene(config: LauncherConfig, p: AkoyaPalette) {
    val t by rememberSeconds()
    val now by rememberNow(withSeconds = false)
    val cycle = t % 10f
    val inhale = cycle < 5f
    // ease in/out over 5 s each way
    val u = if (inhale) cycle / 5f else 1f - (cycle - 5f) / 5f
    val eased = (1 - kotlin.math.cos(u * Math.PI.toFloat())) / 2f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        MistLayer(p)
        val h = maxHeight.value
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height * 0.47f)
            val base = size.minDimension * 0.17f
            val r = base * (0.82f + 0.36f * eased)
            drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.28f), Color.Transparent), c, r * 1.6f), r * 1.6f, c)
            drawCircle(p.text.copy(alpha = 0.10f), r, c)
            drawCircle(p.text.copy(alpha = 0.75f), r, c, style = Stroke(2.5f))
            drawCircle(p.text.copy(alpha = 0.18f), base * 1.3f, c, style = Stroke(1.2f))
        }
        Text("LIVE LIFE ZEN", style = AkoyaType.section.copy(fontSize = (h * 0.024f).sp), color = p.text, modifier = Modifier.align(Alignment.TopStart).padding(start = maxWidth * 0.0625f, top = maxHeight * 0.09f))
        Text(
            formatTime(now, config.use24h, false) + " " + formatAmPm(now, config.use24h).uppercase(),
            style = AkoyaType.section.copy(fontSize = (h * 0.024f).sp),
            color = p.muted,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = maxWidth * 0.0625f, top = maxHeight * 0.09f),
        )
        Crossfade(targetState = inhale, animationSpec = tween(900), label = "breath", modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.12f)) { inn ->
            Text(if (inn) "Breathe in" else "Breathe out", style = AkoyaType.title.copy(fontSize = (h * 0.05f).sp), color = p.text)
        }
    }
}

// ------------------------------------------------------------------ Festival / season card

private val LIGHT_ARTS = setOf(FestivalArt.HOLI, FestivalArt.KITES, FestivalArt.RAKHI, FestivalArt.TORAN)

@Composable
fun FestiveScene(config: LauncherConfig, festival: Festival, p: AkoyaPalette) {
    val now by rememberNow(withSeconds = false)
    val lightArt = festival.art in LIGHT_ARTS || (festival.isSeason && p.isLight)
    val fg = if (lightArt) Color(0xFF141311) else Color(0xFFF1EEE8)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        FestivalScene(festival, p)
        val h = maxHeight.value
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = maxWidth * 0.0625f, bottom = maxHeight * 0.09f),
        ) {
            if (!festival.isSeason) {
                Text(config.brandName.uppercase(), style = AkoyaType.section.copy(fontSize = (h * 0.02f).sp), color = fg.copy(alpha = 0.75f))
                Text(festival.greeting, style = AkoyaType.display.copy(fontSize = (h * 0.11f).sp, lineHeight = (h * 0.12f).sp), color = fg)
            }
            Text(
                formatTime(now, config.use24h, false) + " " + formatAmPm(now, config.use24h) + "  ·  " + formatDate(now),
                style = AkoyaType.title.copy(fontSize = (h * 0.04f).sp),
                color = fg.copy(alpha = 0.85f),
            )
        }
    }
}

// ------------------------------------------------------------------ Rotation (like the lobby signage)

private enum class Scene { DAY, WEEK, ZEN, FESTIVE, PHOTOS }

@Composable
fun SignageRotation(config: LauncherConfig, store: ConfigStore, festival: Festival?) {
    val photos = remember { store.images(store.screensaverDir) }
    val scenes = remember(festival, photos) {
        buildList {
            add(Scene.DAY)
            if (festival != null) add(Scene.FESTIVE)
            add(Scene.WEEK)
            add(Scene.ZEN)
            if (photos.isNotEmpty()) add(Scene.PHOTOS)
        }
    }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(scenes) {
        while (true) {
            delay(if (scenes[index % scenes.size] == Scene.PHOTOS) (config.screensaver.photoSeconds * 3 * 1000L).coerceAtLeast(20_000) else 20_000L)
            index = (index + 1) % scenes.size
        }
    }
    Crossfade(targetState = scenes[index % scenes.size], animationSpec = tween(1400), label = "signage") { scene ->
        when (scene) {
            Scene.DAY -> DayProgressScene(config, paletteFor("obsidian"))
            Scene.WEEK -> WeekScene(paletteFor("kumo"))
            Scene.ZEN -> ZenScene(config, paletteFor("sandstone"))
            Scene.FESTIVE -> FestiveScene(config, festival ?: Festivals.season(LocalDate.now()), paletteFor("obsidian"))
            Scene.PHOTOS -> Box(Modifier.fillMaxSize()) {
                Slideshow(photos.map { it as Any }, config.screensaver.photoSeconds)
                CornerClock(config, Modifier.align(Alignment.BottomStart))
            }
        }
    }
}
