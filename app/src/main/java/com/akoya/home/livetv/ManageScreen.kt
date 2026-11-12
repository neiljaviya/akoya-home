package com.akoya.home.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.akoya.home.ui.components.DialogOption
import com.akoya.home.ui.components.OptionsDialog
import com.akoya.home.ui.components.TvButton
import com.akoya.home.ui.settings.SettingRow
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette
import kotlinx.coroutines.delay

private enum class ChannelMenu { MAIN, CATEGORY, NEW_CATEGORY, RENAME }

/** Hold OK on a channel: favourites, categories, rename, hide, picture quality. */
@Composable
fun ChannelOptions(session: LiveTvSession, ch: Channel, state: LiveTvState) {
    val store = session.store
    val fav = ch.id in state.favorites
    val onNow = rememberNowNext(ch)?.now
    var menu by remember { mutableStateOf(ChannelMenu.MAIN) }
    val close = { session.optionsFor = null }
    val categoryKey = session.groupKey.takeIf { it.startsWith("c:") }
    val inThisCategory = categoryKey != null && state.categories.any { "c:" + it.id == categoryKey && ch.id in it.channels }

    when (menu) {
        ChannelMenu.MAIN -> OptionsDialog(
            title = ch.name,
            subtitle = ch.group.ifBlank { null },
            onDismiss = close,
            options = buildList {
                if (session.current?.id != ch.id) {
                    add(DialogOption("Watch", Icons.Filled.GraphicEq) { session.tune(ch, session.index.channels(session.groupKey, session.favorites)); session.panel = Panel.NONE })
                } else {
                    add(DialogOption("Picture quality", Icons.Filled.HighQuality) { session.qualityOpen = true })
                }
                add(DialogOption(if (fav) "Remove from favourites" else "Add to favourites", if (fav) Icons.Filled.StarBorder else Icons.Filled.Star) { store.toggleFavorite(ch.id) })
                if (fav && state.favorites.size > 1 && session.groupKey == GROUP_FAV) {
                    add(DialogOption("Move up", Icons.Filled.ArrowUpward, keepOpen = true) { store.moveFavorite(ch.id, -1) })
                    add(DialogOption("Move down", Icons.Filled.ArrowDownward, keepOpen = true) { store.moveFavorite(ch.id, +1) })
                }
                if (inThisCategory && categoryKey != null) {
                    add(DialogOption("Move up", Icons.Filled.ArrowUpward, keepOpen = true) { store.moveInCategory(categoryKey, ch.id, -1) })
                    add(DialogOption("Move down", Icons.Filled.ArrowDownward, keepOpen = true) { store.moveInCategory(categoryKey, ch.id, +1) })
                }
                add(DialogOption("Add to a category", Icons.Filled.FolderSpecial, keepOpen = true) { menu = ChannelMenu.CATEGORY })
                add(DialogOption("Rename", Icons.Filled.Edit, keepOpen = true) { menu = ChannelMenu.RENAME })
                add(DialogOption("Hide this channel", Icons.Filled.VisibilityOff) { store.hideChannel(ch.id) })
                if (onNow != null && Catchup.covers(ch, onNow.start, System.currentTimeMillis())) {
                    add(DialogOption("Watch \"${onNow.title.take(28)}\" from the start", Icons.Filled.History) { session.playArchive(ch, onNow) })
                }
                add(DialogOption("TV guide", Icons.Filled.CalendarMonth) { session.openGuide() })
            },
        )
        ChannelMenu.CATEGORY -> OptionsDialog(
            title = "Categories",
            subtitle = ch.name,
            onDismiss = close,
            options = state.categories.map { cat ->
                val member = ch.id in cat.channels
                DialogOption(cat.name, if (member) Icons.Filled.Check else null, keepOpen = true) { store.setInCategory("c:" + cat.id, ch.id, !member) }
            } + DialogOption("New category", Icons.Filled.Add, keepOpen = true) { menu = ChannelMenu.NEW_CATEGORY },
        )
        ChannelMenu.NEW_CATEGORY -> FieldsDialog(
            title = "New category",
            subtitle = "${ch.name} will be its first channel.",
            fields = listOf(Field("Name")),
            onDismiss = close,
            onDone = { v -> if (v[0].isNotBlank()) store.addCategory(v[0], ch.id) },
        )
        ChannelMenu.RENAME -> FieldsDialog(
            title = "Rename channel",
            subtitle = "Leave it empty to go back to the provider's name.",
            fields = listOf(Field("Name", initial = ch.name)),
            onDismiss = close,
            onDone = { v -> store.renameChannel(ch.id, v[0]) },
        )
    }
}

