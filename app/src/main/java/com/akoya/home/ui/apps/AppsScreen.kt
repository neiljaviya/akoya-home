package com.akoya.home.ui.apps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import com.akoya.home.ui.components.ListScrollSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.SortByAlpha
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
import com.akoya.home.data.LauncherConfig
import com.akoya.home.ui.LauncherController
import com.akoya.home.ui.components.AppTile
import com.akoya.home.ui.components.TvButton
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette

private enum class AppSort(val label: String) { NAME("A–Z"), NEWEST("Newest") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(controller: LauncherController, config: LauncherConfig, apps: List<AppInfo>) {
    val p = LocalPalette.current
    var sort by remember { mutableStateOf(AppSort.NAME) }
    var showHidden by remember { mutableStateOf(false) }
    val list = apps
        .filter { showHidden || it.packageName !in config.hidden }
        .let { l -> if (sort == AppSort.NAME) l.sortedBy { it.label.lowercase() } else l.sortedByDescending { it.installedAt } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 56.dp, end = 56.dp, top = 18.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Your apps", style = AkoyaType.title, color = p.text)
            Text("  ${list.size}", style = AkoyaType.body, color = p.muted)
            Spacer(Modifier.width(24.dp))
            TvButton("A–Z", icon = Icons.Filled.SortByAlpha, selected = sort == AppSort.NAME, compact = true, onClick = { sort = AppSort.NAME })
            TvButton("Newest", icon = Icons.Filled.NewReleases, selected = sort == AppSort.NEWEST, compact = true, onClick = { sort = AppSort.NEWEST })
            if (config.hidden.isNotEmpty()) {
                TvButton(
                    if (showHidden) "Hide hidden" else "Show hidden (${config.hidden.size})",
                    icon = Icons.Filled.Visibility,
                    selected = showHidden,
                    compact = true,
                    onClick = { showHidden = !showHidden },
                )
            }
        }
        Text(
            "Hold OK (or press Menu) on an app for options: Quick apps, hide, uninstall.",
            style = AkoyaType.small,
            color = p.muted,
            modifier = Modifier.padding(start = 56.dp, bottom = 4.dp),
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides ListScrollSpec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 16.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list, key = { it.packageName }) { app ->
                AppTile(
                    app = app,
                    width = 150.dp,
                    onClick = { controller.launch(app) },
                    onLongClick = { controller.appOptionsFor = app },
                )
            }
        }
        }
    }
}
