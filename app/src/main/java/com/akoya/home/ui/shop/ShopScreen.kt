package com.akoya.home.ui.shop

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.akoya.home.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.akoya.home.ui.components.TvLazyRow
import com.akoya.home.ui.components.HomeScrollSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette

private val STORE_PACKAGES = listOf(
    "com.android.vending",            // Play Store
    "com.google.android.videos",      // Google TV / Play Movies
    "com.google.android.play.games",  // Play Games
    "com.google.android.youtube.tv",
    "com.google.android.youtube.tvmusic",
)

private data class Category(val label: String, val query: String, val art: Int, val darkArt: Boolean)

private val CATEGORIES = listOf(
    Category("Streaming", "streaming tv", R.drawable.shop_streaming, true),
    Category("Indian TV", "hindi tv streaming", R.drawable.shop_indiantv, false),
    Category("Sports", "live sports", R.drawable.shop_sports, false),
    Category("Music", "music", R.drawable.shop_music, true),
    Category("Games", "tv games", R.drawable.shop_games, false),
    Category("Kids", "kids tv", R.drawable.shop_kids, false),
    Category("Fitness", "fitness workout tv", R.drawable.shop_fitness, true),
    Category("News", "news tv", R.drawable.shop_news, false),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShopScreen(controller: LauncherController, apps: List<AppInfo>) {
    val p = LocalPalette.current
    val stores = STORE_PACKAGES.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }
    val picks by produceState<ContentResult?>(null, controller.refreshTick) {
        value = controller.content.appPrograms("com.google.android.videos")
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides HomeScrollSpec) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 200.dp)) {
        item {
            Column(Modifier.padding(start = 56.dp, top = 24.dp, bottom = 8.dp)) {
                Text("Get more apps & games", style = AkoyaType.title, color = p.text)
                Text("Browse the Play Store, rent or buy films, and find something new.", style = AkoyaType.body, color = p.muted)
            }
        }
        item {
            Column {
            SectionHeader("Stores")
            TvLazyRow() {
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
            TvLazyRow() {
                itemsIndexed(CATEGORIES, key = { _, c -> c.label }) { _, cat ->
                    TvCard(
                        width = 190.dp,
                        height = 100.dp,
                        onClick = {
                            controller.apps.start(
                                Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(cat.query)}&c=apps")),
                            )
                        },
                    ) {
                        Image(
                            painter = painterResource(cat.art),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Text(
                            cat.label.uppercase(),
                            style = AkoyaType.section,
                            color = if (cat.darkArt) Color(0xFFF1EEE8) else Color(0xFF141311),
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
                TvLazyRow() {
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
}
