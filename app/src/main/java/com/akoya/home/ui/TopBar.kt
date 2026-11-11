package com.akoya.home.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.akoya.home.data.LauncherConfig
import com.akoya.home.ui.components.BrandMark
import com.akoya.home.ui.components.TvButton
import com.akoya.home.ui.components.formatAmPm
import com.akoya.home.ui.components.formatTime
import com.akoya.home.ui.components.rememberNow
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette

enum class NetState { WIFI, ETHERNET, NONE }

@Composable
fun rememberNetState(): NetState {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(NetState.NONE) }
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun read(caps: NetworkCapabilities?) = when {
            caps == null -> NetState.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetState.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetState.WIFI
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetState.WIFI
            else -> NetState.NONE
        }
        state = read(cm.getNetworkCapabilities(cm.activeNetwork))
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { state = read(caps) }
            override fun onLost(network: Network) { state = NetState.NONE }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    return state
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopBar(controller: LauncherController, config: LauncherConfig, navFocus: Map<Tab, FocusRequester>) {
    val p = LocalPalette.current
    val now by rememberNow(withSeconds = false)
    val net = rememberNetState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 56.dp, top = 26.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BrandMark(controller.store)
        Spacer(Modifier.width(28.dp))
        // Remembers the last pill, so pressing Up from the content returns to the current tab
        // instead of whichever pill happens to be nearest.
        Row(
            Modifier
                .focusRestorer { navFocus[controller.tab] ?: FocusRequester.Default }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        TvButton(text = "Search", icon = Icons.Filled.Search, onClick = { controller.openSearch() })
        for (tab in listOf(Tab.HOME, Tab.LIVE, Tab.APPS, Tab.FILES, Tab.SHOP)) {
            TvButton(
                text = tab.label,
                selected = controller.tab == tab,
                focusRequester = navFocus[tab],
                onFocus = { if (it) controller.tab = tab },
                onClick = {
                    controller.tab = tab
                    if (tab == Tab.HOME) controller.onHomePressed()
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        TvButton(
            text = "",
            icon = Icons.Filled.Settings,
            selected = controller.tab == Tab.SETTINGS,
            focusRequester = navFocus[Tab.SETTINGS],
            onClick = { controller.tab = Tab.SETTINGS },
        )
        }
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = when (net) {
                NetState.WIFI -> Icons.Filled.Wifi
                NetState.ETHERNET -> Icons.Filled.SettingsEthernet
                NetState.NONE -> Icons.Filled.WifiOff
            },
            contentDescription = "Network",
            tint = if (net == NetState.NONE) p.muted else p.text,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(formatTime(now, config.use24h, false), style = AkoyaType.title, color = p.text)
        val ampm = formatAmPm(now, config.use24h)
        if (ampm.isNotEmpty()) Text(ampm, style = AkoyaType.small, color = p.muted, modifier = Modifier.padding(top = 10.dp))
    }
}
