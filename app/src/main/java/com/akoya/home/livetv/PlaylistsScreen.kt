package com.akoya.home.livetv

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.akoya.home.data.ConfigStore
import com.akoya.home.data.LauncherConfig
import com.akoya.home.server.CompanionServer
import com.akoya.home.ui.components.AkoyaDialog
import com.akoya.home.ui.components.Backdrop
import com.akoya.home.ui.components.DialogOption
import com.akoya.home.ui.components.OptionsDialog
import com.akoya.home.ui.components.TvButton
import com.akoya.home.ui.components.qrBitmap
import com.akoya.home.ui.components.rememberInitialFocus
import com.akoya.home.ui.settings.SettingRow
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class AddForm { LINK, XTREAM }

/** Where channel sources are added, refreshed, switched off and removed. Also the first-run screen. */
@Composable
fun PlaylistsScreen(
    session: LiveTvSession,
    config: LauncherConfig,
    configStore: ConfigStore,
    hasChannels: Boolean,
    onExit: () -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val store = session.store
    val state by store.state.collectAsState()
    val loading by store.loading.collectAsState()
    val errors by store.errors.collectAsState()
    val guideErrors by store.guideErrors.collectAsState()
    val guideLoading by store.guide.loading.collectAsState()
    var guideFor by remember { mutableStateOf<Playlist?>(null) }
    // Set while the add form is being used to swap an existing playlist's provider.
    var replaceId by remember { mutableStateOf<String?>(null) }
    val notice by store.notice.collectAsState()
    var optionsFor by remember { mutableStateOf<Playlist?>(null) }
    var form by remember { mutableStateOf<AddForm?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { CompanionServer.ensureStarted(ctx.applicationContext) }
        delay(120)
        runCatching { first.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(p.background)) {
        Backdrop(config, configStore)
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 34.dp, bottom = 24.dp)) {
            Column(Modifier.weight(1f)) {
                Text("LIVE TV", style = AkoyaType.section, color = p.accent)
                Text("Playlists", style = AkoyaType.title.copy(fontSize = AkoyaType.title.fontSize * 1.3f), color = p.text)
                Text(
                    if (state.playlists.isEmpty()) "Add a channel source to start watching. Free public lists are one press away."
                    else "Channels from every playlist that's switched on appear together in the channel list.",
                    style = AkoyaType.body, color = p.muted,
                )
                Spacer(Modifier.height(16.dp))
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 30.dp, start = 4.dp, end = 4.dp),
                ) {
                    if (hasChannels) item(key = "__watch") {
                        Row { TvButton("Watch live TV", icon = Icons.Filled.PlayArrow, selected = true, focusRequester = first, onClick = { session.panel = Panel.NONE }) }
                    }
                    items(state.playlists, key = { it.id }) { pl ->
                        val err = errors[pl.id]
                        val busy = pl.id in loading
                        SettingRow(
                            title = pl.name,
                            subtitle = when {
                                busy -> "Downloading channels…"
                                err != null && pl.channelCount > 0 -> "Couldn't update: $err. Showing the last saved ${pl.channelCount} channels"
                                err != null -> err
                                pl.channelCount > 0 -> "${pl.channelCount} channels  ·  updated ${ago(pl.updatedAt)}" + when {
                                    pl.id in guideLoading -> "  ·  downloading guide…"
                                    guideErrors[pl.id] != null -> "  ·  guide: ${guideErrors[pl.id]}"
                                    pl.guideCount > 0 -> "  ·  guide: ${pl.guideCount} programmes"
                                    pl.guideUrls().isEmpty() -> "  ·  no guide link"
                                    else -> "  ·  guide not loaded yet"
                                }
                                else -> "Not loaded yet"
                            },
                            value = if (pl.enabled) "On" else "Off",
                            valueColor = if (err != null && !busy) Color(0xFFE0705A) else if (pl.enabled) null else p.muted,
                            onClick = { optionsFor = pl },
                        )
                    }
                    item(key = "__afr") {
                        Column {
                            Text("PICTURE", style = AkoyaType.section, color = p.muted, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp, start = 4.dp))
                            SettingRow(
                                title = "Auto frame rate",
                                subtitle = "Switches the screen to suit each channel (50 Hz for Indian channels, 60 Hz for American). The picture blanks for a moment when it changes.",
                                value = if (state.autoFrameRate) "On" else "Off",
                                onClick = { store.setAutoFrameRate(!state.autoFrameRate) },
                            )
                        }
                    }
                    item(key = "__add") {
                        Column {
                            Text("ADD", style = AkoyaType.section, color = p.muted, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp, start = 4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val missing = PLAYLIST_PRESETS.filter { pre -> state.playlists.none { it.url == pre.url } }
                                missing.forEachIndexed { i, pre ->
                                    TvButton(
                                        pre.name, icon = Icons.Filled.Add,
                                        focusRequester = if (i == 0 && !hasChannels) first else null,
                                        onClick = { store.addPreset(pre.id) },
                                    )
                                }
                                TvButton(
                                    "Playlist link", icon = Icons.Filled.Link,
                                    focusRequester = if (missing.isEmpty() && !hasChannels) first else null,
                                    onClick = { form = AddForm.LINK },
                                )
                                TvButton("Xtream login", icon = Icons.Filled.VpnKey, onClick = { form = AddForm.XTREAM })
                            }
                            notice?.let { Text(it, style = AkoyaType.small, color = p.accent, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
                            message?.let { Text(it, style = AkoyaType.small, color = Color(0xFFE0705A), modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
                        }
                    }
                }
            }
            Spacer(Modifier.width(36.dp))
            PhoneCard(config)
        }
    }

    optionsFor?.let { pl ->
        OptionsDialog(
            title = pl.name,
            subtitle = if (pl.kind == PlaylistKind.XTREAM) "${pl.url}  ·  ${pl.username}" else pl.url.take(70),
            onDismiss = { optionsFor = null },
            options = listOf(
                DialogOption("Update channels and guide now", Icons.Filled.Refresh) { store.refreshAsync(pl.id) },
                DialogOption(if (pl.guideUrl.isBlank()) "Set a guide link" else "Change the guide link", Icons.Filled.Link) { guideFor = pl },
                DialogOption(if (pl.enabled) "Switch off" else "Switch on", if (pl.enabled) Icons.Filled.VisibilityOff else Icons.Filled.Visibility) { store.setEnabled(pl.id, !pl.enabled) },
                DialogOption("Check which channels work", Icons.Filled.Refresh) {
                    session.checkChannels(pl.name, session.index.channels("p:" + pl.id, emptyList()).ifEmpty { session.index.all.filter { it.playlistId == pl.id } })
                    session.panel = Panel.MANAGE
                },
                DialogOption("Replace the source with a playlist link", Icons.Filled.Link) { replaceId = pl.id; form = AddForm.LINK },
                DialogOption("Replace the source with an Xtream login", Icons.Filled.VpnKey) { replaceId = pl.id; form = AddForm.XTREAM },
                DialogOption("Remove", Icons.Filled.Delete) { store.remove(pl.id) },
            ),
        )
    }

    guideFor?.let { pl ->
        FieldsDialog(
            title = "Guide link for ${pl.name}",
            subtitle = "An XMLTV link (.xml or .xml.gz) from your provider. Leave it empty to use the one the playlist names, if any.",
            fields = listOf(Field("Guide link (http…)", KeyboardType.Uri, pl.guideUrl)),
            onDismiss = { guideFor = null },
            onDone = { v -> message = runCatching { store.setGuideUrl(pl.id, v[0]) }.exceptionOrNull()?.message },
        )
    }

    when (form) {
        AddForm.LINK -> FieldsDialog(
            title = "Add a playlist link",
            subtitle = "The M3U link from your provider. Typing is easier from the phone page.",
            fields = listOf(Field("Link (http…)", KeyboardType.Uri), Field("Name (optional)")),
            onDismiss = { form = null; replaceId = null },
            onDone = { v ->
                val target = replaceId
                message = runCatching { if (target != null) store.replaceSource(target, v[1], v[0], "", "", "") else store.addM3u(v[1], v[0]) }.exceptionOrNull()?.message
            },
        )
        AddForm.XTREAM -> FieldsDialog(
            title = "Add an Xtream login",
            subtitle = "The server address, username and password from your provider.",
            fields = listOf(Field("Server (http://host:port)", KeyboardType.Uri), Field("Username"), Field("Password"), Field("Name (optional)")),
            onDismiss = { form = null; replaceId = null },
            onDone = { v ->
                val target = replaceId
                message = runCatching { if (target != null) store.replaceSource(target, v[3], "", v[0], v[1], v[2]) else store.addXtream(v[3], v[0], v[1], v[2]) }.exceptionOrNull()?.message
            },
        )
        null -> Unit
    }
}

