package com.akoya.home.livetv

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind.name).put("url", url)
        .put("username", username).put("password", password).put("enabled", enabled)
        .put("channelCount", channelCount).put("updatedAt", updatedAt)

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
            )
        }
    }
}

data class LiveTvState(
    val playlists: List<Playlist> = emptyList(),
    /** Channel ids, in the order the user arranged them. */
    val favorites: List<String> = emptyList(),
    val lastChannelId: String? = null,
)

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheDir = File(context.filesDir, "livetv").apply { mkdirs() }

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    /** Every channel from every playlist that is switched on, in playlist order. */
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    /** Playlist ids being downloaded right now. */
    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    val loading: StateFlow<Set<String>> = _loading.asStateFlow()

    /** Last download problem per playlist id. */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    /** False until the cached channels have been read from storage. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val byPlaylist = HashMap<String, List<Channel>>()
    private val lock = Any()

    init {
        scope.launch {
            for (p in _state.value.playlists) {
                val cached = readCache(p.id)
                synchronized(lock) { byPlaylist[p.id] = cached }
            }
            publish()
            _ready.value = true
            refreshStale()
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
        File(cacheDir, "$id.tsv").delete()
        publish()
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
        val cutoff = System.currentTimeMillis() - STALE_MS
        current.playlists.filter { it.enabled && it.updatedAt < cutoff }.forEach { refreshAsync(it.id) }
    }

    /** Downloads one playlist again. Keeps the old channels if the download fails. */
    suspend fun refresh(id: String): Result<Int> = withContext(Dispatchers.IO) {
        val p = current.playlists.firstOrNull { it.id == id } ?: return@withContext Result.failure(IllegalArgumentException("No such playlist"))
        if (!_loading.compareAndAdd(id)) return@withContext Result.success(p.channelCount)
        val result = runCatching {
            val list = when (p.kind) {
                PlaylistKind.M3U -> downloadM3u(p)
                PlaylistKind.XTREAM -> downloadXtream(p)
            }
            if (list.isEmpty()) throw IllegalStateException("No channels found in this playlist")
            writeCache(id, list)
            synchronized(lock) { byPlaylist[id] = list }
            updateState { s ->
                s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(channelCount = list.size, updatedAt = System.currentTimeMillis()) else it })
            }
            publish()
            list.size
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
        _channels.value = merged
    }

    private fun updateState(transform: (LiveTvState) -> LiveTvState) {
        val next = _state.updateAndGet(transform)
        prefs.edit().putString(KEY, toJson(next).toString()).apply()
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
            )
        }.getOrDefault(LiveTvState())
    }

    private fun toJson(s: LiveTvState): JSONObject = JSONObject()
        .put("playlists", JSONArray().also { arr -> s.playlists.forEach { arr.put(it.toJson()) } })
        .put("favorites", JSONArray(s.favorites))
        .put("lastChannelId", s.lastChannelId ?: "")

    private fun downloadM3u(p: Playlist): List<Channel> =
        open(p.url).use { input -> input.bufferedReader().useLines { M3uParser.parse(it, p.id) } }

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
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "stream_id" -> streamId = r.nextStringOrEmpty()
                            "name" -> name = r.nextStringOrEmpty()
                            "stream_icon" -> icon = r.nextStringOrEmpty()
                            "epg_channel_id" -> epg = r.nextStringOrEmpty()
                            "category_id" -> cat = r.nextStringOrEmpty()
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
                        )
                    }
                }
                r.endArray()
            }
        }
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
                w.write(listOf(c.id, c.name, c.url, c.logo, c.group, c.tvgId, c.userAgent, c.referrer).joinToString("\t") { clean(it) })
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
                lines.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 8) null
                    else Channel(id = p[0], playlistId = id, name = p[1], url = p[2], logo = p[3], group = p[4], tvgId = p[5], userAgent = p[6], referrer = p[7])
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
        private const val STALE_MS = 24L * 60 * 60 * 1000
        const val PLAYLIST_AGENT = "Mozilla/5.0 (Linux; Android 12; TV) AkoyaHome/1.0"

        @Volatile private var instance: LiveTvStore? = null
        fun get(context: Context): LiveTvStore =
            instance ?: synchronized(this) {
                instance ?: LiveTvStore(context.applicationContext).also { instance = it }
            }
    }
}
