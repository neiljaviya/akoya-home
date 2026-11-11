package com.akoya.home.vod

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.akoya.home.livetv.LiveTvStore
import com.akoya.home.livetv.XtreamAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class VodKind(val label: String) { MOVIES("Movies"), SERIES("Series") }

/** A film or a series in the provider's library. */
data class VodItem(
    /** Unique across accounts: "<playlist>:m:<id>" or "<playlist>:s:<id>". */
    val key: String,
    val id: String,
    val playlistId: String,
    val name: String,
    val poster: String,
    val category: String,
    val rating: String,
    val added: Long,
    val ext: String,
    val series: Boolean,
    val plot: String = "",
    val year: String = "",
)

class VodRow(val key: String, val title: String, val items: List<VodItem>)

/** A whole library of one kind, arranged into rows. */
class VodCatalog(val items: List<VodItem>, val rows: List<VodRow>) {
    val byKey: Map<String, VodItem> = items.associateBy { it.key }
}

class MovieInfo(val plot: String, val genre: String, val year: String, val duration: String, val rating: String, val cast: String, val director: String, val backdrop: String, val ext: String)

class Episode(val id: String, val season: Int, val number: Int, val title: String, val ext: String, val plot: String, val durationSecs: Int)

class SeriesInfo(val plot: String, val genre: String, val year: String, val rating: String, val cast: String, val backdrop: String, val seasons: Map<Int, List<Episode>>)

/** Something part-watched, shown in "Continue watching". */
data class Resume(
    val key: String,
    val title: String,
    val subtitle: String,
    val poster: String,
    val url: String,
    val pos: Long,
    val dur: Long,
    val updated: Long,
    val series: Boolean,
) {
    val fraction: Float get() = if (dur <= 0) 0f else (pos.toFloat() / dur).coerceIn(0f, 1f)
}

/**
 * The provider's film and series library (the Xtream "player API"). Lists are downloaded to
 * the TV's storage, kept for a day, and read from there, so browsing doesn't wait on the network.
 */
class VodStore private constructor(private val context: Context) {
    private val live = LiveTvStore.get(context)
    private val dir = File(context.filesDir, "vod").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("akoya_vod", Context.MODE_PRIVATE)
    private val locks = mapOf(VodKind.MOVIES to Mutex(), VodKind.SERIES to Mutex())
    private val catalogs = HashMap<VodKind, VodCatalog>()

    private val _resumes = MutableStateFlow(loadResumes())
    val resumes: StateFlow<List<Resume>> = _resumes.asStateFlow()

    fun accounts(): List<XtreamAccount> = live.xtreamAccounts()

    private fun account(playlistId: String) = accounts().firstOrNull { it.playlistId == playlistId }

    /** The library of one kind. [force] downloads it again even if the saved copy is fresh. */
    suspend fun catalog(kind: VodKind, force: Boolean = false): Result<VodCatalog> = withContext(Dispatchers.IO) {
        locks.getValue(kind).withLock {
            if (!force) catalogs[kind]?.let { return@withLock Result.success(it) }
            runCatching {
                val accounts = accounts()
                if (accounts.isEmpty()) throw IllegalStateException("None of your playlists offers a film and series library. That needs an Xtream login, or an M3U link that contains a username and password.")
                val items = ArrayList<VodItem>()
                val categories = LinkedHashMap<String, String>()
                var failure: Throwable? = null
                for (a in accounts) {
                    runCatching {
                        val tag = if (kind == VodKind.MOVIES) "vod" else "series"
                        val cats = cached(a, "${tag}_cats", a.api(if (kind == VodKind.MOVIES) "get_vod_categories" else "get_series_categories"), force)
                        val list = cached(a, "${tag}_list", a.api(if (kind == VodKind.MOVIES) "get_vod_streams" else "get_series"), force)
                        readCategories(cats, a, categories)
                        readItems(list, a, kind == VodKind.SERIES, items)
                    }.onFailure { failure = it; Log.w(TAG, "Library for ${a.name} failed", it) }
                }
                if (items.isEmpty()) throw failure ?: IllegalStateException("The provider's ${kind.label.lowercase()} library is empty")
                arrange(items, categories).also { catalogs[kind] = it }
            }
        }
    }

