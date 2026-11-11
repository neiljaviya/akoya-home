package com.akoya.home.vod

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.akoya.home.data.ConfigStore
import com.akoya.home.livetv.Field
import com.akoya.home.livetv.FieldsDialog
import com.akoya.home.ui.components.DialogOption
import com.akoya.home.ui.components.NoScrollSpec
import com.akoya.home.ui.components.OptionsDialog
import com.akoya.home.ui.components.ScrollPace
import com.akoya.home.ui.components.SectionHeader
import com.akoya.home.ui.components.TvButton
import com.akoya.home.ui.components.TvCard
import com.akoya.home.ui.components.TvLazyRow
import com.akoya.home.ui.settings.SettingRow
import com.akoya.home.ui.theme.AkoyaTheme
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette
import kotlinx.coroutines.delay

private val POSTER_W = 116.dp
private const val ROW_PREVIEW = 30
private val OnVideo = Color(0xFFF1EEE8)
private val OnVideoMuted = Color(0xFFA39D91)

@Composable
fun VodRoot(session: VodSession, configStore: ConfigStore, onExit: () -> Unit) {
    val config by configStore.config.collectAsState()
    AkoyaTheme(config) {
        val p = LocalPalette.current
        BackHandler { if (!session.back()) onExit() }
        Box(Modifier.fillMaxSize().background(p.background)) {
            if (session.player.now != null) {
                PlayerScreen(session)
            } else {
                when (val s = session.screen) {
                    VodScreen.Browse -> BrowseScreen(session)
                    is VodScreen.Grid -> GridScreen(session, s)
                    is VodScreen.Detail -> DetailScreen(session, s.item)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ browse

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseScreen(session: VodSession) {
    val p = LocalPalette.current
    val store = session.store
    val kind = session.kind
    var reload by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    val result by produceState<Result<VodCatalog>?>(null, kind, reload) {
        value = null
        value = store.catalog(kind, force = reload > 0)
    }
    val resumes by store.resumes.collectAsState()
    val continuing = remember(resumes, kind) { resumes.filter { it.series == (kind == VodKind.SERIES) } }
    var focused by remember(kind) { mutableStateOf<VodItem?>(null) }
    var focusedRow by remember(kind) { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val firstTab = remember { FocusRequester() }
    val catalog = result?.getOrNull()

    LaunchedEffect(Unit) { delay(120); runCatching { firstTab.requestFocus() } }
    LaunchedEffect(focusedRow) {
        if (focusedRow < 0) return@LaunchedEffect
        if (ScrollPace.fast) listState.scrollToItem(focusedRow) else listState.animateScrollToItem(focusedRow)
    }

    Column(Modifier.fillMaxSize()) {
        // ---- what's in focus, over a faded copy of its poster
        Box(Modifier.fillMaxWidth().height(206.dp)) {
            focused?.poster?.takeIf { it.isNotBlank() }?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.22f))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to p.background)))
            Column(Modifier.padding(start = 56.dp, end = 56.dp, top = 26.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    VodKind.entries.forEachIndexed { i, k ->
                        TvButton(k.label, selected = kind == k, focusRequester = if (i == 0) firstTab else null, onClick = { session.kind = k })
                    }
                    TvButton("Search", icon = Icons.Filled.Search, onClick = { searching = true })
                    TvButton("Update", icon = Icons.Filled.Refresh, onClick = { reload++ })
                }
                Spacer(Modifier.height(14.dp))
                val f = focused
                if (f != null) {
                    Text(f.name, style = AkoyaType.title.copy(fontSize = AkoyaType.title.fontSize * 1.25f), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(f.year.ifBlank { null }, f.rating.ifBlank { null }?.let { "★ $it" }).joinToString("   ·   ")
                    if (meta.isNotEmpty()) Text(meta, style = AkoyaType.label, color = p.accent)
                    if (f.plot.isNotBlank()) Text(f.plot, style = AkoyaType.body, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.7f))
                } else {
                    Text(kind.label, style = AkoyaType.title.copy(fontSize = AkoyaType.title.fontSize * 1.25f), color = p.text)
                    catalog?.let { Text("${it.items.size} titles", style = AkoyaType.label, color = p.muted) }
                }
            }
        }

        when {
            result == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = p.accent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(14.dp))
                Text("Loading the ${kind.label.lowercase()} library. The first time can take a minute.", style = AkoyaType.body, color = p.muted)
            }
            catalog == null -> Text(
                result?.exceptionOrNull()?.message ?: "The library couldn't be loaded.",
                style = AkoyaType.body, color = p.muted, modifier = Modifier.padding(horizontal = 56.dp, vertical = 20.dp),
            )
            else -> CompositionLocalProvider(LocalBringIntoViewSpec provides NoScrollSpec) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 260.dp)) {
                    val offset = if (continuing.isEmpty()) 0 else 1
                    if (continuing.isNotEmpty()) item(key = "__continue") {
                        Column(Modifier.onFocusChanged { if (it.hasFocus) { focusedRow = 0; focused = null } }) {
                            SectionHeader("Continue watching")
                            TvLazyRow {
                                items(continuing, key = { it.key }) { r ->
                                    PosterCard(r.poster, r.title, POSTER_W, progress = r.fraction, caption = r.subtitle.ifBlank { r.title }) {
                                        session.player.play(listOf(Playable(r.key, r.title, r.subtitle, r.poster, r.url, r.series)), 0)
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(catalog.rows, key = { _, r -> r.key }) { i, row ->
                        Column(Modifier.onFocusChanged { if (it.hasFocus) focusedRow = i + offset }) {
                            SectionHeader("${row.title}  ·  ${row.items.size}")
                            TvLazyRow {
                                items(row.items.take(ROW_PREVIEW), key = { it.key }) { item ->
                                    PosterCard(item.poster, item.name, POSTER_W, onFocus = { focused = item }) { session.open(VodScreen.Detail(item)) }
                                }
                                if (row.items.size > ROW_PREVIEW) item(key = "__all") {
                                    TvCard(width = POSTER_W, height = POSTER_W * 1.5f, onClick = { session.open(VodScreen.Grid(row.title, row.items)) }) {
                                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Filled.GridView, contentDescription = null, tint = p.accent, modifier = Modifier.size(26.dp))
                                            Spacer(Modifier.height(6.dp))
                                            Text("See all ${row.items.size}", style = AkoyaType.small, color = p.text)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (searching) {
        FieldsDialog(
            title = "Search ${kind.label.lowercase()}",
            subtitle = "Part of a title is enough.",
            fields = listOf(Field("Title")),
            onDismiss = { searching = false },
            onDone = { v ->
                val q = v[0].trim().lowercase()
                val all = catalog?.items
                if (q.length >= 2 && all != null) {
                    session.open(VodScreen.Grid("Results for \"${v[0].trim()}\"", all.filter { q in it.name.lowercase() }.take(300)))
                }
            },
        )
    }
}

/** A 2:3 poster tile. Falls back to the title when there's no artwork. */
@Composable
private fun PosterCard(
    poster: String,
    title: String,
    width: Dp,
    progress: Float? = null,
    caption: String? = null,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    // Posters for tiles that are only flying past aren't worth downloading.
    var load by remember(poster) { mutableStateOf(!ScrollPace.fast) }
    if (!load) LaunchedEffect(poster) { delay(260); load = true }
    Column(Modifier.width(width)) {
        TvCard(width = width, height = width * 1.5f, onClick = onClick, focusRequester = focusRequester, onFocus = { if (it) onFocus?.invoke() }, corner = 10.dp) {
            Text(title, style = AkoyaType.small, color = p.muted, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            if (poster.isNotBlank() && load) {
                AsyncImage(model = poster, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxWidth(progress).height(4.dp).background(p.accent))
                }
            }
        }
        if (caption != null) {
            Text(caption, style = AkoyaType.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------ grid

@Composable
private fun GridScreen(session: VodSession, screen: VodScreen.Grid) {
    val p = LocalPalette.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(top = 30.dp)) {
        Text(session.kind.label.uppercase(), style = AkoyaType.section, color = p.accent, modifier = Modifier.padding(start = 56.dp))
        Text("${screen.title}  ·  ${screen.items.size}", style = AkoyaType.title, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 56.dp, end = 56.dp, bottom = 8.dp))
        if (screen.items.isEmpty()) {
            Text("Nothing found.", style = AkoyaType.body, color = p.muted, modifier = Modifier.padding(start = 56.dp, top = 12.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 12.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            gridItemsIndexed(screen.items, key = { _, v -> v.key }) { i, item ->
                PosterCard(item.poster, item.name, POSTER_W, caption = item.name, focusRequester = if (i == 0) first else null) { session.open(VodScreen.Detail(item)) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ detail

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun DetailScreen(session: VodSession, item: VodItem) {
    val p = LocalPalette.current
    val store = session.store
    val resumes by store.resumes.collectAsState()
    val first = remember { FocusRequester() }
    val movie by produceState<MovieInfo?>(null, item.key) { if (!item.series) value = store.movieInfo(item) }
    var loaded by remember(item.key) { mutableStateOf(!item.series) }
    val series by produceState<SeriesInfo?>(null, item.key) { if (item.series) { value = store.seriesInfo(item); loaded = true } }
    var season by remember(item.key) { mutableIntStateOf(-1) }
    val seasons = series?.seasons.orEmpty()
    if (season !in seasons && seasons.isNotEmpty()) {
        // Open on the season that was being watched, else the first.
        season = seasons.entries.firstOrNull { (_, eps) -> eps.any { e -> resumes.any { it.key == item.key + ":" + e.id } } }?.key ?: seasons.keys.first()
    }
    val backdrop = movie?.backdrop?.ifBlank { null } ?: series?.backdrop?.ifBlank { null } ?: item.poster

    LaunchedEffect(loaded) { if (loaded) { delay(120); runCatching { first.requestFocus() } } }

    fun episodeList(): List<Playable> = seasons.entries.flatMap { (s, eps) ->
        eps.mapNotNull { e ->
            val url = store.episodeUrl(item, e) ?: return@mapNotNull null
            Playable(item.key + ":" + e.id, item.name, "S$s E${e.number}" + if (e.title.isNotBlank()) "  ·  ${e.title}" else "", item.poster, url, series = true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (backdrop.isNotBlank()) AsyncImage(model = backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.16f))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to p.background, 0.55f to p.background.copy(alpha = 0.75f), 1f to p.background.copy(alpha = 0.35f))))
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 40.dp)) {
            Column(Modifier.width(190.dp)) {
                Box(Modifier.width(190.dp).height(285.dp).clip(RoundedCornerShape(14.dp)).background(p.surface)) {
                    if (item.poster.isNotBlank()) AsyncImage(model = item.poster, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(34.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(item.name, style = AkoyaType.title.copy(fontSize = AkoyaType.title.fontSize * 1.3f), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    (movie?.year ?: series?.year ?: item.year).ifBlank { null },
                    (movie?.rating ?: series?.rating ?: item.rating).ifBlank { null }?.let { "★ $it" },
                    movie?.duration?.ifBlank { null },
                    (movie?.genre ?: series?.genre)?.ifBlank { null },
                    if (item.series && seasons.isNotEmpty()) "${seasons.size} season${if (seasons.size == 1) "" else "s"}" else null,
                ).joinToString("   ·   ")
                if (meta.isNotEmpty()) Text(meta, style = AkoyaType.label, color = p.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                val plot = movie?.plot?.ifBlank { null } ?: series?.plot?.ifBlank { null } ?: item.plot
                if (plot.isNotBlank()) Text(plot, style = AkoyaType.body, color = p.muted, maxLines = if (item.series) 3 else 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                val cast = (movie?.cast ?: series?.cast).orEmpty()
                if (cast.isNotBlank()) Text("With $cast", style = AkoyaType.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(16.dp))

                if (!item.series) {
                    val key = item.key
                    val resume = resumes.firstOrNull { it.key == key }
                    val play = { fromStart: Boolean ->
                        store.movieUrl(item, movie?.ext ?: item.ext)?.let { url ->
                            session.player.play(listOf(Playable(key, item.name, "", item.poster, url, series = false)), 0, fromStart)
                        }
                        Unit
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvButton(if (resume != null) "Resume from ${clock(resume.pos)}" else "Play", icon = Icons.Filled.PlayArrow, selected = true, focusRequester = first, onClick = { play(false) })
                        if (resume != null) TvButton("Start over", icon = Icons.Filled.Replay, onClick = { play(true) })
                    }
                } else if (!loaded) {
                    CircularProgressIndicator(color = p.accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                } else if (seasons.isEmpty()) {
                    Text("The provider has no episodes listed for this series.", style = AkoyaType.body, color = p.muted)
                } else {
                    TvLazyRow(contentPadding = PaddingValues(vertical = 4.dp), spacing = 6.dp) {
                        itemsIndexed(seasons.keys.toList(), key = { _, s -> s }) { i, s ->
                            TvButton("Season $s", selected = s == season, focusRequester = if (i == 0) first else null, onClick = { season = s }, onFocus = { if (it) season = s })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val episodes = seasons[season].orEmpty()
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 60.dp, start = 3.dp, end = 3.dp, top = 3.dp)) {
                        items(episodes, key = { it.id }) { e ->
                            val key = item.key + ":" + e.id
                            val resume = resumes.firstOrNull { it.key == key }
                            SettingRow(
                                title = "${e.number}.  " + e.title.ifBlank { "Episode ${e.number}" },
                                subtitle = listOfNotNull(e.durationSecs.takeIf { it > 0 }?.let { "${it / 60} min" }, e.plot.ifBlank { null }?.take(110)).joinToString("  ·  ").ifBlank { null },
                                value = if (resume != null) "Resume ${clock(resume.pos)}" else "Play",
                                onClick = {
                                    val list = episodeList()
                                    val at = list.indexOfFirst { it.key == key }
                                    if (at >= 0) session.player.play(list, at)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ player

@Composable
private fun PlayerScreen(session: VodSession) {
    val p = LocalPalette.current
    val player = session.player
    val item = player.now ?: return
    var visible by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }

    LaunchedEffect(player.controlsTick) { visible = true; delay(4_500); visible = false }
    LaunchedEffect(item.key) {
        var sinceSave = 0
        while (true) {
            pos = player.exo.currentPosition.coerceAtLeast(0)
            dur = player.exo.duration.coerceAtLeast(0)
            if (++sinceSave >= 20) { sinceSave = 0; player.save() }
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.aspectRatio(player.aspect.coerceIn(0.5f, 3f)),
            factory = { ctx -> SurfaceView(ctx).also { player.exo.setVideoSurfaceView(it) } },
            onRelease = { player.exo.clearVideoSurfaceView(it) },
        )
        val error = player.error
        if (error != null) {
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 30.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, style = AkoyaType.title, color = OnVideo)
                Text("Press Back to return to the library", style = AkoyaType.body, color = OnVideoMuted)
            }
        } else if (player.loading) {
            CircularProgressIndicator(color = p.accent, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
        }
    }

    val shown = player.seekPreview.takeIf { it >= 0 } ?: pos
    AnimatedVisibility(visible = visible || player.paused || player.seekPreview >= 0, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black.copy(alpha = 0.8f), 1f to Color.Black.copy(alpha = 0.92f)))
                    .padding(start = 56.dp, end = 56.dp, top = 70.dp, bottom = 30.dp),
            ) {
                Text(item.title, style = AkoyaType.title, color = OnVideo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.subtitle.isNotBlank()) Text(item.subtitle, style = AkoyaType.body, color = OnVideoMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(shown), style = AkoyaType.label, color = OnVideo)
                    Box(Modifier.padding(horizontal = 14.dp).weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f))) {
                        Box(Modifier.fillMaxWidth(if (dur > 0) (shown.toFloat() / dur).coerceIn(0f, 1f) else 0f).height(4.dp).background(Color(0xFFC8B79D)))
                    }
                    Text(if (dur > 0) clock(dur) else "", style = AkoyaType.label, color = OnVideoMuted)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (player.paused) "PAUSED   ·   " else "") + "OK  pause   ·   ◀ ▶  skip (hold for more)   ·   ▲  audio" + (if (player.hasNext) "   ·   next episode plays automatically" else "") + "   ·   Back  library",
                    style = AkoyaType.small, color = OnVideoMuted,
                )
            }
        }
    }

    if (session.audioOpen) {
        val tracks = remember(item.key, player.loading) { player.audioTracks() }
        OptionsDialog(
            title = "Audio",
            subtitle = if (tracks.size > 1) "Pick a language or sound track" else "This video has one sound track",
            onDismiss = { session.audioOpen = false },
            options = tracks.map { t -> DialogOption(t.label, if (t.selected) Icons.Filled.Check else null) { player.selectAudio(t) } }
                .ifEmpty { listOf(DialogOption("Close") {}) },
        )
    }
}