/** Hold OK on a group: rename, hide, health check; reorder and delete for your own categories. */
@Composable
fun GroupOptions(session: LiveTvSession, index: ChannelIndex, key: String) {
    val store = session.store
    var renaming by remember { mutableStateOf(false) }
    val close = { session.groupOptionsFor = null }
    val label = index.label(key)
    val custom = key.startsWith("c:")
    val wholePlaylist = key.startsWith("p:") || key.startsWith("s:")
    var moving by remember { mutableStateOf(false) }
    if (moving) {
        OptionsDialog(
            title = "Move $label to",
            subtitle = "Now under ${index.sectionOf(key)}",
            onDismiss = close,
            options = ChannelSort.SECTIONS.filter { it != index.sectionOf(key) }.map { s -> DialogOption(s) { store.setGroupSection(key, s) } },
        )
        return
    }
    if (renaming) {
        FieldsDialog(
            title = "Rename $label",
            subtitle = if (custom) "A new name for this category." else "Leave it empty to go back to the provider's name.",
            fields = listOf(Field("Name", initial = label)),
            onDismiss = close,
            onDone = { v -> store.renameGroup(key, v[0]) },
        )
        return
    }
    OptionsDialog(
        title = label,
        subtitle = "${index.channels(key, session.favorites).size} channels",
        onDismiss = close,
        options = buildList {
            add(DialogOption("Check which channels work", Icons.Filled.NetworkCheck) {
                session.checkChannels(label, index.channels(key, session.favorites))
                session.panel = Panel.MANAGE
            })
            if (!wholePlaylist && key != GROUP_ALL) add(DialogOption("Rename", Icons.Filled.Edit, keepOpen = true) { renaming = true })
            if (!wholePlaylist && !custom && key != GROUP_ALL) add(DialogOption("Move to another section", Icons.Filled.SwapHoriz, keepOpen = true) { moving = true })
            if (key.startsWith("s:")) add(DialogOption("Hide this whole section", Icons.Filled.VisibilityOff) {
                store.setGroupsHidden(index.allGroups.filter { !it.isPlaylist && it.section == index.sectionOf(key) }.map { it.key }, true)
                session.groupKey = GROUP_ALL
            })
            if (custom) {
                add(DialogOption("Move up", Icons.Filled.ArrowUpward, keepOpen = true) { store.moveCategory(key, -1) })
                add(DialogOption("Move down", Icons.Filled.ArrowDownward, keepOpen = true) { store.moveCategory(key, +1) })
                add(DialogOption("Delete this category", Icons.Filled.Delete) { store.deleteCategory(key); session.groupKey = GROUP_ALL })
            } else if (!wholePlaylist && key != GROUP_ALL) {
                add(DialogOption("Hide this group", Icons.Filled.VisibilityOff) { store.setGroupsHidden(listOf(key), true); session.groupKey = GROUP_ALL })
            }
            add(DialogOption("Manage channels", Icons.Filled.Visibility) { session.panel = Panel.MANAGE })
        },
    )
}

private val QUALITY_STEPS = listOf(0, 1080, 720, 480, 360)

