package com.akoya.home.livetv

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.akoya.home.data.ConfigStore
import com.akoya.home.ui.components.DialogOption
import com.akoya.home.ui.components.ListScrollSpec
import com.akoya.home.ui.components.OptionsDialog
import com.akoya.home.ui.components.formatAmPm
import com.akoya.home.ui.components.formatTime
import com.akoya.home.ui.components.rememberNow
import com.akoya.home.ui.components.tvClickable
import com.akoya.home.ui.theme.AkoyaTheme
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalConfig
import com.akoya.home.ui.theme.LocalPalette
import kotlinx.coroutines.delay

private val OnVideo = Color(0xFFF1EEE8)
private val OnVideoMuted = Color(0xFFA39D91)

@Composable
fun LiveTvRoot(session: LiveTvSession, configStore: ConfigStore, onExit: () -> Unit) {
    val config by configStore.config.collectAsState()
    val store = session.store
    val channels by store.channels.collectAsState()
    val state by store.state.collectAsState()
    val ready by store.ready.collectAsState()

    val index = remember(channels, state.playlists) { ChannelIndex(channels, state.playlists) }
    val favorites = remember(index, state.favorites) { store.favoriteChannels(index.all, state.favorites) }
    LaunchedEffect(index, favorites, ready) { session.sync(index, favorites, ready) }

    AkoyaTheme(config) {
        BackHandler(enabled = session.panel != Panel.NONE) {
            if (session.current == null) onExit() else session.panel = Panel.NONE
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(session.player)
            StatusOverlay(session)
            ChannelBanner(session, index, state.playlists)

            when (session.panel) {
                Panel.CHANNELS -> ChannelPanel(session, index, favorites, state.favorites)
                Panel.PLAYLISTS -> PlaylistsScreen(session, config, configStore, hasChannels = index.all.isNotEmpty(), onExit = onExit)
                Panel.NONE -> Unit
            }

            session.optionsFor?.let { ch -> ChannelOptions(session, ch, state.favorites) }
        }
    }
}

@Composable
private fun VideoSurface(player: LivePlayer) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.aspectRatio(player.aspect.coerceIn(0.5f, 3f)),
            factory = { ctx -> SurfaceView(ctx).also { player.exo.setVideoSurfaceView(it) } },
            onRelease = { player.exo.clearVideoSurfaceView(it) },
        )
    }
}

@Composable
private fun StatusOverlay(session: LiveTvSession) {
    val p = LocalPalette.current
    val status = session.player.status
    if (session.panel == Panel.PLAYLISTS) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (status) {
            PlayStatus.Loading -> CircularProgressIndicator(color = p.accent, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
            is PlayStatus.Failed -> Column(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 30.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(status.message, style = AkoyaType.title, color = OnVideo)
                Spacer(Modifier.height(6.dp))
                Text("Up or Down for another channel  ·  OK for the channel list", style = AkoyaType.body, color = OnVideoMuted)
            }
            else -> Unit
        }
    }
}

/** Channel name strip along the bottom: pops up on every channel change, then fades. */
@Composable
private fun ChannelBanner(session: LiveTvSession, index: ChannelIndex, playlists: List<Playlist>) {
    val ch = session.current ?: return
    val p = LocalPalette.current
    val config = LocalConfig.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(session.bannerTick) {
        if (session.bannerTick == 0) return@LaunchedEffect
        visible = true
        delay(5_000)
        visible = false
    }
    val show = session.panel == Panel.NONE && (visible || session.player.status is PlayStatus.Failed)
    AnimatedVisibility(visible = show, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Black.copy(alpha = 0.82f), 1f to Color.Black.copy(alpha = 0.92f)))
                    .padding(start = 56.dp, end = 56.dp, top = 70.dp, bottom = 34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text((index.numberOf[ch.id] ?: 0).toString(), style = AkoyaType.display.copy(fontSize = AkoyaType.display.fontSize * 0.5f, lineHeight = AkoyaType.display.lineHeight * 0.5f), color = p.accent.onVideo())
                Spacer(Modifier.width(22.dp))
                ChannelLogo(ch, Modifier.size(width = 84.dp, height = 52.dp))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(ch.name, style = AkoyaType.title, color = OnVideo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val source = playlists.firstOrNull { it.id == ch.playlistId }?.name.orEmpty()
                    val line = listOf(ch.group, source).filter { it.isNotBlank() }.joinToString("  ·  ")
                    if (line.isNotEmpty()) Text(line.uppercase(), style = AkoyaType.section, color = OnVideoMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (ch.id in session.store.current.favorites) {
                    Icon(Icons.Filled.Star, contentDescription = "Favourite", tint = p.accent.onVideo(), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                }
                val q = session.player.quality
                if (q.isNotEmpty() && session.player.status == PlayStatus.Playing) {
                    Text(q, style = AkoyaType.label, color = OnVideo, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp))
                    Spacer(Modifier.width(18.dp))
                }
                val now by rememberNow(withSeconds = false)
                Text(formatTime(now, config.use24h, false), style = AkoyaType.title, color = OnVideo)
                val ampm = formatAmPm(now, config.use24h)
                if (ampm.isNotEmpty()) Text(ampm, style = AkoyaType.small, color = OnVideoMuted, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            }
        }
    }
}

