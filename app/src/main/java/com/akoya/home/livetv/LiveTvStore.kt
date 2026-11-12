package com.akoya.home.livetv

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

enum class PlaylistKind { M3U, XTREAM }

/** A provider account that offers the Xtream API (films, series, guide) alongside live channels. */
class XtreamAccount(val playlistId: String, val name: String, val base: String, val user: String, val pass: String) {
    private val u = URLEncoder.encode(user, "UTF-8")
    private val p = URLEncoder.encode(pass, "UTF-8")
    fun api(action: String) = "$base/player_api.php?username=$u&password=$p&action=$action"
    fun movieUrl(id: String, ext: String) = "$base/movie/$u/$p/$id.${ext.ifBlank { "mp4" }}"
    fun episodeUrl(id: String, ext: String) = "$base/series/$u/$p/$id.${ext.ifBlank { "mp4" }}"
}

/** Progress of a channel health check. */
data class HealthRun(val label: String, val done: Int, val total: Int, val bad: Int, val running: Boolean)

/**
 * A channel source. For [PlaylistKind.M3U], [url] is the playlist link.
 * For [PlaylistKind.XTREAM], [url] is the server address and the login is used with it.
 */
data class Playlist(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val kind: PlaylistKind,
    val url: String,
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = true,
    val channelCount: Int = 0,
    val updatedAt: Long = 0L,
    /** Guide (XMLTV) link the user typed in; wins over the one the playlist names. */
    val guideUrl: String = "",
    /** Guide links found in the playlist itself, separated by spaces. */
    val guideDetected: String = "",
    val guideUpdatedAt: Long = 0L,
    val guideCount: Int = 0,
    /** The provider's time zone (Xtream catch-up links use the server's clock). */
    val timezone: String = "",
) {
    /** Where this playlist's guide comes from; empty when it has none. */
    fun guideUrls(): List<String> = when {
        guideUrl.isNotBlank() -> listOf(guideUrl)
        guideDetected.isNotBlank() -> guideDetected.split(' ').filter { it.isNotBlank() }
        kind == PlaylistKind.XTREAM ->
            listOf("$url/xmltv.php?username=${URLEncoder.encode(username, "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}")
        else -> emptyList()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind.name).put("url", url)
        .put("username", username).put("password", password).put("enabled", enabled)
        .put("channelCount", channelCount).put("updatedAt", updatedAt)
        .put("guideUrl", guideUrl).put("guideDetected", guideDetected)
        .put("guideUpdatedAt", guideUpdatedAt).put("guideCount", guideCount).put("timezone", timezone)

    companion object {
        fun fromJson(o: JSONObject): Playlist? {
            val url = o.optString("url")
            if (url.isBlank()) return null
            return Playlist(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                name = o.optString("name").ifBlank { "Playlist" },
                kind = runCatching { PlaylistKind.valueOf(o.optString("kind")) }.getOrDefault(PlaylistKind.M3U),
                url = url,
                username = o.optString("username"),
                password = o.optString("password"),
                enabled = o.optBoolean("enabled", true),
                channelCount = o.optInt("channelCount", 0),
                updatedAt = o.optLong("updatedAt", 0L),
                guideUrl = o.optString("guideUrl"),
                guideDetected = o.optString("guideDetected"),
                guideUpdatedAt = o.optLong("guideUpdatedAt", 0L),
                guideCount = o.optInt("guideCount", 0),
                timezone = o.optString("timezone"),
            )
        }
    }
}

data class LiveTvState(
    val playlists: List<Playlist> = emptyList(),
    /** Channel ids, in the order the user arranged them. */
    val favorites: List<String> = emptyList(),
    val lastChannelId: String? = null,
    /** Channel id to how many times in a row it failed to play. */
    val failing: Map<String, Int> = emptyMap(),
    /** Switch the screen's refresh rate to suit each channel (25 fps on 50 Hz, and so on). */
    val autoFrameRate: Boolean = true,
    // ---- channel management
    val renames: Map<String, String> = emptyMap(),
    val hiddenChannels: Set<String> = emptySet(),
    val hiddenGroups: Set<String> = emptySet(),
    val groupNames: Map<String, String> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val sortAz: Boolean = false,
    /** Leave channels that failed twice running out of the lists altogether. */
    val hideOffAir: Boolean = false,
    /** Highest picture height to play (0 = whatever the connection manages). */
    val maxHeight: Int = 0,
) {
    fun prefs(): ChannelPrefs = ChannelPrefs(
        renames, hiddenChannels, hiddenGroups, groupNames, categories, sortAz,
        offAir = if (hideOffAir) failing.filterValues { it >= 2 }.keys else emptySet(),
    )

    /** Failed twice running: dimmed in the list and skipped when zapping. */
    fun isOffAir(channelId: String) = (failing[channelId] ?: 0) >= 2
}

