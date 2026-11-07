package com.akoya.home.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Shop
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.akoya.home.data.AppInfo
import com.akoya.home.data.LauncherConfig
import com.akoya.home.ui.apps.AppsScreen
import com.akoya.home.ui.components.Backdrop
import com.akoya.home.ui.components.DialogOption
import com.akoya.home.ui.components.OptionsDialog
import com.akoya.home.ui.home.HomeScreen
import com.akoya.home.ui.screensaver.ScreensaverContent
import com.akoya.home.ui.settings.SettingsScreen
import com.akoya.home.ui.shop.ShopScreen
import com.akoya.home.ui.theme.AkoyaTheme
import com.akoya.home.ui.theme.LocalPalette
import kotlinx.coroutines.delay

@Composable
fun LauncherRoot(controller: LauncherController) {
    val config by controller.store.config.collectAsState()
    val apps by controller.apps.apps.collectAsState()
    val lastInteraction by controller.lastInteraction.collectAsState()

    LaunchedEffect(apps) { controller.seedFavorites(apps) }

    // In-launcher idle screensaver.
    val idleMinutes = config.screensaver.idleMinutes
    LaunchedEffect(lastInteraction, idleMinutes, controller.resumed) {
        if (idleMinutes > 0 && controller.resumed && !controller.screensaverVisible) {
            delay(idleMinutes * 60_000L)
            controller.screensaverVisible = true
        }
    }

    AkoyaTheme(config) {
        CompositionLocalProvider(LocalLauncher provides controller) {
            val p = LocalPalette.current
            val navFocus = remember { Tab.entries.associateWith { FocusRequester() } }

            BackHandler {
                if (controller.onBack()) runCatching { navFocus[Tab.HOME]?.requestFocus() }
            }

            Box(Modifier.fillMaxSize().background(p.background)) {
                Backdrop(config, controller.store)
                Column(Modifier.fillMaxSize()) {
                    TopBar(controller, config, navFocus)
                    Crossfade(
                        targetState = controller.tab,
                        animationSpec = tween(if (config.animations) 260 else 0),
                        label = "tabs",
                    ) { tab ->
                        when (tab) {
                            Tab.HOME -> HomeScreen(controller, config, apps)
                            Tab.APPS -> AppsScreen(controller, config, apps)
                            Tab.SHOP -> ShopScreen(controller, apps)
                            Tab.SETTINGS -> SettingsScreen(controller, config, apps)
                        }
                    }
                }

                controller.appOptionsFor?.let { app -> AppOptionsDialog(controller, config, app) }

                if (controller.screensaverVisible) {
                    ScreensaverContent(config, controller.store, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun AppOptionsDialog(controller: LauncherController, config: LauncherConfig, app: AppInfo) {
    val pkg = app.packageName
    val fav = pkg in config.favorites
    val hidden = pkg in config.hidden
    OptionsDialog(
        title = app.label,
        subtitle = pkg,
        onDismiss = { controller.appOptionsFor = null },
        options = buildList {
            add(DialogOption("Open", Icons.Filled.Launch) { controller.launch(app) })
            add(
                DialogOption(if (fav) "Remove from Quick apps" else "Add to Quick apps", if (fav) Icons.Filled.StarBorder else Icons.Filled.Star) {
                    controller.toggleFavorite(pkg)
                },
            )
            if (fav) {
                add(DialogOption("Move left in Quick apps", Icons.Filled.ChevronLeft, keepOpen = true) { controller.moveFavorite(pkg, -1) })
                add(DialogOption("Move right in Quick apps", Icons.Filled.ChevronRight, keepOpen = true) { controller.moveFavorite(pkg, 1) })
            }
            add(
                DialogOption(if (hidden) "Show in Home & Apps" else "Hide from Home & Apps", if (hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff) {
                    controller.setHidden(pkg, !hidden)
                },
            )
            add(DialogOption("Open in Play Store", Icons.Filled.Shop) { controller.apps.openStorePage(pkg) })
            add(DialogOption("App info", Icons.Filled.Info) { controller.apps.openAppInfo(pkg) })
            if (!app.isSystem) add(DialogOption("Uninstall", Icons.Filled.Delete) { controller.apps.uninstall(pkg) })
        },
    )
}
