package com.akoya.home.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.akoya.home.ui.components.SectionHeader
import com.akoya.home.ui.components.TvCard
import com.akoya.home.ui.components.TvLazyRow
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette

private const val ROW_LIMIT = 30

/** What the launcher needs to draw Live TV rows: favourites, recent state and counts. */
private class LiveSummary(val favorites: List<Channel>, val last: Channel?, val channelCount: Int, val playlistCount: Int)

@Composable
private fun rememberLiveSummary(): LiveSummary {
    val store = LiveTvStore.get(LocalContext.current)
    val index by store.index.collectAsState()
    val state by store.state.collectAsState()
    return remember(index, state.favorites, state.lastChannelId, state.playlists.size) {
        LiveSummary(
            favorites = state.favorites.mapNotNull { index.byId[it] },
            last = state.lastChannelId?.let { index.byId[it] },
            channelCount = index.all.size,
            playlistCount = state.playlists.size,
        )
    }
}

/** The "Live TV" tab of the launcher. */
@Composable
fun LiveTvTab() {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val s = rememberLiveSummary()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp)) {
        item {
            SectionHeader("Live TV")
            TvLazyRow {
                liveActions(ctx, s, 190.dp, null)
            }
        }
        item {
            SectionHeader("Favourite channels")
            if (s.favorites.isEmpty()) {
                Text(
                    if (s.playlistCount == 0) "Add a playlist to get started: free Indian and US channels are one press away."
                    else "No favourites yet. In the channel list, hold OK on a channel to add it here.",
                    style = AkoyaType.body, color = p.muted,
                    modifier = Modifier.padding(start = 56.dp, top = 8.dp, end = 56.dp),
                )
            } else {
                TvLazyRow {
                    itemsIndexed(s.favorites.take(ROW_LIMIT), key = { _, c -> c.id }) { _, ch ->
                        ChannelCard(ch, 190.dp, onClick = { LiveTvSession.launch(ctx, channelId = ch.id) })
                    }
                }
            }
        }
    }
}

/** The optional "Live TV" row on the Home screen: favourites first, then the shortcuts. */
@Composable
fun LiveTvHomeSection(title: String, scale: Float, initialFocus: FocusRequester?) {
    val ctx = LocalContext.current
    val s = rememberLiveSummary()
    val width = 176.dp * scale
    val favs = s.favorites.take(ROW_LIMIT)
    Column {
        SectionHeader(title)
        TvLazyRow {
            itemsIndexed(favs, key = { _, c -> c.id }) { i, ch ->
                ChannelCard(ch, width, onClick = { LiveTvSession.launch(ctx, channelId = ch.id) }, focusRequester = if (i == 0) initialFocus else null)
            }
            liveActions(ctx, s, width, if (favs.isEmpty()) initialFocus else null)
        }
    }
}

private fun LazyListScope.liveActions(ctx: android.content.Context, s: LiveSummary, width: Dp, firstFocus: FocusRequester?) {
    if (s.channelCount > 0) {
        item(key = "__watch") {
            ActionCard(if (s.last != null) "Resume ${s.last.name}" else "Watch live TV", Icons.Filled.LiveTv, width, firstFocus) { LiveTvSession.launch(ctx) }
        }
        item(key = "__guide") {
            ActionCard("TV guide", Icons.Filled.CalendarMonth, width, null) { LiveTvSession.launch(ctx, open = LiveTvSession.OPEN_GUIDE) }
        }
        item(key = "__list") {
            ActionCard("All channels", Icons.Filled.FormatListBulleted, width, null) { LiveTvSession.launch(ctx, open = LiveTvSession.OPEN_CHANNELS) }
        }
    }
    item(key = "__playlists") {
        ActionCard(if (s.playlistCount == 0) "Add a playlist" else "Playlists", Icons.Filled.PlaylistAdd, width, if (s.channelCount > 0) null else firstFocus) {
            LiveTvSession.launch(ctx, open = LiveTvSession.OPEN_PLAYLISTS)
        }
    }
}

@Composable
private fun ActionCard(label: String, icon: ImageVector, width: Dp, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val p = LocalPalette.current
    TvCard(width = width, height = width * 9f / 16f, onClick = onClick, focusRequester = focusRequester) { focused ->
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(p.accent.copy(alpha = if (focused) 0.30f else 0.12f), Color.Transparent))))
        Column(Modifier.align(Alignment.Center).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = p.accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = AkoyaType.label, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A 16:9 tile for one channel: its logo, with the name underneath. */
@Composable
fun ChannelCard(ch: Channel, width: Dp, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    val p = LocalPalette.current
    TvCard(width = width, height = width * 9f / 16f, onClick = onClick, focusRequester = focusRequester) {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ch.logo.isNotBlank()) {
                AsyncImage(
                    model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp, vertical = 4.dp),
                )
            } else {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.LiveTv, contentDescription = null, tint = p.accent, modifier = Modifier.size(28.dp))
                }
            }
            Text(ch.name, style = AkoyaType.small, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
