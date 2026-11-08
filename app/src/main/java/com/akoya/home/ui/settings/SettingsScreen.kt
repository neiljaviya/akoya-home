package com.akoya.home.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
import com.akoya.home.ui.components.ListScrollSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.akoya.home.data.AppInfo
import com.akoya.home.data.CardSize
import com.akoya.home.data.LauncherConfig
import com.akoya.home.data.ScreensaverStyle
import com.akoya.home.data.SectionConfig
import com.akoya.home.data.SectionType
import com.akoya.home.data.WallpaperMode
import com.akoya.home.data.defaultSections
import com.akoya.home.service.HomeGuardService
import com.akoya.home.ui.LauncherController
import com.akoya.home.ui.components.AppPickerDialog
import com.akoya.home.ui.components.DialogOption
import com.akoya.home.ui.components.OptionsDialog
import com.akoya.home.ui.components.TextInputDialog
import com.akoya.home.ui.components.TvButton
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette

private enum class SettingsTab(val label: String) {
    APPEARANCE("Appearance"),
    LAYOUT("Home layout"),
    CLOCK("Clock"),
    SCREENSAVER("Screensaver"),
    APPS("Apps"),
    SETUP("Setup & backup"),
}

private const val PKG = "com.akoya.home"

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(controller: LauncherController, config: LauncherConfig, apps: List<AppInfo>) {
    val p = LocalPalette.current
    var tab by remember { mutableStateOf(SettingsTab.APPEARANCE) }

    // Dialog state for the layout editor
    var editingId by remember { mutableStateOf<String?>(null) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var choosingAppFor by remember { mutableStateOf<String?>(null) }
    var addingSection by remember { mutableStateOf(false) }
    var addingAppSection by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var editingBrand by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .padding(start = 56.dp, end = 56.dp, top = 16.dp, bottom = 4.dp)
                .focusRestorer()
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Akoya settings", style = AkoyaType.title, color = p.text)
            Spacer(Modifier.width(20.dp))
            SettingsTab.entries.forEach { t ->
                TvButton(
                    text = t.label,
                    selected = tab == t,
                    compact = true,
                    onFocus = { if (it) tab = t },
                    onClick = { tab = t },
                )
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides ListScrollSpec) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 12.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (tab) {
                SettingsTab.APPEARANCE -> appearance(controller, config, onEditBrand = { editingBrand = true })
                SettingsTab.LAYOUT -> layout(
                    controller, config,
                    onEdit = { editingId = it },
                    onAdd = { addingSection = true },
                )
                SettingsTab.CLOCK -> clock(controller, config, onEditName = { editingName = true })
                SettingsTab.SCREENSAVER -> screensaver(controller, config)
                SettingsTab.APPS -> appsTab(controller, config, apps)
                SettingsTab.SETUP -> setup(controller, onReset = { confirmReset = true })
            }
        }
        }
    }

    // ---------------- dialogs ----------------
    val store = controller.store
    fun updateSection(id: String, f: (SectionConfig) -> SectionConfig) =
        store.update { c -> c.copy(sections = c.sections.map { if (it.id == id) f(it) else it }) }

    fun moveSection(id: String, delta: Int) = store.update { c ->
        val list = c.sections.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) c else {
            val s = list.removeAt(i); list.add(j, s); c.copy(sections = list)
        }
    }

    config.sections.firstOrNull { it.id == editingId }?.let { s ->
        OptionsDialog(
            title = s.title,
            subtitle = s.type.description,
            onDismiss = { editingId = null },
            options = buildList {
                add(DialogOption("Move up", Icons.Filled.KeyboardArrowUp, keepOpen = true) { moveSection(s.id, -1) })
                add(DialogOption("Move down", Icons.Filled.KeyboardArrowDown, keepOpen = true) { moveSection(s.id, 1) })
                add(
                    DialogOption(if (s.visible) "Hide this section" else "Show this section", Icons.Filled.Visibility, keepOpen = true) {
                        updateSection(s.id) { it.copy(visible = !it.visible) }
                    },
                )
                if (s.type != SectionType.HERO) {
                    add(
                        DialogOption("Card size: ${s.size.label}", Icons.Filled.AspectRatio, keepOpen = true) {
                            updateSection(s.id) { it.copy(size = CardSize.entries[(it.size.ordinal + 1) % CardSize.entries.size]) }
                        },
                    )
                }
                add(DialogOption("Rename", Icons.Filled.Edit) { renamingId = s.id })
                if (s.type == SectionType.APP_CONTENT) add(DialogOption("Choose app", Icons.Filled.Apps) { choosingAppFor = s.id })
                add(DialogOption("Remove from Home", Icons.Filled.Delete) {
                    store.update { c -> c.copy(sections = c.sections.filter { it.id != s.id }) }
                })
            },
        )
    }

    config.sections.firstOrNull { it.id == renamingId }?.let { s ->
        TextInputDialog(
            title = "Rename section",
            initial = s.title,
            onDone = { name -> if (name.isNotBlank()) updateSection(s.id) { it.copy(title = name) } },
            onDismiss = { renamingId = null },
        )
    }

    if (choosingAppFor != null) {
        AppPickerDialog(
            title = "Spotlight which app?",
            apps = apps,
            onPick = { app -> choosingAppFor?.let { id -> updateSection(id) { it.copy(packageName = app.packageName, title = app.label) } } },
            onDismiss = { choosingAppFor = null },
        )
    }

    if (addingSection) {
        OptionsDialog(
            title = "Add a section",
            subtitle = "It's added to the bottom of Home. Use Move up to place it.",
            onDismiss = { addingSection = false },
            options = SectionType.entries.map { type ->
                DialogOption(type.label) {
                    if (type == SectionType.APP_CONTENT) {
                        addingAppSection = true
                    } else {
                        store.update { it.copy(sections = it.sections + SectionConfig(type = type)) }
                    }
                }
            },
        )
    }

    if (addingAppSection) {
        AppPickerDialog(
            title = "Spotlight which app?",
            apps = apps,
            onPick = { app ->
                store.update {
                    it.copy(
                        sections = it.sections + SectionConfig(
                            type = SectionType.APP_CONTENT,
                            title = app.label,
                            packageName = app.packageName,
                        ),
                    )
                }
            },
            onDismiss = { addingAppSection = false },
        )
    }

    if (editingName) {
        TextInputDialog(
            title = "Name for the greeting",
            initial = config.greetingName,
            onDone = { name -> store.update { it.copy(greetingName = name) } },
            onDismiss = { editingName = false },
        )
    }

    if (editingBrand) {
        TextInputDialog(
            title = "Name shown on screen",
            initial = config.brandName,
            onDone = { name -> store.update { it.copy(brandName = name.ifBlank { "Javiya's TV" }) } },
            onDismiss = { editingBrand = false },
        )
    }

    if (confirmReset) {
        OptionsDialog(
            title = "Reset Akoya Home?",
            subtitle = "Theme, layout, Quick apps and screensaver go back to defaults.",
            onDismiss = { confirmReset = false },
            options = listOf(
                DialogOption("Cancel") {},
                DialogOption("Reset everything", Icons.Filled.Delete) {
                    store.resetToDefaults()
                    controller.seedFavorites(apps)
                },
            ),
        )
    }
}

