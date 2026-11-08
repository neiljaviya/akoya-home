package com.akoya.home.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.akoya.home.data.HealthItem
import com.akoya.home.data.HealthLevel
import com.akoya.home.data.HealthReport
import com.akoya.home.data.LauncherConfig
import com.akoya.home.server.CompanionServer
import com.akoya.home.ui.LauncherController
import com.akoya.home.ui.Tab
import com.akoya.home.ui.components.TvButton
import com.akoya.home.ui.components.qrBitmap
import com.akoya.home.ui.files.hasStorageAccess
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette

private fun levelColor(l: HealthLevel, muted: Color): Color = when (l) {
    HealthLevel.OK -> Color(0xFF8DBF8B)
    HealthLevel.INFO -> muted
    HealthLevel.WARN -> Color(0xFFE0B04A)
    HealthLevel.BAD -> Color(0xFFE0705A)
}

internal fun LazyListScope.healthTab(report: HealthReport?, onRerun: () -> Unit, onItem: (HealthItem) -> Unit) {
    item {
        val p = LocalPalette.current
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        report == null -> "Checking the TV…"
                        report.bad == 0 && report.warn == 0 -> "All clear"
                        else -> listOfNotNull(
                            report.bad.takeIf { it > 0 }?.let { "$it to fix" },
                            report.warn.takeIf { it > 0 }?.let { "$it to review" },
                        ).joinToString(" · ")
                    },
                    style = AkoyaType.title,
                    color = when {
                        report == null -> p.text
                        report.bad > 0 -> Color(0xFFE0705A)
                        report.warn > 0 -> Color(0xFFE0B04A)
                        else -> Color(0xFF8DBF8B)
                    },
                )
                Text("Runs on the TV itself. Select an item to open the right setting, or an app to uninstall it.", style = AkoyaType.small, color = p.muted)
            }
            TvButton("Check again", icon = Icons.Filled.Refresh, onClick = onRerun)
        }
    }
    report?.groups?.forEach { g ->
        item(key = "g-${g.title}") { SettingsNote(g.title.uppercase()) }
        g.items.forEachIndexed { i, it ->
            item(key = "h-${g.title}-$i") {
                val p = LocalPalette.current
                SettingRow(
                    title = it.title,
                    subtitle = it.detail,
                    value = it.level.label,
                    valueColor = levelColor(it.level, p.muted),
                    onClick = { onItem(it) },
                )
            }
        }
    }
}

internal fun LazyListScope.phoneTab(controller: LauncherController, config: LauncherConfig) {
    val store = controller.store
    item {
        val p = LocalPalette.current
        val url = CompanionServer.url()
        val qr = remember(url, config.companionPin) { url?.let { qrBitmap("$it/?pin=${config.companionPin}") } }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(p.surface)
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (qr != null) {
                Image(qr, contentDescription = "QR code", modifier = Modifier.size(170.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(28.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PHONE PAGE", style = AkoyaType.section, color = p.accent)
                Text(url ?: "Not on Wi-Fi", style = AkoyaType.title, color = p.text)
                Text("PIN  ${config.companionPin.ifBlank { "…" }}", style = AkoyaType.title, color = p.text)
                Text(
                    "Scan with your phone camera, or open the address in Safari/Chrome on the same Wi-Fi. " +
                        "Upload and crop screensaver photos and wallpapers, manage files, name the TV and add your TMDB key.",
                    style = AkoyaType.small,
                    color = p.muted,
                )
                Text(if (CompanionServer.isRunning) "● Running" else "○ Starting…", style = AkoyaType.label, color = if (CompanionServer.isRunning) Color(0xFF8DBF8B) else p.muted)
            }
        }
    }
    item {
        SettingRow(
            title = "Change PIN",
            subtitle = "Phones using the old PIN will need the new one",
            value = config.companionPin,
            onClick = { store.update { it.copy(companionPin = CompanionServer.newPin()) } },
        )
    }
    item {
        SettingRow(
            title = "Trending covers (TMDB)",
            value = if (config.tmdbKey.isNotBlank()) "Connected" else "Not set",
            subtitle = if (config.tmdbKey.isNotBlank()) "Netflix, Prime Video, JioHotstar, ZEE5, SonyLIV, Apple TV+ spotlights show what's trending; YouTube shows trailers."
            else "Get a free key at themoviedb.org → Settings → API, then paste it on the phone page → TV tab.",
        )
    }
    item {
        ChoiceRow(
            title = "Covers region",
            options = listOf("IN", "CA", "US", "GB", "AE", "AU"),
            selected = config.tmdbRegion,
            label = { mapOf("IN" to "India", "CA" to "Canada", "US" to "United States", "GB" to "United Kingdom", "AE" to "UAE", "AU" to "Australia")[it] ?: it },
            onSelect = { r -> com.akoya.home.data.TmdbRepository.instance.clear(); store.update { it.copy(tmdbRegion = r) } },
        )
    }
    item {
        val ok = remember(controller.refreshTick) { hasStorageAccess(controller.context) }
        SettingRow(
            title = "Storage access for Files",
            value = if (ok) "Allowed" else "Not allowed",
            subtitle = if (ok) "Files and the phone page can browse Downloads, Movies and USB drives." else "Open Files and choose Allow access.",
            onClick = { controller.tab = Tab.FILES },
        )
    }
}
