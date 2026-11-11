package com.akoya.home.livetv

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akoya.home.ui.components.ScrollPace
import com.akoya.home.ui.components.formatAmPm
import com.akoya.home.ui.components.formatTime
import com.akoya.home.ui.components.rememberNow
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalConfig
import com.akoya.home.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MIN = 60_000L
private const val HALF_HOUR = 30 * MIN
private const val WINDOW_MIN = 150
private const val GUIDE_ROWS = 7
private val CHANNEL_COL = 200.dp
private val TIMELINE = 680.dp
private val ROW_HEIGHT = 44.dp
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM")

private fun local(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

/** "6:30 pm", in the launcher's clock style. */
fun clockText(ms: Long, use24h: Boolean): String {
    val t = local(ms)
    val ampm = formatAmPm(t, use24h)
    return formatTime(t, use24h, false) + if (ampm.isEmpty()) "" else " $ampm"
}

private fun dayText(ms: Long): String {
    val day = local(ms).toLocalDate()
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DAY_FORMAT)
    }
}

/** What's on [ch] now and next, refreshed each minute. Null when the guide has nothing for it. */
@Composable
fun rememberNowNext(ch: Channel): NowNext? {
    val guide = LiveTvStore.get(LocalContext.current).guide
    val version by guide.version.collectAsState()
    val clock by rememberNow(withSeconds = false)
    val key = remember(ch.id, version) { guide.keyFor(ch) }
    val result by produceState(key?.let { guide.cached(it, System.currentTimeMillis()) }, key, version, clock) {
        if (key == null) { value = null; return@produceState }
        val now = System.currentTimeMillis()
        guide.cached(key, now)?.let { value = it; return@produceState }
        // Don't hit the database for rows that are only flying past.
        if (ScrollPace.fast) delay(260)
        value = withContext(Dispatchers.IO) { guide.nowNext(key, now) }
    }
    return result?.takeIf { it.now != null || it.next != null }
}

/**
 * The TV guide: channels down the side, time across the top. The remote moves a cursor
 * (which channel, what time) and the screen is drawn around it, a screenful of rows at a time,
 * so it stays quick however long the channel list is.
 */