// ------------------------------------------------------------------------------------------
// Tabs

private fun LazyListScope.appearance(controller: LauncherController, config: LauncherConfig, onEditBrand: () -> Unit) {
    val store = controller.store
    item { SettingRow(title = "Name shown on screen", value = config.brandName, subtitle = "Top-left of Home and on the screensaver", onClick = onEditBrand) }
    item { SettingsNote("Theme (from the Akoya signage)") }
    item { ThemePicker(config.themeId) { pal -> store.update { it.copy(themeId = pal.id) } } }
    item {
        ChoiceRow(
            title = "Background",
            options = WallpaperMode.entries,
            selected = config.wallpaperMode,
            label = { it.label },
            onSelect = { m -> store.update { it.copy(wallpaperMode = m) } },
        )
    }
    if (config.wallpaperMode == WallpaperMode.IMAGE) {
        item {
            val files = store.images(store.wallpapersDir).map { it.name }
            ChoiceRow(
                title = "Background image",
                subtitle = "Add images with: adb push photo.jpg ${store.wallpapersDir.absolutePath}/",
                options = files,
                selected = config.wallpaperFile ?: files.firstOrNull(),
                label = { it ?: "None found" },
                onSelect = { f -> store.update { it.copy(wallpaperFile = f) } },
            )
        }
        item {
            ChoiceRow(
                title = "Image dimming",
                options = listOf(0f, 0.2f, 0.35f, 0.45f, 0.6f, 0.75f),
                selected = config.wallpaperDim,
                label = { "${(it * 100).toInt()}%" },
                onSelect = { d -> store.update { it.copy(wallpaperDim = d) } },
            )
        }
    }
    item {
        ChoiceRow(
            title = "Focus zoom",
            subtitle = "How much a tile grows when selected",
            options = listOf(1.0f, 1.05f, 1.08f, 1.12f),
            selected = config.focusZoom,
            label = { z ->
                when (z) {
                    1.0f -> "Off"; 1.05f -> "Subtle"; 1.08f -> "Standard"; else -> "Bold"
                }
            },
            onSelect = { z -> store.update { it.copy(focusZoom = z) } },
        )
    }
    item { ToggleRow("App names under tiles", config.showAppLabels, { v -> store.update { it.copy(showAppLabels = v) } }) }
    item { ToggleRow("Animations", config.animations, { v -> store.update { it.copy(animations = v) } }, "Turn off for the snappiest feel on slower TVs") }
    item {
        SettingRow(
            title = "Logo",
            value = if (store.logoFile() != null) "Custom logo" else "Name as text",
            subtitle = "Optional image logo instead of the name: adb push logo.png ${store.brandingDir.absolutePath}/logo.png",
        )
    }
}