/** Dark themes have a light accent already; light themes need a lighter one over the picture. */
private fun Color.onVideo(): Color = if ((red + green + blue) / 3f < 0.5f) Color(0xFFC8B79D) else this

@Composable
fun ChannelLogo(ch: Channel, modifier: Modifier = Modifier) {
    if (ch.logo.isBlank()) {
        Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Text(ch.name.take(1).uppercase(), style = AkoyaType.label, color = OnVideoMuted)
        }
    } else {
        AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
    }
}

// ------------------------------------------------------------------------------------------ channel list

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ChannelPanel(session: LiveTvSession, index: ChannelIndex, favorites: List<Channel>, favoriteIds: List<String>) {
    val p = LocalPalette.current
    val list = remember(session.groupKey, index, favorites) { index.channels(session.groupKey, favorites) }
    val favSet = remember(favoriteIds) { favoriteIds.toHashSet() }
    val listState = rememberLazyListState()
    val groupState = rememberLazyListState()
    val currentRow = remember { FocusRequester() }
    val currentGroup = remember { FocusRequester() }
    var opened by remember { mutableStateOf(false) }

    val groupRows = remember(index, favorites.size) {
        listOf(
            GroupEntry(GROUP_FAV, "Favourites", favorites.size),
            GroupEntry(GROUP_ALL, "All channels", index.all.size),
        ) + index.groups
    }

    // On opening, land on the channel that's playing. After that, a new group starts at its top.
    LaunchedEffect(session.groupKey) {
        if (opened) { listState.scrollToItem(0); return@LaunchedEffect }
        opened = true
        val gi = groupRows.indexOfFirst { it.key == session.groupKey }
        if (gi > 4) groupState.scrollToItem(gi - 3)
        val ci = list.indexOfFirst { it.id == session.current?.id }
        if (ci >= 0) listState.scrollToItem((ci - 3).coerceAtLeast(0))
        delay(90)
        val ok = ci >= 0 && runCatching { currentRow.requestFocus() }.isSuccess
        if (!ok) runCatching { currentGroup.requestFocus() }
    }

    Row(
        Modifier
            .fillMaxHeight()
            .width(760.dp)
            .background(Brush.horizontalGradient(0f to p.background.copy(alpha = 0.97f), 0.8f to p.background.copy(alpha = 0.93f), 1f to Color.Transparent))
            .padding(start = 40.dp, top = 30.dp, bottom = 24.dp),
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides ListScrollSpec) {
            Column(Modifier.width(236.dp)) {
                Text("LIVE TV", style = AkoyaType.section, color = p.accent, modifier = Modifier.padding(start = 14.dp, bottom = 12.dp))
                LazyColumn(state = groupState, modifier = Modifier.weight(1f).focusRestorer { currentGroup }.focusGroup(), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    itemsIndexed(groupRows, key = { _, g -> g.key }) { _, g ->
                        val selected = g.key == session.groupKey
                        PanelRow(
                            selected = selected,
                            onClick = { session.groupKey = g.key },
                            onFocus = { session.groupKey = g.key },
                            modifier = if (selected) Modifier.focusRequester(currentGroup) else Modifier,
                        ) { fg, sub ->
                            if (g.key == GROUP_FAV) {
                                Icon(Icons.Filled.Star, null, tint = fg, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                            } else if (!g.isPlaylist && index.groups.any { it.isPlaylist }) {
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                if (g.isPlaylist) g.label.uppercase() else g.label,
                                style = if (g.isPlaylist) AkoyaType.section.copy(letterSpacing = AkoyaType.section.letterSpacing * 0.5f) else AkoyaType.body,
                                color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text(g.count.toString(), style = AkoyaType.small, color = sub)
                        }
                    }
                }
                PanelRow(selected = false, onClick = { session.panel = Panel.PLAYLISTS }) { fg, _ ->
                    Icon(Icons.Filled.PlaylistAdd, null, tint = fg, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Playlists", style = AkoyaType.body, color = fg)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.width(400.dp)) {
                Text(
                    "${index.label(session.groupKey).uppercase()}  ·  ${list.size}",
                    style = AkoyaType.section, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp, bottom = 12.dp),
                )
                if (list.isEmpty()) {
                    Text(
                        if (session.groupKey == GROUP_FAV) "No favourites yet.\nHold OK on any channel to add it here." else "No channels here.",
                        style = AkoyaType.body, color = p.muted, modifier = Modifier.padding(14.dp),
                    )
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    itemsIndexed(list, key = { _, c -> c.id }) { _, ch ->
                        val playing = ch.id == session.current?.id
                        PanelRow(
                            selected = playing,
                            onClick = { session.tune(ch, list); session.panel = Panel.NONE },
                            onLongClick = { session.optionsFor = ch },
                            modifier = if (playing) Modifier.focusRequester(currentRow) else Modifier,
                        ) { fg, sub ->
                            Text((index.numberOf[ch.id] ?: 0).toString(), style = AkoyaType.small, color = sub, modifier = Modifier.width(40.dp), maxLines = 1)
                            ChannelLogo(ch, Modifier.size(width = 46.dp, height = 28.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(ch.name, style = AkoyaType.body, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (ch.id in favSet) Icon(Icons.Filled.Star, "Favourite", tint = sub, modifier = Modifier.padding(start = 6.dp).size(15.dp))
                            if (playing) Icon(Icons.Filled.GraphicEq, "Playing", tint = fg, modifier = Modifier.padding(start = 6.dp).size(17.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A list row in the launcher's pill style: inverts when focused, tinted when selected. */
@Composable
private fun PanelRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.(fg: Color, sub: Color) -> Unit,
) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> p.text
        selected -> p.text.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val fg = if (focused) p.background else p.text
    val sub = if (focused) p.background.copy(alpha = 0.7f) else p.muted
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .tvClickable(onClick, onLongClick) { focused = it; if (it) onFocus?.invoke() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(fg, sub) }
}

@Composable
private fun ChannelOptions(session: LiveTvSession, ch: Channel, favoriteIds: List<String>) {
    val fav = ch.id in favoriteIds
    OptionsDialog(
        title = ch.name,
        subtitle = ch.group.ifBlank { null },
        onDismiss = { session.optionsFor = null },
        options = buildList {
            add(DialogOption(if (fav) "Remove from favourites" else "Add to favourites", if (fav) Icons.Filled.StarBorder else Icons.Filled.Star) { session.store.toggleFavorite(ch.id) })
            if (fav && favoriteIds.size > 1) {
                add(DialogOption("Move up in favourites", Icons.Filled.ArrowUpward, keepOpen = true) { session.store.moveFavorite(ch.id, -1) })
                add(DialogOption("Move down in favourites", Icons.Filled.ArrowDownward, keepOpen = true) { session.store.moveFavorite(ch.id, +1) })
            }
            if (session.current?.id != ch.id) {
                add(DialogOption("Watch", Icons.Filled.GraphicEq) { session.tune(ch, session.index.channels(session.groupKey, session.favorites)); session.panel = Panel.NONE })
            }
        },
    )
}