    private fun arrange(items: List<VodItem>, categories: Map<String, String>): VodCatalog {
        val rows = ArrayList<VodRow>()
        val recent = items.filter { it.added > 0 }.sortedByDescending { it.added }.take(40)
        if (recent.size >= 6) rows += VodRow("__recent", "Recently added", recent)
        val grouped = LinkedHashMap<String, MutableList<VodItem>>()
        for (k in categories.keys) grouped[k] = ArrayList()
        for (i in items) grouped.getOrPut(i.category) { ArrayList() }.add(i)
        for ((k, list) in grouped) if (list.isNotEmpty()) rows += VodRow(k, categories[k] ?: "Other", list)
        return VodCatalog(items, rows)
    }

    private fun readCategories(file: File, a: XtreamAccount, into: MutableMap<String, String>) {
        JsonReader(file.reader()).use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) return
            r.beginArray()
            while (r.hasNext()) {
                var id = ""; var name = ""
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "category_id" -> id = r.text()
                        "category_name" -> name = r.text()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id.isNotEmpty()) into[a.playlistId + ":" + id] = name
            }
            r.endArray()
        }
    }

    private fun readItems(file: File, a: XtreamAccount, series: Boolean, into: MutableList<VodItem>) {
        JsonReader(file.reader()).use { r ->
            if (r.peek() != JsonToken.BEGIN_ARRAY) throw IllegalStateException("The provider didn't return a library")
            r.beginArray()
            while (r.hasNext()) {
                var id = ""; var name = ""; var poster = ""; var cat = ""; var rating = ""; var added = 0L; var ext = ""; var plot = ""; var year = ""
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "stream_id", "series_id" -> id = r.text()
                        "name" -> name = r.text()
                        "stream_icon", "cover" -> poster = r.text()
                        "category_id" -> cat = r.text()
                        "rating" -> rating = r.text()
                        "added", "last_modified" -> added = r.text().toLongOrNull() ?: 0L
                        "container_extension" -> ext = r.text()
                        "plot" -> plot = r.text().take(600)
                        "releaseDate", "release_date", "year" -> r.text().take(4).let { if (it.length == 4 && it.all(Char::isDigit)) year = it }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id.isNotEmpty() && name.isNotBlank()) {
                    into += VodItem(
                        key = a.playlistId + (if (series) ":s:" else ":m:") + id, id = id, playlistId = a.playlistId, name = name.trim(),
                        poster = poster, category = a.playlistId + ":" + cat, rating = rating.takeIf { it.toFloatOrNull()?.let { v -> v > 0f } == true }.orEmpty(),
                        added = added, ext = ext, series = series, plot = plot, year = year,
                    )
                }
            }
            r.endArray()
        }
    }

    private fun JsonReader.text(): String = when (peek()) {
        JsonToken.NULL -> { nextNull(); "" }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); "" }
    }

    /** A saved copy of [url] if it is under a day old, otherwise a fresh download. */
    private fun cached(a: XtreamAccount, name: String, url: String, force: Boolean): File {
        val file = File(dir, "${a.playlistId}_$name.json")
        if (!force && file.isFile && file.length() > 2 && System.currentTimeMillis() - file.lastModified() < DAY_MS) return file
        val tmp = File(dir, file.name + ".tmp")
        try {
            open(url).use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        } catch (e: Exception) {
            tmp.delete()
            // An old copy beats nothing when the provider is having a moment.
            if (file.isFile && file.length() > 2) return file
            throw e
        }
        return file
    }

    suspend fun movieInfo(item: VodItem): MovieInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val a = account(item.playlistId) ?: return@runCatching null
            val o = JSONObject(open(a.api("get_vod_info") + "&vod_id=" + item.id).use { it.reader().readText() })
            val info = o.optJSONObject("info") ?: JSONObject()
            val data = o.optJSONObject("movie_data") ?: JSONObject()
            MovieInfo(
                plot = info.str("plot").ifBlank { info.str("description") },
                genre = info.str("genre"),
                year = info.str("releasedate").ifBlank { info.str("release_date") }.take(4),
                duration = info.str("duration"),
                rating = info.str("rating"),
                cast = info.str("cast").ifBlank { info.str("actors") },
                director = info.str("director"),
                backdrop = firstImage(info.opt("backdrop_path")),
                ext = data.str("container_extension").ifBlank { item.ext },
            )
        }.getOrNull()
    }

    suspend fun seriesInfo(item: VodItem): SeriesInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val a = account(item.playlistId) ?: return@runCatching null
            val o = JSONObject(open(a.api("get_series_info") + "&series_id=" + item.id).use { it.reader().readText() })
            val info = o.optJSONObject("info") ?: JSONObject()
            val seasons = java.util.TreeMap<Int, MutableList<Episode>>()
            fun add(arr: JSONArray?, seasonHint: Int) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val extra = e.optJSONObject("info") ?: JSONObject()
                    val season = e.str("season").toIntOrNull() ?: seasonHint
                    val ep = Episode(
                        id = e.str("id"), season = season, number = e.str("episode_num").toIntOrNull() ?: (i + 1),
                        title = e.str("title"), ext = e.str("container_extension"),
                        plot = extra.str("plot"), durationSecs = extra.str("duration_secs").toIntOrNull() ?: 0,
                    )
                    if (ep.id.isNotBlank()) seasons.getOrPut(season) { ArrayList() }.add(ep)
                }
            }
            // Providers send the episodes either as {"1": [...], "2": [...]} or as [[...], [...]].
            when (val eps = o.opt("episodes")) {
                is JSONObject -> for (k in eps.keys()) add(eps.optJSONArray(k), k.toIntOrNull() ?: 1)
                is JSONArray -> for (i in 0 until eps.length()) add(eps.optJSONArray(i), i + 1)
            }
            for (list in seasons.values) list.sortBy { it.number }
            SeriesInfo(
                plot = info.str("plot").ifBlank { item.plot },
                genre = info.str("genre"),
                year = info.str("releaseDate").ifBlank { info.str("release_date") }.take(4),
                rating = info.str("rating"),
                cast = info.str("cast"),
                backdrop = firstImage(info.opt("backdrop_path")),
                seasons = seasons,
            )
        }.getOrNull()
    }

    fun movieUrl(item: VodItem, ext: String): String? = account(item.playlistId)?.movieUrl(item.id, ext)
    fun episodeUrl(item: VodItem, e: Episode): String? = account(item.playlistId)?.episodeUrl(e.id, e.ext)

    private fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name).trim()

    private fun firstImage(v: Any?): String = when (v) {
        is JSONArray -> (0 until v.length()).map { v.optString(it) }.firstOrNull { it.startsWith("http") }.orEmpty()
        is String -> v.takeIf { it.startsWith("http") }.orEmpty()
        else -> ""
    }

    // ------------------------------------------------------------------ continue watching

    fun resumeFor(key: String): Resume? = _resumes.value.firstOrNull { it.key == key }

    /** Remembers where playback got to. Near the end counts as finished and is forgotten. */
    fun saveProgress(r: Resume) {
        val rest = _resumes.value.filterNot { it.key == r.key }
        val finished = r.dur > 0 && r.pos > r.dur * 0.94
        val next = if (finished || r.pos < 30_000) rest else (listOf(r) + rest).take(40)
        _resumes.value = next
        val arr = JSONArray()
        next.forEach {
            arr.put(
                JSONObject().put("key", it.key).put("title", it.title).put("subtitle", it.subtitle).put("poster", it.poster)
                    .put("url", it.url).put("pos", it.pos).put("dur", it.dur).put("updated", it.updated).put("series", it.series),
            )
        }
        prefs.edit().putString("resumes", arr.toString()).apply()
    }

    private fun loadResumes(): List<Resume> = runCatching {
        val arr = JSONArray(prefs.getString("resumes", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Resume(o.optString("key"), o.optString("title"), o.optString("subtitle"), o.optString("poster"), o.optString("url"), o.optLong("pos"), o.optLong("dur"), o.optLong("updated"), o.optBoolean("series"))
        }.filter { it.key.isNotBlank() && it.url.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun open(url: String): InputStream {
        var next = url
        repeat(6) {
            val c = URL(next).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", LiveTvStore.PLAYLIST_AGENT)
            val code = c.responseCode
            when {
                code in 300..399 -> {
                    val loc = c.getHeaderField("Location") ?: throw IllegalStateException("The provider redirected nowhere")
                    c.disconnect()
                    next = URL(URL(next), loc).toString()
                }
                code in 200..299 -> return c.inputStream
                else -> { c.disconnect(); throw IllegalStateException("The provider answered with error $code") }
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    companion object {
        private const val TAG = "AkoyaVod"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        @Volatile private var instance: VodStore? = null
        fun get(context: Context): VodStore =
            instance ?: synchronized(this) {
                instance ?: VodStore(context.applicationContext).also { instance = it }
            }
    }
}