/** Picture quality: a ceiling for channels that come in several qualities, plus other versions of this channel. */
@Composable
fun QualityDialog(session: LiveTvSession, state: LiveTvState) {
    val ch = session.current
    val offered = remember(session.player.status, ch?.id) { session.player.videoHeights() }
    val others = remember(ch?.id, session.index) { ch?.let { session.index.alternates(it, limit = 3) }.orEmpty() }
    OptionsDialog(
        title = "Picture quality",
        subtitle = when {
            offered.size > 1 -> "This channel comes in ${offered.joinToString(", ") { "${it}p" }}. A lower limit buffers less on a weak connection."
            others.isNotEmpty() -> "This channel has one quality, so the limit won't change it. Another version may be lighter."
            else -> "This channel has one quality, so the limit won't change it. It applies to channels that offer several."
        },
        onDismiss = { session.qualityOpen = false },
        options = QUALITY_STEPS.map { h ->
            DialogOption(if (h == 0) "Automatic" else "Up to ${h}p", if (state.maxHeight == h) Icons.Filled.Check else null, keepOpen = true) {
                session.store.setMaxHeight(h)
                session.player.setMaxHeight(h)
            }
        } + others.map { other ->
            DialogOption("Switch to ${other.name}", Icons.Filled.SwapHoriz) { session.tune(other, session.index.channels(session.groupKey, session.favorites)) }
        },
    )
}

/**
 * Channel management for big playlists: sorting, hiding whole groups, hidden channels,
 * categories and the health check.
 */
