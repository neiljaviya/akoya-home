package com.akoya.home.ui

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.akoya.home.data.AppInfo
import com.akoya.home.data.AppRepository
import com.akoya.home.data.ConfigStore
import com.akoya.home.data.ContentRepository
import com.akoya.home.data.DEFAULT_FAVORITES
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Tab(val label: String) { HOME("Home"), APPS("Apps"), SHOP("Shop"), SETTINGS("Settings") }

/** UI state + actions shared by every screen. Lives as long as the launcher activity. */
class LauncherController(val context: Context) {
    val store = ConfigStore.get(context)
    val apps = AppRepository.get(context)
    val content = ContentRepository.get(context)

    var tab by mutableStateOf(Tab.HOME)
    var appOptionsFor by mutableStateOf<AppInfo?>(null)
    var screensaverVisible by mutableStateOf(false)
    var resumed by mutableStateOf(false)
        private set

    /** Bumped on every resume so content rows reload when returning from an app. */
    var refreshTick by mutableIntStateOf(0)
        private set

    /** Bumped when Home is pressed while already home: scroll to top + refocus. */
    var homeReset by mutableIntStateOf(0)
        private set

    /** Last homeReset value Home acted on (so switching tabs doesn't steal focus). */
    var handledHomeReset = -1

    private val _lastInteraction = MutableStateFlow(SystemClock.uptimeMillis())
    val lastInteraction: StateFlow<Long> = _lastInteraction.asStateFlow()

    fun touch() { _lastInteraction.value = SystemClock.uptimeMillis() }

    fun onResume() { resumed = true; refreshTick++; touch() }
    fun onPause() { resumed = false; screensaverVisible = false }

    fun onHomePressed() {
        screensaverVisible = false
        appOptionsFor = null
        tab = Tab.HOME
        homeReset++
    }

    /** Returns true if Back was used to leave a sub-screen. */
    fun onBack(): Boolean {
        if (screensaverVisible) { screensaverVisible = false; return false }
        if (tab != Tab.HOME) { tab = Tab.HOME; return true }
        homeReset++
        return false
    }

    // ---- favourites / hidden ----

    fun seedFavorites(installed: List<AppInfo>) {
        if (installed.isEmpty() || store.current.favoritesInitialised) return
        val pkgs = installed.map { it.packageName }.toSet()
        val seeded = DEFAULT_FAVORITES.filter { it in pkgs }.toMutableList()
        installed.filter { it.isTvApp && it.packageName !in seeded }.take((6 - seeded.size).coerceAtLeast(0))
            .forEach { seeded += it.packageName }
        store.update { it.copy(favorites = seeded.distinct(), favoritesInitialised = true) }
    }

    fun isFavorite(pkg: String) = pkg in store.current.favorites

    fun toggleFavorite(pkg: String) = store.update {
        if (pkg in it.favorites) it.copy(favorites = it.favorites - pkg)
        else it.copy(favorites = it.favorites + pkg, hidden = it.hidden - pkg)
    }

    fun moveFavorite(pkg: String, delta: Int) = store.update {
        val list = it.favorites.toMutableList()
        val i = list.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j !in list.indices) return@update it
        list.removeAt(i); list.add(j, pkg)
        it.copy(favorites = list)
    }

    fun setHidden(pkg: String, hidden: Boolean) = store.update {
        if (hidden) it.copy(hidden = it.hidden + pkg, favorites = it.favorites - pkg)
        else it.copy(hidden = it.hidden - pkg)
    }

    // ---- system actions ----

    fun launch(app: AppInfo) = apps.launch(app)

    fun openSearch() = apps.startFirst(
        Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH),
        Intent(Intent.ACTION_ASSIST),
        Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=&c=apps")),
    )

    fun openSystemSettings() = apps.start(Intent(Settings.ACTION_SETTINGS))
    fun openWifi() = apps.startFirst(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    fun openBluetooth() = apps.startFirst(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    fun openDisplay() = apps.startFirst(Intent(Settings.ACTION_DISPLAY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    fun openDreamSettings() = apps.startFirst(Intent(Settings.ACTION_DREAM_SETTINGS), Intent(Settings.ACTION_DISPLAY_SETTINGS))
    fun openAccessibility() = apps.startFirst(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    fun openHomeSettings() = apps.startFirst(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}

val LocalLauncher = staticCompositionLocalOf<LauncherController> { error("LauncherController not provided") }