/** Free, publicly listed channels (the iptv-org community index). */
data class PlaylistPreset(val id: String, val name: String, val url: String)

val PLAYLIST_PRESETS = listOf(
    PlaylistPreset("in", "India (free)", "https://iptv-org.github.io/iptv/countries/in.m3u"),
    PlaylistPreset("us", "USA (free)", "https://iptv-org.github.io/iptv/countries/us.m3u"),
)

/**
 * Playlists, the channels loaded from them, favourites and the last channel watched.
 * Channels are cached on the TV so Live TV opens instantly; a playlist is re-downloaded
 * when it is more than a day old or when the user asks.
 */
class LiveTvStore private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("akoya_livetv", Context.MODE_PRIVATE)
    // Downloads, parsing and the guide database run on a few low-priority threads, so they
    // never take the processor away from the picture or the remote.
    private val scope = CoroutineScope(SupervisorJob() + Background.dispatcher)
    private val cacheDir = File(context.filesDir, "livetv").apply { mkdirs() }
    val guide = EpgStore.get(context)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    /** Every channel from every playlist that is switched on, in playlist order. */
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    /** The same channels arranged for the channel list. Rebuilt off the main thread. */
    private val _index = MutableStateFlow(ChannelIndex(emptyList(), emptyList()))
    val index: StateFlow<ChannelIndex> = _index.asStateFlow()

    /** Playlist ids being downloaded right now. */
    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    val loading: StateFlow<Set<String>> = _loading.asStateFlow()

    /** Last download problem per playlist id. */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    /** False until the cached channels have been read from storage. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Last guide download problem per playlist id. */
    private val _guideErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val guideErrors: StateFlow<Map<String, String>> = _guideErrors.asStateFlow()

    private val byPlaylist = HashMap<String, List<Channel>>()
    private val lock = Any()

    init {
        scope.launch {
            for (p in _state.value.playlists) {
                val cached = readCache(p.id)
                synchronized(lock) { byPlaylist[p.id] = cached }
            }
            publish()
            guide.load()
            _ready.value = true
            // Let the launcher and the first channel settle before any downloading starts.
            delay(20_000)
            // Channel files saved by an older version don't know about catch-up: fetch them again.
            if (prefs.getInt(KEY_FORMAT, 1) < CACHE_FORMAT) {
                prefs.edit().putInt(KEY_FORMAT, CACHE_FORMAT).apply()
                refreshAll()
            } else {
                refreshStale()
            }
        }
    }

    val current: LiveTvState get() = _state.value

    // ------------------------------------------------------------------ playlists

    fun addM3u(name: String, url: String): Playlist {
        val clean = url.trim()
        require(clean.startsWith("http://", true) || clean.startsWith("https://", true)) { "The link must start with http:// or https://" }
        current.playlists.firstOrNull { it.kind == PlaylistKind.M3U && it.url == clean }?.let { refreshAsync(it.id); return it }
        val p = Playlist(name = name.trim().ifBlank { hostOf(clean) }, kind = PlaylistKind.M3U, url = clean)
        return add(p)
    }

    fun addXtream(name: String, server: String, username: String, password: String): Playlist {
        var base = server.trim().trimEnd('/')
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) base = "http://$base"
        // People often paste the whole link; keep only scheme://host:port.
        runCatching { URL(base) }.getOrNull()?.let { u ->
            base = u.protocol + "://" + u.host + (if (u.port > 0) ":" + u.port else "")
        }
        require(username.isNotBlank() && password.isNotBlank()) { "Username and password are needed" }
        val p = Playlist(
            name = name.trim().ifBlank { hostOf(base) },
            kind = PlaylistKind.XTREAM,
            url = base,
            username = username.trim(),
            password = password.trim(),
        )
        return add(p)
    }

    fun addPreset(id: String): Playlist? =
        PLAYLIST_PRESETS.firstOrNull { it.id == id }?.let { addM3u(it.name, it.url) }

    private fun add(p: Playlist): Playlist {
        updateState { it.copy(playlists = it.playlists + p) }
        refreshAsync(p.id)
        return p
    }

    fun remove(id: String) {
        updateState { s -> s.copy(playlists = s.playlists.filterNot { it.id == id }) }
        synchronized(lock) { byPlaylist.remove(id) }
        _errors.update { it - id }
        _guideErrors.update { it - id }
        File(cacheDir, "$id.tsv").delete()
        publish()
        scope.launch { guide.remove(id) }
    }

    /** Sets (or clears) the guide link for a playlist and downloads it. */
    fun setGuideUrl(id: String, url: String) {
        val clean = url.trim()
        require(clean.isEmpty() || clean.startsWith("http://", true) || clean.startsWith("https://", true)) { "The guide link must start with http:// or https://" }
        updateState { s -> s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(guideUrl = clean, guideUpdatedAt = 0L) else it }) }
        refreshGuideAsync(id)
    }

    fun setAutoFrameRate(on: Boolean) = updateState { it.copy(autoFrameRate = on) }

    fun refreshGuideAsync(id: String) {
        scope.launch { refreshGuide(id) }
    }

    /** Downloads one playlist's guide. Needs the channels loaded first, to know what to keep. */
    private fun refreshGuide(id: String) {
        val p = current.playlists.firstOrNull { it.id == id } ?: return
        val urls = p.guideUrls()
        if (urls.isEmpty() || !p.enabled) return
        // Only channels that are shown need a guide: with most groups hidden this keeps the
        // download quick and the database small.
        val all = _index.value.all
        if (all.isEmpty()) return
        val ids = HashSet<String>()
        val names = HashSet<String>()
        var catchup = false
        for (c in all) {
            if (c.tvgId.isNotBlank()) { val t = c.tvgId.lowercase(); ids += t; ids += t.substringBefore('@') }
            ChannelIndex.nameKey(c.name).let { if (it.length >= 3) names += it }
            if (c.hasCatchup) catchup = true
        }
        val past = if (catchup) GUIDE_PAST_CATCHUP_MS else GUIDE_PAST_MS
        guide.refresh(id, urls, GuideWanted(ids, names), past, GUIDE_FUTURE_MS).fold(
            onSuccess = { count ->
                _guideErrors.update { it - id }
                if (count > 0) updateState { s ->
                    s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(guideCount = count, guideUpdatedAt = System.currentTimeMillis()) else it })
                }
            },
            onFailure = { e -> _guideErrors.update { it + (id to friendly(e)) } },
        )
    }

    fun setEnabled(id: String, enabled: Boolean) {
        updateState { s -> s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
        publish()
    }

    fun refreshAsync(id: String) {
        scope.launch { refresh(id) }
    }

    fun refreshAll() {
        current.playlists.filter { it.enabled }.forEach { refreshAsync(it.id) }
    }

    /** Re-downloads playlists that are more than a day old. */
    fun refreshStale() {
        val now = System.currentTimeMillis()
        current.playlists.filter { it.enabled }.forEach { p ->
            when {
                // A playlist refresh fetches the guide afterwards as well.
                p.updatedAt < now - STALE_MS -> refreshAsync(p.id)
                p.guideUpdatedAt < now - GUIDE_STALE_MS && p.guideUrls().isNotEmpty() -> refreshGuideAsync(p.id)
            }
        }
    }

    /** Downloads one playlist again. Keeps the old channels if the download fails. */
    suspend fun refresh(id: String): Result<Int> = withContext(Background.dispatcher) {
        val p = current.playlists.firstOrNull { it.id == id } ?: return@withContext Result.failure(IllegalArgumentException("No such playlist"))
        if (!_loading.compareAndAdd(id)) return@withContext Result.success(p.channelCount)
        val result = runCatching {
            var detected = p.guideDetected
            var zone = p.timezone
            val list = when (p.kind) {
                PlaylistKind.M3U -> downloadM3u(p).also { detected = it.epgUrls.joinToString(" ") }.channels
                PlaylistKind.XTREAM -> { zone = xtreamTimezone(p).ifBlank { zone }; downloadXtream(p) }
            }
            if (list.isEmpty()) throw IllegalStateException("No channels found in this playlist")
            writeCache(id, list)
            synchronized(lock) { byPlaylist[id] = list }
            updateState { s ->
                s.copy(playlists = s.playlists.map {
                    if (it.id == id) it.copy(channelCount = list.size, updatedAt = System.currentTimeMillis(), guideDetected = detected, timezone = zone) else it
                })
            }
            publish()
            list.size
        }
        if (result.isSuccess) {
            // New channels may match guides that other playlists brought, so those look again too.
            val first = p.updatedAt == 0L
            current.playlists.filter { it.enabled && (it.id == id || first) && it.guideUrls().isNotEmpty() }.forEach { refreshGuideAsync(it.id) }
        }
        result.fold(
            onSuccess = { _errors.update { it - id } },
            onFailure = { e ->
                Log.w(TAG, "Playlist ${p.name} failed", e)
                _errors.update { it + (id to friendly(e)) }
            },
        )
        _loading.update { it - id }
        result
    }

    // ------------------------------------------------------------------ favourites / last channel

    fun isFavorite(channelId: String) = channelId in current.favorites

    fun toggleFavorite(channelId: String) = updateState {
        if (channelId in it.favorites) it.copy(favorites = it.favorites - channelId)
        else it.copy(favorites = it.favorites + channelId)
    }

    fun moveFavorite(channelId: String, delta: Int) = updateState {
        val list = it.favorites.toMutableList()
        val i = list.indexOf(channelId)
        val j = i + delta
        if (i < 0 || j !in list.indices) return@updateState it
        list.removeAt(i); list.add(j, channelId)
        it.copy(favorites = list)
    }

    fun setLastChannel(channelId: String) {
        if (current.lastChannelId != channelId) updateState { it.copy(lastChannelId = channelId) }
    }

    /** Remembers whether a channel played, so dead ones can be dimmed and skipped. */
    fun reportPlayback(channelId: String, played: Boolean) {
        val count = current.failing[channelId] ?: 0
        if (played) {
            if (count > 0) updateState { it.copy(failing = it.failing - channelId) }
        } else if (count < 9) {
            updateState { it.copy(failing = it.failing + (channelId to count + 1)) }
        }
    }

    /**
     * Logins that can also list films and series. An Xtream login can; so can an M3U link of
     * the usual "get.php?username=…&password=…" kind, which is the same account underneath.
     */
    fun xtreamAccounts(): List<XtreamAccount> = current.playlists.filter { it.enabled }.mapNotNull { p ->
        when {
            p.kind == PlaylistKind.XTREAM -> XtreamAccount(p.id, p.name, p.url, p.username, p.password)
            "username=" in p.url && "password=" in p.url -> runCatching {
                val u = Uri.parse(p.url)
                val user = u.getQueryParameter("username").orEmpty()
                val pass = u.getQueryParameter("password").orEmpty()
                if (user.isBlank() || pass.isBlank() || u.host.isNullOrBlank()) null
                else XtreamAccount(p.id, p.name, u.scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else ""), user, pass)
            }.getOrNull()
            else -> null
        }
    }

    // ------------------------------------------------------------------ channel management

    /** Rearranges the channel list after a management change (off the main thread). */
    private fun rearrange() { scope.launch { publish() } }

    private fun manage(transform: (LiveTvState) -> LiveTvState) { updateState(transform); rearrange() }

    /** A blank name puts the provider's name back. */
    fun renameChannel(id: String, name: String) = manage {
        val n = name.trim()
        it.copy(renames = if (n.isEmpty()) it.renames - id else it.renames + (id to n.take(80)))
    }

    fun hideChannel(id: String) = manage { s ->
        s.copy(hiddenChannels = s.hiddenChannels + id, favorites = s.favorites - id, categories = s.categories.map { c -> c.copy(channels = c.channels - id) })
    }

    fun showAllChannels() = manage { it.copy(hiddenChannels = emptySet()) }

    fun setGroupsHidden(keys: Collection<String>, hidden: Boolean) {
        manage { it.copy(hiddenGroups = if (hidden) it.hiddenGroups + keys else it.hiddenGroups - keys.toSet()) }
        if (!hidden) guideSoon()
    }

    private var guideSoonJob: kotlinx.coroutines.Job? = null

    /** Newly shown channels have no guide yet: fetch it once the user has stopped switching groups on. */
    private fun guideSoon() {
        synchronized(lock) {
            guideSoonJob?.cancel()
            guideSoonJob = scope.launch {
                delay(45_000)
                current.playlists.filter { it.enabled && it.guideUrls().isNotEmpty() }.forEach { refreshGuide(it.id) }
            }
        }
    }

    fun renameGroup(key: String, name: String) = manage {
        val n = name.trim()
        if (key.startsWith("c:")) it.copy(categories = it.categories.map { c -> if ("c:" + c.id == key && n.isNotEmpty()) c.copy(name = n.take(40)) else c })
        else it.copy(groupNames = if (n.isEmpty()) it.groupNames - key else it.groupNames + (key to n.take(40)))
    }

    /** Makes a category and returns its group key. */
    fun addCategory(name: String, firstChannel: String? = null): String {
        val cat = Category(UUID.randomUUID().toString().take(8), name.trim().take(40).ifBlank { "My channels" }, listOfNotNull(firstChannel))
        manage { it.copy(categories = it.categories + cat) }
        return "c:" + cat.id
    }

    fun deleteCategory(key: String) = manage { it.copy(categories = it.categories.filterNot { c -> "c:" + c.id == key }) }

    fun moveCategory(key: String, delta: Int) = manage {
        val list = it.categories.toMutableList()
        val i = list.indexOfFirst { c -> "c:" + c.id == key }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@manage it
        val c = list.removeAt(i); list.add(j, c)
        it.copy(categories = list)
    }

    fun setInCategory(key: String, channelId: String, member: Boolean) = manage {
        it.copy(categories = it.categories.map { c ->
            when {
                "c:" + c.id != key -> c
                member -> if (channelId in c.channels) c else c.copy(channels = c.channels + channelId)
                else -> c.copy(channels = c.channels - channelId)
            }
        })
    }

    fun moveInCategory(key: String, channelId: String, delta: Int) = manage {
        it.copy(categories = it.categories.map { c ->
            if ("c:" + c.id != key) return@map c
            val list = c.channels.toMutableList()
            val i = list.indexOf(channelId)
            val j = i + delta
            if (i < 0 || j !in list.indices) return@map c
            list.removeAt(i); list.add(j, channelId)
            c.copy(channels = list)
        })
    }

    fun setSortAz(on: Boolean) = manage { it.copy(sortAz = on) }
    fun setHideOffAir(on: Boolean) = manage { it.copy(hideOffAir = on) }
    fun setMaxHeight(h: Int) = updateState { it.copy(maxHeight = h) }

    // ------------------------------------------------------------------ channel health check

    /** Progress of a "which channels work?" run; null when none has been started. */
    private val _health = MutableStateFlow<HealthRun?>(null)
    val health: StateFlow<HealthRun?> = _health.asStateFlow()
    private var healthJob: kotlinx.coroutines.Job? = null

    /** A playlist that comes from a paid login usually allows one connection at a time. */
    fun isSingleConnection(playlistId: String): Boolean {
        val p = current.playlists.firstOrNull { it.id == playlistId } ?: return false
        return p.kind == PlaylistKind.XTREAM || "username=" in p.url
    }

    /**
     * Tries each channel's link and marks the ones that don't answer as not working.
     * [gentle] checks one at a time with a pause (for providers that allow one connection);
     * otherwise four are checked at once.
     */
    fun checkChannels(label: String, channels: List<Channel>, gentle: Boolean) {
        healthJob?.cancel()
        if (channels.isEmpty()) return
        _health.value = HealthRun(label, 0, channels.size, 0, running = true)
        healthJob = scope.launch {
            val results = HashMap<String, Boolean>()
            var done = 0
            var bad = 0
            fun flush() {
                if (results.isEmpty()) return
                val batch = HashMap(results); results.clear()
                updateState { s ->
                    val f = s.failing.toMutableMap()
                    for ((id, ok) in batch) if (ok) f.remove(id) else f[id] = maxOf(f[id] ?: 0, 2)
                    s.copy(failing = f)
                }
            }
            try {
                for (chunk in channels.chunked(if (gentle) 1 else 4)) {
                    val answers = chunk.map { ch -> async { ch.id to probe(ch) } }.awaitAll()
                    for ((id, ok) in answers) { results[id] = ok; done++; if (!ok) bad++ }
                    _health.value = HealthRun(label, done, channels.size, bad, running = true)
                    if (results.size >= 24) flush()
                    if (gentle) delay(150)
                }
            } finally {
                flush()
                _health.value = HealthRun(label, done, channels.size, bad, running = false)
                if (current.hideOffAir) publish()
            }
        }
    }

    fun stopCheck() { healthJob?.cancel() }

    /** Forgets every "not working" mark (for instance after the provider fixed an outage). */
    fun clearOffAir() = manage { it.copy(failing = emptyMap()) }

    /** True when the link answers and sends at least a little data. */
    private fun probe(ch: Channel): Boolean = runCatching {
        var next = ch.url
        repeat(5) {
            val c = URL(next).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6_000
                c.readTimeout = 7_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", ch.userAgent.ifBlank { PLAYLIST_AGENT })
                if (ch.referrer.isNotBlank()) c.setRequestProperty("Referer", ch.referrer)
                val code = c.responseCode
                when {
                    code in 300..399 -> next = URL(URL(next), c.getHeaderField("Location") ?: return@runCatching false).toString()
                    code in 200..299 -> return@runCatching c.inputStream.use { it.read(ByteArray(512)) > 0 }
                    else -> return@runCatching false
                }
            } finally {
                c.disconnect()
            }
        }
        false
    }.getOrDefault(false)

    /** The clock a channel's provider keeps, for catch-up links that use server time. */
    fun zoneFor(ch: Channel): java.time.ZoneId {
        val tz = current.playlists.firstOrNull { it.id == ch.playlistId }?.timezone.orEmpty()
        return runCatching { if (tz.isBlank()) java.time.ZoneOffset.UTC else java.time.ZoneId.of(tz) }.getOrDefault(java.time.ZoneOffset.UTC)
    }

    /** Favourite channels that still exist, in the user's order. */
    fun favoriteChannels(all: List<Channel>, favorites: List<String>): List<Channel> {
        if (favorites.isEmpty()) return emptyList()
        val byId = all.associateBy { it.id }
        return favorites.mapNotNull { byId[it] }
    }

    // ------------------------------------------------------------------ internals

    private fun publish() {
        val order = current.playlists.filter { it.enabled }.map { it.id }
        val merged = synchronized(lock) { order.flatMap { byPlaylist[it].orEmpty() } }
        _index.value = ChannelIndex(merged, current.playlists, current.prefs())
        _channels.value = merged
    }

    private var saveJob: kotlinx.coroutines.Job? = null

    /**
     * Changes take effect at once; writing them to storage happens a moment later, off the
     * main thread, and several quick changes (zapping, a health check) become one write.
     */
    private fun updateState(transform: (LiveTvState) -> LiveTvState) {
        _state.updateAndGet(transform)
        synchronized(prefs) {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(400)
                prefs.edit().putString(KEY, toJson(_state.value).toString()).apply()
            }
        }
    }

    private fun loadState(): LiveTvState {
        val raw = prefs.getString(KEY, null) ?: return LiveTvState()
        return runCatching {
            val o = JSONObject(raw)
            val lists = o.optJSONArray("playlists")
            val favs = o.optJSONArray("favorites")
            LiveTvState(
                playlists = (0 until (lists?.length() ?: 0)).mapNotNull { i -> lists?.optJSONObject(i)?.let(Playlist::fromJson) },
                favorites = (0 until (favs?.length() ?: 0)).mapNotNull { i -> favs?.optString(i)?.ifBlank { null } },
                lastChannelId = o.optString("lastChannelId").ifBlank { null },
                autoFrameRate = o.optBoolean("autoFrameRate", true),
                renames = o.optJSONObject("renames").toStringMap(),
                hiddenChannels = o.optJSONArray("hiddenChannels").toStringSet(),
                hiddenGroups = o.optJSONArray("hiddenGroups").toStringSet(),
                groupNames = o.optJSONObject("groupNames").toStringMap(),
                categories = o.optJSONArray("categories")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { c -> Category(c.optString("id"), c.optString("name"), c.optJSONArray("channels").toStringSet().toList()) }
                    }.filter { it.id.isNotBlank() }
                }.orEmpty(),
                sortAz = o.optBoolean("sortAz", false),
                hideOffAir = o.optBoolean("hideOffAir", false),
                maxHeight = o.optInt("maxHeight", 0),
                failing = o.optJSONObject("failing")?.let { f -> f.keys().asSequence().associateWith { k -> f.optInt(k) } }.orEmpty(),
            )
        }.getOrDefault(LiveTvState())
    }

    private fun toJson(s: LiveTvState): JSONObject = JSONObject()
        .put("playlists", JSONArray().also { arr -> s.playlists.forEach { arr.put(it.toJson()) } })
        .put("favorites", JSONArray(s.favorites))
        .put("lastChannelId", s.lastChannelId ?: "")
        .put("autoFrameRate", s.autoFrameRate)
        .put("renames", JSONObject(s.renames))
        .put("hiddenChannels", JSONArray(s.hiddenChannels.toList()))
        .put("hiddenGroups", JSONArray(s.hiddenGroups.toList()))
        .put("groupNames", JSONObject(s.groupNames))
        .put("categories", JSONArray().also { arr ->
            s.categories.forEach { c -> arr.put(JSONObject().put("id", c.id).put("name", c.name).put("channels", JSONArray(c.channels))) }
        })
        .put("sortAz", s.sortAz)
        .put("hideOffAir", s.hideOffAir)
        .put("maxHeight", s.maxHeight)
        .put("failing", JSONObject().also { o -> s.failing.forEach { (k, v) -> o.put(k, v) } })

    private fun downloadM3u(p: Playlist): ParsedPlaylist =
        open(p.url).use { input -> input.bufferedReader().useLines { M3uParser.parsePlaylist(it, p.id) } }

    /** The provider's time zone, from the Xtream account call. Empty if it doesn't say. */
    private fun xtreamTimezone(p: Playlist): String = runCatching {
        val user = URLEncoder.encode(p.username, "UTF-8")
        val pass = URLEncoder.encode(p.password, "UTF-8")
        var zone = ""
        open("${p.url}/player_api.php?username=$user&password=$pass").use { input ->
            JsonReader(input.reader()).use { r ->
                if (r.peek() != JsonToken.BEGIN_OBJECT) return@use
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "server_info" && r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject()
                        while (r.hasNext()) {
                            if (r.nextName() == "timezone") zone = r.nextStringOrEmpty() else r.skipValue()
                        }
                        r.endObject()
                    } else r.skipValue()
                }
                r.endObject()
            }
        }
        zone
    }.getOrDefault("")

    /** Xtream Codes "player API": categories first (for group names), then the live channels. */
    private fun downloadXtream(p: Playlist): List<Channel> {
        val user = URLEncoder.encode(p.username, "UTF-8")
        val pass = URLEncoder.encode(p.password, "UTF-8")
        val api = "${p.url}/player_api.php?username=$user&password=$pass"

        val categories = HashMap<String, String>()
        runCatching {
            open("$api&action=get_live_categories").use { input ->
                JsonReader(input.reader()).use { r ->
                    if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return@use }
                    r.beginArray()
                    while (r.hasNext()) {
                        var id = ""; var name = ""
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "category_id" -> id = r.nextStringOrEmpty()
                                "category_name" -> name = r.nextStringOrEmpty()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (id.isNotEmpty()) categories[id] = name
                    }
                    r.endArray()
                }
            }
        }

        val out = ArrayList<Channel>()
        open("$api&action=get_live_streams").use { input ->
            JsonReader(input.reader()).use { r ->
                if (r.peek() != JsonToken.BEGIN_ARRAY) throw IllegalStateException("The provider didn't accept this login")
                r.beginArray()
                while (r.hasNext()) {
                    var streamId = ""; var name = ""; var icon = ""; var epg = ""; var cat = ""
                    var archive = false; var archiveDays = 0
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "stream_id" -> streamId = r.nextStringOrEmpty()
                            "name" -> name = r.nextStringOrEmpty()
                            "stream_icon" -> icon = r.nextStringOrEmpty()
                            "epg_channel_id" -> epg = r.nextStringOrEmpty()
                            "category_id" -> cat = r.nextStringOrEmpty()
                            "tv_archive" -> archive = r.nextStringOrEmpty().let { it == "1" || it == "true" }
                            "tv_archive_duration" -> archiveDays = r.nextStringOrEmpty().toIntOrNull() ?: 0
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (streamId.isNotEmpty()) {
                        out += Channel(
                            id = p.id + ":x" + streamId,
                            playlistId = p.id,
                            name = name.ifBlank { "Channel $streamId" },
                            url = "${p.url}/live/$user/$pass/$streamId.ts",
                            logo = icon,
                            group = categories[cat].orEmpty(),
                            tvgId = epg,
                            catchup = if (archive) "xc" else "",
                            catchupDays = if (archive) archiveDays.coerceAtLeast(1) else 0,
                        )
                    }
                }
                r.endArray()
            }
        }
        return out
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val out = HashMap<String, String>()
        for (k in keys()) optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it }
        return out
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }

    private fun JsonReader.nextStringOrEmpty(): String = when (peek()) {
        JsonToken.NULL -> { nextNull(); "" }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); "" }
    }

    /** GET with a browser-like agent, following redirects between http and https. */
    private fun open(url: String): InputStream {
        var next = url
        repeat(6) {
            val c = URL(next).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", PLAYLIST_AGENT)
            c.setRequestProperty("Accept", "*/*")
            val code = c.responseCode
            when {
                code in 300..399 -> {
                    val loc = c.getHeaderField("Location") ?: throw IllegalStateException("The server redirected nowhere")
                    c.disconnect()
                    next = URL(URL(next), loc).toString()
                }
                code in 200..299 -> return c.inputStream
                code == 401 || code == 403 -> { c.disconnect(); throw IllegalStateException("The server refused access ($code). Check the link or login") }
                code == 404 -> { c.disconnect(); throw IllegalStateException("Playlist not found (404). Check the link") }
                else -> { c.disconnect(); throw IllegalStateException("The server answered with error $code") }
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Can't reach that address. Check the link and the TV's internet"
        is java.net.SocketTimeoutException -> "The server took too long to answer"
        is javax.net.ssl.SSLException -> "Secure connection failed"
        else -> e.message?.take(140) ?: "Couldn't load the playlist"
    }

    // Cache: one channel per line, tab-separated. Small, fast and needs no JSON parsing at start-up.
    private fun writeCache(id: String, list: List<Channel>) {
        val tmp = File(cacheDir, "$id.tmp")
        tmp.bufferedWriter().use { w ->
            for (c in list) {
                w.write(listOf(c.id, c.name, c.url, c.logo, c.group, c.tvgId, c.userAgent, c.referrer, c.catchup, c.catchupSource, c.catchupDays.toString()).joinToString("\t") { clean(it) })
                w.write("\n")
            }
        }
        val dest = File(cacheDir, "$id.tsv")
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
    }

    private fun readCache(id: String): List<Channel> {
        val f = File(cacheDir, "$id.tsv")
        if (!f.isFile) return emptyList()
        return runCatching {
            f.bufferedReader().useLines { lines ->
                // Thousands of channels share a few hundred group names (and mostly-empty fields):
                // keep one copy of each instead of one per channel.
                val shared = HashMap<String, String>()
                fun one(s: String) = shared.getOrPut(s) { s }
                lines.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 8) null
                    else Channel(
                        id = p[0], playlistId = id, name = p[1], url = p[2], logo = p[3], group = one(p[4]), tvgId = p[5], userAgent = one(p[6]), referrer = one(p[7]),
                        catchup = one(p.getOrElse(8) { "" }), catchupSource = one(p.getOrElse(9) { "" }), catchupDays = p.getOrNull(10)?.toIntOrNull() ?: 0,
                    )
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.ifBlank { null } ?: "Playlist"

    private fun MutableStateFlow<Set<String>>.compareAndAdd(id: String): Boolean {
        while (true) {
            val cur = value
            if (id in cur) return false
            if (compareAndSet(cur, cur + id)) return true
        }
    }

    companion object {
        private const val TAG = "AkoyaLiveTv"
        private const val KEY = "state"
        private const val KEY_FORMAT = "cacheFormat"
        private const val CACHE_FORMAT = 2
        private const val STALE_MS = 24L * 60 * 60 * 1000
        private const val GUIDE_STALE_MS = 12L * 60 * 60 * 1000
        private const val GUIDE_PAST_MS = 3L * 60 * 60 * 1000
        private const val GUIDE_PAST_CATCHUP_MS = 3L * 24 * 60 * 60 * 1000
        private const val GUIDE_FUTURE_MS = 36L * 60 * 60 * 1000
        const val PLAYLIST_AGENT = "Mozilla/5.0 (Linux; Android 12; TV) AkoyaHome/1.0"

        @Volatile private var instance: LiveTvStore? = null
        fun get(context: Context): LiveTvStore =
            instance ?: synchronized(this) {
                instance ?: LiveTvStore(context.applicationContext).also { instance = it }
            }
    }
}
