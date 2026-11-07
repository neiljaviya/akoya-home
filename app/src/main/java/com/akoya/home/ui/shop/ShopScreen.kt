package com.akoya.home.ui.shop

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.akoya.home.data.AppInfo
import com.akoya.home.data.ContentResult
import com.akoya.home.ui.LauncherController
import com.akoya.home.ui.components.AppTile
import com.akoya.home.ui.components.ContentCard
import com.akoya.home.ui.components.SectionHeader
import com.akoya.home.ui.components.TvCard
import com.akoya.home.ui.home.CONTENT_WIDTH
import com.akoya.home.ui.home.ROW_PADDING
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette

private val STORE_PACKAGES = listOf(
    "com.android.vending",            // Play Store
    "com.google.android.videos",      // Google TV / Play Movies
    "com.google.android.play.games",  // Play Games
    "com.google.android.youtube.tv",
    "com.google.android.youtube.tvmusic",
)

private data class Category(val label: String, val query: String)

private val CATEGORIES = listOf(
    Category("Streaming", "streaming tv"),
    Category("Indian TV", "hindi tv streaming"),
    Category("Sports", "live sports"),
    Category("Music", "music"),
    Category("Games", "tv games"),
    Category("Kids", "kids tv"),
    Category("Fitness", "fitness workout tv"),
    Category("News", "news tv"),
)

@Composable
fun ShopScreen(controller: LauncherController, apps: List<AppInfo>) {
    val p = LocalPalette.current
    val stores = STORE_PACKAGES.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }
    val picks by produceState<ContentResult?>(null, controller.refreshTick) {
        value = controller.content.appPrograms("com.google.android.videos")
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Column(Modifier.padding(start = 56.dp, top = 24.dp, bottom = 8.dp)) {
                Text("Get more apps & games", style = AkoyaType.title, color = p.text)
                Text("Browse the Play Store, rent or buy films, and find something new.", style = AkoyaType.body, color = p.muted)
            }
        }
        item {
            Column {
            SectionHeader("Stores")
            LazyRow(contentPadding = ROW_PADDING, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                items(stores, key = { it.packageName }) { app ->
                    AppTile(
                        app = app,
                        width = 200.dp,
                        onClick = { controller.launch(app) },
                        onLongClick = { controller.appOptionsFor = app },
                    )
                }
            }
            }
        }
        item {
            Column {
            SectionHeader("Browse the Play Store")
            LazyRow(contentPadding = ROW_PADDING, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                itemsIndexed(CATEGORIES, key = { _, c -> c.label }) { i, cat ->
                    TvCard(
                        width = 190.dp,
                        height = 100.dp,
                        onClick = {
                            controller.apps.start(
                                Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(cat.query)}&c=apps")),
                            )
                        },
                    ) { focused ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            p.accent.copy(alpha = if (focused) 0.45f else 0.10f + (i % 3) * 0.06f),
                                            Color.Transparent,
                                        ),
                                    ),
                                ),
                        )
                        Text(
                            cat.label,
                            style = AkoyaType.title.copy(fontSize = AkoyaType.title.fontSize * 0.7f),
                            color = p.text,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(14.dp),
                        )
                    }
                }
            }
            }
        }
        val pickItems = picks?.items.orEmpty()
        if (pickItems.isNotEmpty()) {
            item {
            Column {
                SectionHeader("Picks from Google TV")
                LazyRow(contentPadding = ROW_PADDING, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    itemsIndexed(pickItems, key = { i, c -> "p$i-${c.id}" }) { _, item ->
                        ContentCard(
                            item = item,
                            width = CONTENT_WIDTH,
                            onClick = { controller.content.intentFor(item)?.let { controller.apps.start(it) } },
                        )
                    }
                }
            }
        }
        }
    }
}