@Composable
private fun PhoneCard(config: LauncherConfig) {
    val p = LocalPalette.current
    val url = CompanionServer.url()
    val qr = remember(url, config.companionPin) { url?.let { qrBitmap("$it/?pin=${config.companionPin}") } }
    Column(
        Modifier
            .width(250.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(p.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("ADD FROM YOUR PHONE", style = AkoyaType.section, color = p.accent)
        if (qr != null) Image(qr, contentDescription = "QR code", modifier = Modifier.padding(vertical = 6.dp).size(150.dp).clip(RoundedCornerShape(12.dp)))
        Text(url ?: "Not on Wi-Fi", style = AkoyaType.label, color = p.text)
        Text("PIN  ${config.companionPin.ifBlank { "…" }}", style = AkoyaType.label, color = p.text)
        Text("Scan, open the TV tab, and paste the link under Live TV playlists.", style = AkoyaType.small, color = p.muted)
    }
}

private fun ago(time: Long): String {
    if (time <= 0) return "never"
    val mins = (System.currentTimeMillis() - time) / 60_000
    return when {
        mins < 2 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 48 * 60 -> "${mins / 60} h ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
    }
}

internal data class Field(val label: String, val keyboard: KeyboardType = KeyboardType.Text, val initial: String = "")

/** A small form: one text box per field, Save and Cancel. */
@Composable
internal fun FieldsDialog(
    title: String,
    subtitle: String,
    fields: List<Field>,
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val values = remember { mutableStateListOf<String>().apply { fields.forEach { add(it.initial) } } }
    val submit = { onDone(values.map { it.trim() }); onDismiss() }
    AkoyaDialog(title = title, subtitle = subtitle, onDismiss = onDismiss, wide = true) {
        val fr = rememberInitialFocus()
        fields.forEachIndexed { i, f ->
            OutlinedTextField(
                value = values[i],
                onValueChange = { values[i] = it.take(600) },
                label = { Text(f.label, style = AkoyaType.small) },
                singleLine = true,
                textStyle = AkoyaType.body.copy(color = p.text),
                keyboardOptions = KeyboardOptions(keyboardType = f.keyboard, imeAction = if (i == fields.lastIndex) ImeAction.Done else ImeAction.Next),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = p.accent,
                    unfocusedBorderColor = p.muted,
                    cursorColor = p.accent,
                    focusedLabelColor = p.accent,
                    unfocusedLabelColor = p.muted,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (i == 0) Modifier.focusRequester(fr) else Modifier),
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("Save", onClick = submit)
            TvButton("Cancel", onClick = onDismiss)
        }
    }
}