@Composable
fun ManageScreen(session: LiveTvSession, index: ChannelIndex, state: LiveTvState) {
    val p = LocalPalette.current
    val store = session.store
    val health by store.health.collectAsState()
    var playlistMenu by remember { mutableStateOf<GroupEntry?>(null) }
    var newCategory by remember { mutableStateOf(false) }
    var checkMenu by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val offAirCount = remember(state.failing) { state.failing.count { it.value >= 2 } }
    val hiddenGroups = index.allGroups.count { it.hidden }

    LaunchedEffect(Unit) { delay(120); runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(p.background)) {
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 34.dp)) {
            Text("LIVE TV", style = AkoyaType.section, color = p.accent)
            Text("Manage channels", style = AkoyaType.title.copy(fontSize = AkoyaType.title.fontSize * 1.3f), color = p.text)
            Text(
                "${index.all.size} channels shown" +
                    (if (hiddenGroups > 0) "  ·  $hiddenGroups groups hidden" else "") +
                    (if (index.hiddenChannelCount > 0) "  ·  ${index.hiddenChannelCount} channels hidden" else "") +
                    (if (offAirCount > 0) "  ·  $offAirCount not working" else ""),
                style = AkoyaType.body, color = p.muted,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                Modifier.widthIn(max = 760.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 60.dp, start = 4.dp, end = 4.dp),
            ) {
                item(key = "__done") {
                    Row { TvButton("Done", icon = Icons.Filled.Check, selected = true, focusRequester = first, onClick = { session.openChannels() }) }
                }
                item(key = "__check") {
                    val h = health
                    if (h != null && h.running) {
                        SettingRow(
                            title = "Checking ${h.label}: ${h.done} of ${h.total}",
                            subtitle = "${h.bad} not working so far. The picture is paused while a paid playlist is checked. Press OK to stop.",
                            value = "Stop",
                            onClick = { store.stopCheck() },
                        )
                    } else {
                        SettingRow(
                            title = "Check which channels work",
                            subtitle = if (h != null) "Last check, ${h.label}: ${h.done} checked, ${h.bad} not working"
                            else "Tries each channel's link and marks the dead ones. A big paid playlist takes a long time: check a group at a time.",
                            value = "Start",
                            onClick = { checkMenu = true },
                        )
                    }
                }
                item(key = "__hideoff") {
                    SettingRow(
                        title = "Hide channels that don't work",
                        subtitle = "Channels that failed twice, or failed a check, are left out of every list instead of being dimmed",
                        value = if (state.hideOffAir) "On" else "Off",
                        onClick = { store.setHideOffAir(!state.hideOffAir) },
                    )
                }
                if (offAirCount > 0) item(key = "__clearoff") {
                    SettingRow(title = "Forget the \"not working\" marks", subtitle = "$offAirCount channels are marked", value = "Clear", onClick = { store.clearOffAir() })
                }
                item(key = "__sort") {
                    SettingRow(
                        title = "Channel order",
                        subtitle = "Your own categories and favourites always keep the order you gave them",
                        value = if (state.sortAz) "A to Z" else "As the provider lists them",
                        onClick = { store.setSortAz(!state.sortAz) },
                    )
                }
                item(key = "__newcat") {
                    SettingRow(
                        title = "New category",
                        subtitle = if (state.categories.isEmpty()) "Your own group of channels, shown above the provider's. Add channels by holding OK on them"
                        else "You have: " + state.categories.joinToString(", ") { it.name },
                        value = "Add",
                        onClick = { newCategory = true },
                    )
                }
                if (index.hiddenChannelCount > 0) item(key = "__unhide") {
                    SettingRow(title = "Hidden channels", subtitle = "${index.hiddenChannelCount} channels you hid one by one", value = "Show all", onClick = { store.showAllChannels() })
                }
                item(key = "__groups") {
                    Text("GROUPS  ·  OK SHOWS OR HIDES", style = AkoyaType.section, color = p.muted, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
                }
                items(index.allGroups, key = { it.key }, contentType = { 1 }) { g ->
                    if (g.isPlaylist) {
                        SettingRow(title = g.label.uppercase(), subtitle = "${g.count} channels shown", value = "Show or hide all", onClick = { playlistMenu = g })
                    } else {
                        SettingRow(
                            title = g.label,
                            subtitle = "${g.count} channels",
                            value = if (g.hidden) "Hidden" else "Shown",
                            valueColor = if (g.hidden) p.muted else null,
                            onClick = { store.setGroupsHidden(listOf(g.key), !g.hidden) },
                        )
                    }
                }
            }
        }
    }

    playlistMenu?.let { g ->
        val keys = index.allGroups.filter { !it.isPlaylist && it.section == g.section }.map { it.key }
        OptionsDialog(
            title = g.label,
            subtitle = "${keys.size} groups",
            onDismiss = { playlistMenu = null },
            options = listOf(
                DialogOption("Hide all groups", Icons.Filled.VisibilityOff) { store.setGroupsHidden(keys, true) },
                DialogOption("Show all groups", Icons.Filled.Visibility) { store.setGroupsHidden(keys, false) },
                DialogOption("Check which channels work", Icons.Filled.NetworkCheck) { session.checkChannels(g.label, index.channels(g.key, emptyList())) },
            ),
        )
    }
    if (checkMenu) {
        val currentKey = session.groupKey
        OptionsDialog(
            title = "Check which channels work",
            subtitle = "Pick what to check",
            onDismiss = { checkMenu = false },
            options = buildList {
                val here = index.channels(currentKey, session.favorites)
                if (here.isNotEmpty()) add(DialogOption("${index.label(currentKey)} (${here.size})", Icons.Filled.NetworkCheck) { session.checkChannels(index.label(currentKey), here) })
                if (state.favorites.isNotEmpty() && currentKey != GROUP_FAV) add(DialogOption("Favourites (${session.favorites.size})", Icons.Filled.Star) { session.checkChannels("Favourites", session.favorites) })
                index.groups.filter { it.isPlaylist }.forEach { g ->
                    add(DialogOption("${g.label} (${g.count})", Icons.Filled.NetworkCheck) { session.checkChannels(g.label, index.channels(g.key, emptyList())) })
                }
                if (currentKey != GROUP_ALL) add(DialogOption("Everything (${index.all.size})", Icons.Filled.NetworkCheck) { session.checkChannels("all channels", index.all) })
            },
        )
    }
    if (newCategory) {
        FieldsDialog(
            title = "New category",
            subtitle = "Add channels to it by holding OK on a channel and choosing \"Add to a category\".",
            fields = listOf(Field("Name")),
            onDismiss = { newCategory = false },
            onDone = { v -> if (v[0].isNotBlank()) store.addCategory(v[0]) },
        )
    }
}