private fun LazyListScope.layout(
    controller: LauncherController,
    config: LauncherConfig,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
) {
    item { SettingsNote("Sections appear on Home top to bottom. Select one to move, resize, rename, hide or remove it.") }
    itemsIndexed(config.sections, key = { _, s -> s.id }) { i, s ->
        val appName = s.packageName?.let { controller.apps.labelFor(it) }
        SettingRow(
            title = "${i + 1}.  ${s.title}",
            value = if (s.visible) (if (s.type == SectionType.HERO) "Shown" else s.size.label) else "Hidden",
            subtitle = s.type.label + (appName?.let { " · $it" } ?: ""),
            onClick = { onEdit(s.id) },
        )
    }
    item { SettingRow(title = "+  Add a section", subtitle = "Clock, Quick apps, an app spotlight, inputs, shortcuts…", onClick = onAdd) }
    item {
        SettingRow(
            title = "Restore default layout",
            onClick = { controller.store.update { it.copy(sections = defaultSections()) } },
        )
    }
}

private fun LazyListScope.clock(controller: LauncherController, config: LauncherConfig, onEditName: () -> Unit) {
    val store = controller.store
    item { ToggleRow("24-hour clock", config.use24h, { v -> store.update { it.copy(use24h = v) } }) }
    item { ToggleRow("Show seconds on Home", config.showSeconds, { v -> store.update { it.copy(showSeconds = v) } }) }
    item { ToggleRow("Greeting", config.showGreeting, { v -> store.update { it.copy(showGreeting = v) } }, "\"Good evening\" above the clock") }
    item {
        SettingRow(
            title = "Name in greeting",
            value = config.greetingName.ifBlank { "Not set" },
            onClick = onEditName,
        )
    }
}

private fun LazyListScope.screensaver(controller: LauncherController, config: LauncherConfig) {
    val store = controller.store
    val ss = config.screensaver
    item {
        ChoiceRow(
            title = "Style",
            options = ScreensaverStyle.entries,
            selected = ss.style,
            label = { it.label },
            onSelect = { s -> store.update { it.copy(screensaver = it.screensaver.copy(style = s)) } },
        )
    }
    item {
        ChoiceRow(
            title = "Start after",
            subtitle = "Idle time on the Akoya home screen",
            options = listOf(0, 1, 2, 5, 10, 15, 30, 60),
            selected = ss.idleMinutes,
            label = { if (it == 0) "Never" else "$it min" },
            onSelect = { m -> store.update { it.copy(screensaver = it.screensaver.copy(idleMinutes = m)) } },
        )
    }
    if (ss.style == ScreensaverStyle.PHOTOS) {
        item {
            ChoiceRow(
                title = "Each photo for",
                options = listOf(5, 8, 12, 20, 30, 60),
                selected = ss.photoSeconds,
                label = { "$it s" },
                onSelect = { s -> store.update { it.copy(screensaver = it.screensaver.copy(photoSeconds = s)) } },
            )
        }
        item {
            val count = store.images(store.screensaverDir).size
            SettingRow(
                title = "Photos",
                value = "$count found",
                subtitle = "adb push <photos> ${store.screensaverDir.absolutePath}/",
            )
        }
    }
    item { ToggleRow("Show clock", ss.showClock, { v -> store.update { it.copy(screensaver = it.screensaver.copy(showClock = v)) } }) }
    item { SettingRow(title = "Preview now", subtitle = "Press any button to exit", onClick = { controller.screensaverVisible = true }) }
    item {
        SettingRow(
            title = "Use as the TV's screensaver too",
            subtitle = "Replaces Google's Ambient mode everywhere. If the TV hides the option, run:\n" +
                "adb shell settings put secure screensaver_components $PKG/.dream.AkoyaDreamService",
            onClick = { controller.openDreamSettings() },
        )
    }
}

