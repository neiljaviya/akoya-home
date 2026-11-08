package com.akoya.home.ui.home

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.akoya.home.data.AppInfo
import com.akoya.home.data.LauncherConfig
import com.akoya.home.data.STREAMING_SERVICES
import com.akoya.home.data.TmdbRepository
import com.akoya.home.data.TmdbRow
import com.akoya.home.data.TmdbTitle
import com.akoya.home.ui.LauncherController
import com.akoya.home.ui.Tab
import com.akoya.home.ui.components.AppArtwork
import com.akoya.home.ui.components.SectionHeader
import com.akoya.home.ui.components.TvButton
import com.akoya.home.ui.components.TvCard
import com.akoya.home.ui.components.TvLazyRow
import com.akoya.home.ui.components.rememberInitialFocus
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette
import kotlinx.coroutines.launch

fun hasTmdb(config: LauncherConfig, pkg: String?) = config.tmdbKey.isNotBlank() && pkg != null && pkg in STREAMING_SERVICES

/** An app spotlight filled with what's trending on that service, from TMDB. */
@Composable
fun TmdbSection(
    controller: LauncherController,
    config: LauncherConfig,
    title: String,
    app: AppInfo,
    width: Dp,
    initialFocus: FocusRequester?,
) {
    val p = LocalPalette.current
    val service = STREAMING_SERVICES[app.packageName]
    val row by produceState<TmdbRow?>(null, app.packageName, config.tmdbKey, config.tmdbRegion) {
        value = TmdbRepository.instance.trending(app.packageName, config.tmdbKey, config.tmdbRegion)
    }
    var open by remember { mutableStateOf<TmdbTitle?>(null) }
    Column {
        SectionHeader(if (service?.trailersOnly == true) "$title · trending trailers" else "$title · trending")
        TvLazyRow {
            item(key = "__app") {
                TvCard(
                    width = width,
                    height = width * 9f / 16f,
                    onClick = { controller.launch(app) },
                    onLongClick = { controller.appOptionsFor = app },
                    focusRequester = initialFocus,
                ) { AppArtwork(app, width) }
            }
            val titles = row?.items.orEmpty()
            items(titles.size, key = { "t${titles[it].mediaType}${titles[it].id}" }) { i ->
                TmdbCard(titles[i], width) { open = titles[i] }
            }
            val err = row?.error
            if (err != null) {
                item(key = "__err") {
                    TvCard(width = width * 1.4f, height = width * 9f / 16f, onClick = { controller.tab = Tab.SETTINGS }) {
                        Text(err, style = AkoyaType.body, color = p.muted, modifier = Modifier.align(Alignment.CenterStart).padding(20.dp))
                    }
                }
            }
        }
    }
    open?.let { t -> TitleDialog(controller, config, t, app, onDismiss = { open = null }) }
}

@Composable
private fun TmdbCard(t: TmdbTitle, width: Dp, onClick: () -> Unit) {
    TvCard(width = width, height = width * 9f / 16f, onClick = onClick) { focused ->
        AsyncImage(model = t.backdrop, contentDescription = t.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(t.title, style = AkoyaType.label, color = Color.White, maxLines = if (focused) 2 else 1, overflow = TextOverflow.Ellipsis)
            if (focused) {
                Text(
                    listOfNotNull(t.year.ifBlank { null }, if (t.mediaType == "tv") "Series" else "Film", if (t.rating > 0) "★ %.1f".format(t.rating) else null).joinToString("  ·  "),
                    style = AkoyaType.small,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun TitleDialog(controller: LauncherController, config: LauncherConfig, t: TmdbTitle, app: AppInfo, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(24.dp)
        Box(
            Modifier
                .width(820.dp)
                .height(440.dp)
                .clip(shape)
                .background(p.background),
        ) {
            AsyncImage(model = t.backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to p.background, 0.55f to p.background.copy(alpha = 0.85f), 1f to Color.Transparent)))
            Column(
                Modifier
                    .fillMaxWidth(0.62f)
                    .padding(36.dp),
            ) {
                Text(STREAMING_SERVICES[app.packageName]?.label?.uppercase() ?: app.label.uppercase(), style = AkoyaType.section, color = p.accent)
                Spacer(Modifier.height(8.dp))
                Text(t.title, style = AkoyaType.title.copy(fontSize = AkoyaType.title.fontSize * 1.3f), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(t.year.ifBlank { null }, if (t.mediaType == "tv") "Series" else "Film", if (t.rating > 0) "★ %.1f".format(t.rating) else null).joinToString("  ·  "),
                    style = AkoyaType.label,
                    color = p.muted,
                )
                Spacer(Modifier.height(14.dp))
                Text(t.overview, style = AkoyaType.body, color = p.text.copy(alpha = 0.85f), maxLines = 6, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                val fr = rememberInitialFocus()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val trailersOnly = STREAMING_SERVICES[app.packageName]?.trailersOnly == true
                    TvButton(
                        text = if (trailersOnly) "Watch trailer" else "Open in ${app.label}",
                        icon = if (trailersOnly) Icons.Filled.PlayArrow else Icons.Filled.Search,
                        focusRequester = fr,
                        onClick = {
                            if (trailersOnly) {
                                scope.launch { playTrailer(controller, config, t) }
                            } else {
                                openInApp(controller, app, t.title)
                            }
                            onDismiss()
                        },
                    )
                    if (!(STREAMING_SERVICES[app.packageName]?.trailersOnly ?: false)) {
                        TvButton("Trailer", icon = Icons.Filled.PlayArrow, onClick = {
                            scope.launch { playTrailer(controller, config, t) }
                            onDismiss()
                        })
                    }
                    TvButton("Close", icon = Icons.Filled.Close, onClick = onDismiss)
                }
            }
        }
    }
}

private suspend fun playTrailer(controller: LauncherController, config: LauncherConfig, t: TmdbTitle) {
    val key = TmdbRepository.instance.trailer(t, config.tmdbKey)
    if (key == null) {
        controller.toast("No trailer found for ${t.title}")
        return
    }
    val yt = "com.google.android.youtube.tv"
    controller.apps.startFirst(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")).setPackage(yt),
        Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")),
    )
}

/** Opens the app's own search for the title when it supports it, otherwise just opens the app. */
private fun openInApp(controller: LauncherController, app: AppInfo, title: String) {
    val search = Intent(Intent.ACTION_SEARCH).setPackage(app.packageName).putExtra(SearchManager.QUERY, title)
    if (!controller.apps.start(search, quiet = true)) {
        controller.launch(app)
        controller.toast("Search for \"$title\" in ${app.label}")
    }
}