@Composable
fun GuideScreen(session: LiveTvSession, index: ChannelIndex) {
    val p = LocalPalette.current
    val config = LocalConfig.current
    val guide = session.store.guide
    val version by guide.version.collectAsState()
    val channels = session.guideChannels
    val clock by rememberNow(withSeconds = false)
    val now = remember(clock) { System.currentTimeMillis() }

    var row by remember { mutableIntStateOf(channels.indexOfFirst { it.id == session.current?.id }.coerceAtLeast(0)) }
    var cursor by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var windowStart by remember { mutableLongStateOf((System.currentTimeMillis() / HALF_HOUR) * HALF_HOUR - HALF_HOUR) }
    var note by remember { mutableStateOf<String?>(null) }
    val windowEnd = windowStart + WINDOW_MIN * MIN
    val focus = remember { FocusRequester() }
    val ok = remember { BooleanArray(2) } // [0] OK went down here, [1] the hold already fired

    val channel = channels.getOrNull(row)
    val key = remember(channel?.id, version) { channel?.let { guide.keyFor(it) } }
    // Programmes for the selected channel, wider than the screen so Left/Right know where to go.
    val around by produceState(emptyList<Programme>(), key, windowStart, version) {
        value = if (key == null) emptyList() else withContext(Dispatchers.IO) { guide.range(key, windowStart - 6 * 60 * MIN, windowEnd + 6 * 60 * MIN) }
    }
    val selected = around.firstOrNull { cursor >= it.start && cursor < it.stop }

    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
    LaunchedEffect(note) { if (note != null) { delay(3_500); note = null } }

    fun moveTo(time: Long) {
        val t = time.coerceIn(now - EpgStore.MAX_PAST_MS + 86_400_000, now + 36 * 60 * MIN)
        cursor = t
        if (t < windowStart) windowStart = (t / HALF_HOUR) * HALF_HOUR
        else if (t >= windowEnd - HALF_HOUR) windowStart = (t / HALF_HOUR) * HALF_HOUR - 2 * HALF_HOUR
    }

    fun watch(fromStart: Boolean) {
        val ch = channel ?: return
        val prog = selected
        val list = channels
        when {
            prog == null || (prog.start <= now && now < prog.stop && !fromStart) -> { session.tune(ch, list); session.panel = Panel.NONE }
            prog.start > now -> note = "Starts ${dayText(prog.start).lowercase()} at ${clockText(prog.start, config.use24h)}"
            !session.playArchive(ch, prog) -> note = if (ch.hasCatchup) "Too old: this channel keeps ${ch.catchupDays} day${if (ch.catchupDays == 1) "" else "s"} of catch-up" else "This channel has no catch-up"
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(p.background.copy(alpha = 0.95f))
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                val down = ev.type == KeyEventType.KeyDown
                val native = ev.nativeKeyEvent
                when (native.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_UP -> { if (down && row > 0) row--; true }
                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> { if (down && row < channels.lastIndex) row++; true }
                    NativeKeyEvent.KEYCODE_CHANNEL_DOWN, NativeKeyEvent.KEYCODE_PAGE_UP -> { if (down) row = (row - GUIDE_ROWS).coerceAtLeast(0); true }
                    NativeKeyEvent.KEYCODE_CHANNEL_UP, NativeKeyEvent.KEYCODE_PAGE_DOWN -> { if (down) row = (row + GUIDE_ROWS).coerceAtMost(channels.lastIndex.coerceAtLeast(0)); true }
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (down) {
                            val before = around.lastOrNull { it.start < (selected?.start ?: cursor) && it.stop <= (selected?.start ?: cursor) }
                            moveTo(before?.start ?: ((selected?.start ?: cursor) - HALF_HOUR))
                        }
                        true
                    }
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (down) {
                            val edge = selected?.stop ?: cursor
                            val after = around.firstOrNull { it.start >= edge } ?: around.firstOrNull { it.start > cursor }
                            moveTo(after?.start ?: (edge.coerceAtLeast(cursor) + if (selected == null) HALF_HOUR else 0))
                        }
                        true
                    }
                    NativeKeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) moveTo(cursor - 24 * 60 * MIN); true }
                    NativeKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) moveTo(cursor + 24 * 60 * MIN); true }
                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (down) {
                            if (native.repeatCount == 0) { ok[0] = true; ok[1] = false }
                            else if (ok[0] && !ok[1]) { ok[1] = true; watch(fromStart = true) }
                        } else {
                            if (ok[0] && !ok[1]) watch(fromStart = false)
                            ok[0] = false
                        }
                        true
                    }
                    NativeKeyEvent.KEYCODE_GUIDE -> { if (!down) session.panel = Panel.NONE; true }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 26.dp, bottom = 16.dp)) {
            // ---- what the cursor is on
            Row(Modifier.fillMaxWidth().height(132.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("TV GUIDE  ·  ${dayText(cursor).uppercase()}", style = AkoyaType.section, color = p.accent)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        selected?.title?.ifBlank { null } ?: if (key == null) "No guide for this channel" else "No programme information",
                        style = AkoyaType.title, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val line = buildString {
                        selected?.let { append(clockText(it.start, config.use24h)); append(" – "); append(clockText(it.stop, config.use24h)); append("   ·   ") }
                        append(channel?.name.orEmpty())
                        selected?.let { s ->
                            when {
                                s.start <= now && now < s.stop -> append("   ·   ON NOW")
                                s.stop <= now && channel != null && Catchup.covers(channel, s.start, now) -> append("   ·   CATCH-UP")
                            }
                        }
                    }
                    Text(line, style = AkoyaType.label, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        note ?: selected?.description.orEmpty(),
                        style = AkoyaType.body, color = if (note != null) p.accent else p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(clockText(now, config.use24h), style = AkoyaType.title, color = p.text)
            }

            // ---- time ruler
            Row(Modifier.height(22.dp)) {
                Spacer(Modifier.width(CHANNEL_COL))
                Box(Modifier.width(TIMELINE)) {
                    var t = windowStart
                    while (t < windowEnd) {
                        Text(
                            clockText(t, config.use24h), style = AkoyaType.small, color = p.muted,
                            modifier = Modifier.offset(x = TIMELINE * ((t - windowStart).toFloat() / (WINDOW_MIN * MIN))),
                        )
                        t += HALF_HOUR
                    }
                }
            }

            // ---- a screenful of channels around the cursor
            val first = (row - GUIDE_ROWS / 2).coerceIn(0, (channels.size - GUIDE_ROWS).coerceAtLeast(0))
            Box {
                Column {
                    for (i in first until minOf(first + GUIDE_ROWS, channels.size)) {
                        val ch = channels[i]
                        GuideRow(
                            ch = ch,
                            number = index.numberOf[ch.id] ?: 0,
                            guide = guide,
                            version = version,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            now = now,
                            cursor = if (i == row) cursor else -1L,
                            playing = ch.id == session.current?.id,
                        )
                    }
                }
                // "Now" line
                if (now in windowStart until windowEnd) {
                    Box(
                        Modifier
                            .offset(x = CHANNEL_COL + TIMELINE * ((now - windowStart).toFloat() / (WINDOW_MIN * MIN)))
                            .width(2.dp)
                            .height((ROW_HEIGHT + 4.dp) * minOf(GUIDE_ROWS, channels.size))
                            .background(p.accent),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            val canRestart = selected != null && channel != null && selected.start <= now && now < selected.stop && Catchup.covers(channel, selected.start, now)
            Text(
                "OK  watch   ·   ◀ ▶  earlier / later   ·   CH+ CH−  page" + (if (canRestart) "   ·   Hold OK  from the start" else "") + "   ·   Back  close",
                style = AkoyaType.small, color = p.muted,
            )
        }
    }
}

@Composable
private fun GuideRow(
    ch: Channel,
    number: Int,
    guide: EpgStore,
    version: Int,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    /** The cursor time when this is the selected row, otherwise -1. */
    cursor: Long,
    playing: Boolean,
) {
    val p = LocalPalette.current
    val isRow = cursor >= 0
    val key = remember(ch.id, version) { guide.keyFor(ch) }
    val programmes by produceState(emptyList<Programme>(), key, windowStart, version) {
        if (key == null) { value = emptyList(); return@produceState }
        if (ScrollPace.fast) delay(200)
        value = withContext(Dispatchers.IO) { guide.range(key, windowStart, windowEnd) }
    }
    val hit = if (isRow) programmes.firstOrNull { cursor >= it.start && cursor < it.stop } else null
    val shape = RoundedCornerShape(8.dp)

    Row(Modifier.padding(bottom = 4.dp).height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .width(CHANNEL_COL - 6.dp)
                .fillMaxHeight()
                .clip(shape)
                .background(if (isRow && hit == null) p.text else if (isRow) p.text.copy(alpha = 0.14f) else Color.Transparent)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = if (isRow && hit == null) p.background else p.text
            Text(number.toString(), style = AkoyaType.small, color = if (isRow && hit == null) p.background else p.muted, modifier = Modifier.width(34.dp), maxLines = 1)
            ChannelLogo(ch, Modifier.size(width = 38.dp, height = 24.dp))
            Spacer(Modifier.width(8.dp))
            Text(ch.name, style = AkoyaType.label, color = if (playing && !(isRow && hit == null)) p.accent else fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.width(TIMELINE).fillMaxHeight()) {
            if (programmes.isEmpty()) {
                Box(Modifier.fillMaxSize().clip(shape).background(p.surface), contentAlignment = Alignment.CenterStart) {
                    Text(if (key == null) "No guide" else "No information", style = AkoyaType.small, color = p.muted, modifier = Modifier.padding(horizontal = 10.dp))
                }
            }
            val span = (windowEnd - windowStart).toFloat()
            for (prog in programmes) {
                val from = maxOf(prog.start, windowStart)
                val to = minOf(prog.stop, windowEnd)
                val width: Dp = TIMELINE * ((to - from) / span) - 3.dp
                if (width < 3.dp) continue
                val on = prog === hit
                val live = prog.start <= now && now < prog.stop
                Box(
                    Modifier
                        .offset(x = TIMELINE * ((from - windowStart) / span))
                        .width(width)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(if (on) p.text else if (live) p.text.copy(alpha = 0.16f) else p.surface),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (width > 26.dp) {
                        Text(
                            prog.title,
                            style = AkoyaType.label,
                            color = if (on) p.background else if (prog.stop <= now) p.muted else p.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 9.dp),
                        )
                    }
                }
            }
        }
    }
}
