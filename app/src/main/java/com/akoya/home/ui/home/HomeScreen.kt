package com.akoya.home.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import com.akoya.home.ui.components.Emphasized
import com.akoya.home.ui.components.HomeScrollSpec
import com.akoya.home.ui.components.TvLazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akoya.home.data.AppInfo
import com.akoya.home.data.ContentResult
import com.akoya.home.data.InputSource
import com.akoya.home.data.LauncherConfig
import com.akoya.home.data.SectionConfig
import com.akoya.home.data.SectionType
import com.akoya.home.ui.LauncherController
import com.akoya.home.ui.Tab
import com.akoya.home.ui.components.AppArtwork
import com.akoya.home.ui.components.AppPickerDialog
import com.akoya.home.ui.components.AppTile
import com.akoya.home.ui.components.ContentCard
import com.akoya.home.ui.components.SectionHeader
import com.akoya.home.ui.components.TvCard
import com.akoya.home.ui.components.formatAmPm
import com.akoya.home.ui.components.formatDate
import com.akoya.home.ui.components.formatTime
import com.akoya.home.ui.components.greetingFor
import com.akoya.home.ui.components.rememberNow
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette
import kotlinx.coroutines.delay

val APP_TILE_WIDTH = 176.dp
val CONTENT_WIDTH = 248.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(controller: LauncherController, config: LauncherConfig, apps: List<AppInfo>) {
    val sections = config.sections.filter { it.visible }
    // A clock section at the very top sits above the list and folds away as you scroll down.
    val heroOnTop = sections.firstOrNull()?.type == SectionType.HERO
    val listSections = if (heroOnTop) sections.drop(1) else sections
    val listState = rememberLazyListState()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    val firstInteractive = listSections.indexOfFirst { it.type != SectionType.HERO }
    val firstCard = remember { FocusRequester() }
    val heroVisible = focusedIndex <= firstInteractive.coerceAtLeast(0)

    LaunchedEffect(controller.homeReset, apps.isNotEmpty()) {
        if (apps.isNotEmpty() && controller.handledHomeReset != controller.homeReset) {
            controller.handledHomeReset = controller.homeReset
            listState.animateScrollToItem(0)
            delay(150)
            runCatching { firstCard.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (heroOnTop) {
            AnimatedVisibility(
                visible = heroVisible,
                enter = expandVertically(tween(480, easing = Emphasized)) + fadeIn(tween(480)),
                exit = shrinkVertically(tween(480, easing = Emphasized)) + fadeOut(tween(260)),
            ) { HeroSection(config) }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides HomeScrollSpec) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { if (!it.hasFocus) focusedIndex = -1 },
                contentPadding = PaddingValues(bottom = 200.dp),
            ) {
                itemsIndexed(listSections, key = { _, s -> s.id }) { index, section ->
                    Box(Modifier.onFocusChanged { if (it.hasFocus) focusedIndex = index }) {
                        SectionView(
                            controller = controller,
                            config = config,
                            apps = apps,
                            section = section,
                            initialFocus = if (index == firstInteractive) firstCard else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionView(
    controller: LauncherController,
    config: LauncherConfig,
    apps: List<AppInfo>,
    section: SectionConfig,
    initialFocus: FocusRequester?,
) {
    val visibleApps = apps.filter { it.packageName !in config.hidden }
    val scale = section.size.scale
    when (section.type) {
        SectionType.HERO -> HeroSection(config)
        SectionType.QUICK_APPS -> QuickAppsSection(controller, config, visibleApps, section, initialFocus)
        SectionType.CONTINUE_WATCHING -> {
            val result by produceState<ContentResult?>(null, controller.refreshTick) {
                value = controller.content.watchNext()
            }
            ContentSection(controller, section.title, result, CONTENT_WIDTH * scale, initialFocus, app = null)
        }
        SectionType.APP_CONTENT -> {
            val pkg = section.packageName
            val app = visibleApps.firstOrNull { it.packageName == pkg }
            if (pkg == null) return
            if (app == null) {
                AppMissingRow(controller, section.title, pkg, initialFocus)
            } else {
                val result by produceState<ContentResult?>(null, controller.refreshTick, pkg) {
                    value = controller.content.appPrograms(pkg)
                }
                ContentSection(controller, section.title, result, CONTENT_WIDTH * scale, initialFocus, app = app)
            }
        }
        SectionType.RECENT_APPS -> {
            val recent by produceState(config.recent, controller.refreshTick, config.recent) {
                value = controller.apps.recentPackages(config.recent)
            }
            val list = recent.mapNotNull { pkg -> visibleApps.firstOrNull { it.packageName == pkg } }.take(12)
            if (list.isNotEmpty()) AppRow(controller, section.title, list, APP_TILE_WIDTH * scale, initialFocus)
        }
        SectionType.ALL_APPS ->
            AppRow(controller, section.title, visibleApps, APP_TILE_WIDTH * scale, initialFocus)
        SectionType.INPUTS -> {
            val inputs by produceState<List<InputSource>>(emptyList(), controller.refreshTick) {
                value = controller.content.inputs()
            }
            if (inputs.isNotEmpty()) InputsRow(controller, section.title, inputs, scale, initialFocus)
        }
        SectionType.SHORTCUTS -> ShortcutsRow(controller, section.title, scale, initialFocus)
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun HeroSection(config: LauncherConfig) {
    val p = LocalPalette.current
    val now by rememberNow(config.showSeconds)
    Column(
        Modifier
            .fillMaxWidth()
            .height(196.dp)
            .padding(start = 56.dp, top = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (config.showGreeting) {
            val name = config.greetingName.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
            Text("${greetingFor(now)}$name", style = AkoyaType.title, color = p.muted)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatTime(now, config.use24h, config.showSeconds), style = AkoyaType.display, color = p.text)
            val ampm = formatAmPm(now, config.use24h)
            if (ampm.isNotEmpty()) {
                Text(ampm, style = AkoyaType.title, color = p.accent, modifier = Modifier.padding(start = 10.dp, bottom = 20.dp))
            }
        }
        Text(formatDate(now), style = AkoyaType.body, color = p.muted)
    }
}

@Composable
private fun QuickAppsSection(
    controller: LauncherController,
    config: LauncherConfig,
    visibleApps: List<AppInfo>,
    section: SectionConfig,
    initialFocus: FocusRequester?,
) {
    val p = LocalPalette.current
    var picking by remember { mutableStateOf(false) }
    val favorites = config.favorites.mapNotNull { pkg -> visibleApps.firstOrNull { it.packageName == pkg } }
    val width = APP_TILE_WIDTH * section.size.scale
    Column {
        SectionHeader(section.title)
        TvLazyRow() {
            itemsIndexed(favorites, key = { _, a -> a.packageName }) { i, app ->
                AppTile(
                    app = app,
                    width = width,
                    onClick = { controller.launch(app) },
                    onLongClick = { controller.appOptionsFor = app },
                    focusRequester = if (i == 0) initialFocus else null,
                )
            }
            item(key = "__add") {
                Column {
                    TvCard(
                        width = width,
                        height = width * 9f / 16f,
                        onClick = { picking = true },
                        focusRequester = if (favorites.isEmpty()) initialFocus else null,
                    ) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = p.accent, modifier = Modifier.size(30.dp))
                            Text("Add app", style = AkoyaType.small, color = p.muted)
                        }
                    }
                }
            }
        }
    }
    if (picking) {
        AppPickerDialog(
            title = "Add to ${section.title}",
            apps = visibleApps.filter { it.packageName !in config.favorites },
            onPick = { controller.toggleFavorite(it.packageName) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun AppRow(
    controller: LauncherController,
    title: String,
    apps: List<AppInfo>,
    width: Dp,
    initialFocus: FocusRequester?,
) {
    Column {
        SectionHeader(title)
        TvLazyRow() {
            itemsIndexed(apps, key = { _, a -> a.packageName }) { i, app ->
                AppTile(
                    app = app,
                    width = width,
                    onClick = { controller.launch(app) },
                    onLongClick = { controller.appOptionsFor = app },
                    focusRequester = if (i == 0) initialFocus else null,
                )
            }
        }
    }
}

@Composable
private fun ContentSection(
    controller: LauncherController,
    title: String,
    result: ContentResult?,
    width: Dp,
    initialFocus: FocusRequester?,
    app: AppInfo?,
) {
    val p = LocalPalette.current
    val items = result?.items.orEmpty()
    // "Continue watching" with nothing to show and no permission problem: hide quietly.
    if (app == null && result != null && items.isEmpty() && !result.needsPermission) return
    Column {
        SectionHeader(title)
        TvLazyRow() {
            if (app != null) {
                item(key = "__app") {
                    // Large artwork tile that opens the app itself.
                    TvCard(
                        width = width,
                        height = width * 9f / 16f,
                        onClick = { controller.launch(app) },
                        onLongClick = { controller.appOptionsFor = app },
                        focusRequester = initialFocus,
                    ) { AppArtwork(app, width) }
                }
            }
            itemsIndexed(items, key = { i, c -> "c$i-${c.id}" }) { i, item ->
                ContentCard(
                    item = item,
                    width = width,
                    onClick = { controller.content.intentFor(item)?.let { controller.apps.start(it) } },
                    focusRequester = if (i == 0 && app == null) initialFocus else null,
                )
            }
            if (result != null && items.isEmpty()) {
                item(key = "__hint") {
                    HintCard(
                        width = width * 1.4f,
                        height = width * 9f / 16f,
                        text = if (result.needsPermission) {
                            "Show covers here: Akoya Settings → Setup explains the one-time adb permission."
                        } else {
                            "${app?.label ?: "This app"} hasn't shared any picks with other launchers yet. Open it once and check back."
                        },
                        focusRequester = if (app == null) initialFocus else null,
                        onClick = { controller.tab = Tab.SETTINGS },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppMissingRow(controller: LauncherController, title: String, pkg: String, initialFocus: FocusRequester?) {
    Column {
        SectionHeader(title)
        TvLazyRow() {
            item {
                HintCard(
                    width = CONTENT_WIDTH * 1.4f,
                    height = CONTENT_WIDTH * 9f / 16f,
                    text = "$title isn't installed. Press OK to get it from the Play Store.",
                    focusRequester = initialFocus,
                    onClick = { controller.apps.openStorePage(pkg) },
                )
            }
        }
    }
}

@Composable
private fun HintCard(width: Dp, height: Dp, text: String, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val p = LocalPalette.current
    TvCard(width = width, height = height, onClick = onClick, focusRequester = focusRequester) {
        Text(
            text,
            style = AkoyaType.body,
            color = p.muted,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(20.dp),
        )
    }
}

@Composable
private fun InputsRow(
    controller: LauncherController,
    title: String,
    inputs: List<InputSource>,
    scale: Float,
    initialFocus: FocusRequester?,
) {
    val p = LocalPalette.current
    Column {
        SectionHeader(title)
        TvLazyRow() {
            itemsIndexed(inputs, key = { _, s -> s.id }) { i, src ->
                TvCard(
                    width = 170.dp * scale,
                    height = 96.dp * scale,
                    onClick = { controller.apps.start(controller.content.inputIntent(src)) },
                    focusRequester = if (i == 0) initialFocus else null,
                ) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        val icon = src.icon
                        if (icon != null) {
                            androidx.compose.foundation.Image(icon, contentDescription = null, modifier = Modifier.size(30.dp))
                        } else {
                            Icon(Icons.Filled.Input, contentDescription = null, tint = p.accent, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(src.label, style = AkoyaType.label, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private data class Shortcut(val label: String, val icon: ImageVector, val action: () -> Unit)

@Composable
private fun ShortcutsRow(controller: LauncherController, title: String, scale: Float, initialFocus: FocusRequester?) {
    val p = LocalPalette.current
    val shortcuts = remember(controller) {
        listOf(
            Shortcut("Akoya settings", Icons.Filled.Palette) { controller.tab = Tab.SETTINGS },
            Shortcut("All apps", Icons.Filled.Apps) { controller.tab = Tab.APPS },
            Shortcut("Search", Icons.Filled.Search) { controller.openSearch() },
            Shortcut("Screensaver", Icons.Filled.Nightlight) { controller.screensaverVisible = true },
            Shortcut("Wi-Fi", Icons.Filled.Wifi) { controller.openWifi() },
            Shortcut("Bluetooth", Icons.Filled.Bluetooth) { controller.openBluetooth() },
            Shortcut("Picture & sound", Icons.Filled.DisplaySettings) { controller.openDisplay() },
            Shortcut("TV settings", Icons.Filled.Settings) { controller.openSystemSettings() },
        )
    }
    Column {
        SectionHeader(title)
        TvLazyRow() {
            itemsIndexed(shortcuts, key = { _, s -> s.label }) { i, s ->
                TvCard(
                    width = 170.dp * scale,
                    height = 96.dp * scale,
                    onClick = s.action,
                    focusRequester = if (i == 0) initialFocus else null,
                    background = null,
                ) { focused ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(p.accent.copy(alpha = if (focused) 0.30f else 0.12f), Color.Transparent),
                                ),
                            ),
                    )
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(s.icon, contentDescription = null, tint = p.accent, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(s.label, style = AkoyaType.label, color = p.text, maxLines = 1)
                    }
                }
            }
        }
    }
}