private fun LazyListScope.appsTab(controller: LauncherController, config: LauncherConfig, apps: List<AppInfo>) {
    item {
        SettingRow(
            title = "Reset Quick apps",
            subtitle = "Back to Netflix, YouTube, Prime Video, JioHotstar… (whatever is installed)",
            onClick = {
                controller.store.update { it.copy(favorites = emptyList(), favoritesInitialised = false) }
                controller.seedFavorites(apps)
            },
        )
    }
    item { SettingsNote("Hidden apps (select to show again)") }
    val hidden = config.hidden.toList().sorted()
    if (hidden.isEmpty()) {
        item { SettingsNote("Nothing hidden. Hold OK on any app to hide it.") }
    } else {
        itemsIndexed(hidden, key = { _, pkg -> "h$pkg" }) { _, pkg ->
            SettingRow(
                title = controller.apps.labelFor(pkg),
                value = "Show",
                subtitle = pkg,
                onClick = { controller.setHidden(pkg, false) },
            )
        }
    }
}

private fun LazyListScope.setup(controller: LauncherController, onReset: () -> Unit) {
    item {
        val isDefault = remember(controller.refreshTick) { controller.apps.isDefaultLauncher() }
        SettingRow(
            title = "Default home screen",
            value = if (isDefault) "Akoya Home" else "Not yet",
            subtitle = if (isDefault) "The Home button opens Akoya." else
                "adb shell cmd package set-home-activity $PKG/.MainActivity (see the setup guide)",
            onClick = { controller.openHomeSettings() },
        )
    }
    item {
        val on = remember(controller.refreshTick) { HomeGuardService.isEnabled(controller.context) }
        SettingRow(
            title = "Home button takeover (no computer needed)",
            value = if (on) "On" else "Off",
            subtitle = if (on) "Pressing Home always lands on Akoya." else
                "TV Settings → System → Accessibility → scroll to \"Akoya Home – Home button\" → On",
            onClick = { controller.openAccessibility() },
        )
    }
    item {
        val granted = remember(controller.refreshTick) { controller.content.hasEpgPermission() }
        SettingRow(
            title = "Continue watching & app covers",
            value = if (granted) "Allowed" else "Not allowed",
            subtitle = if (granted) "Rows fill in when apps share content." else
                "adb shell pm grant $PKG com.android.providers.tv.permission.READ_EPG_DATA",
        )
    }
    item {
        val granted = remember(controller.refreshTick) { controller.apps.hasUsageAccess() }
        SettingRow(
            title = "Smarter Recent apps",
            value = if (granted) "Allowed" else "Basic",
            subtitle = if (granted) "Uses the TV's app usage history." else
                "adb shell appops set $PKG GET_USAGE_STATS allow",
        )
    }
    item {
        SettingRow(
            title = "Back up settings",
            subtitle = "Saves theme, layout and apps to a JSON file on the TV",
            onClick = {
                controller.store.exportBackup()
                    .onSuccess { controller.toast("Saved to ${it.absolutePath}") }
                    .onFailure { controller.toast("Backup failed: ${it.message}") }
            },
        )
    }
    item {
        SettingRow(
            title = "Restore settings",
            subtitle = "Loads the last backup",
            onClick = {
                controller.store.importBackup()
                    .onSuccess { controller.toast("Settings restored") }
                    .onFailure { controller.toast("No backup found") }
            },
        )
    }
    item { SettingRow(title = "Open Android TV settings", onClick = { controller.openSystemSettings() }) }
    item { SettingRow(title = "Reset Akoya Home", subtitle = "Restore every option to its default", onClick = onReset) }
    item {
        val version = remember {
            runCatching { controller.context.packageManager.getPackageInfo(PKG, 0).versionName }.getOrNull() ?: "?"
        }
        SettingsNote("Akoya Home $version")
    }
}
