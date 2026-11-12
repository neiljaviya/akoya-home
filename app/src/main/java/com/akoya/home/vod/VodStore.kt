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

/** A provider category: [raw] is the provider's name, [display] the tidied one. */
class VodCategory(val key: String, val raw: String, val display: String, val facet: Facet, val year: Int, val items: List<VodItem>) {
    /** How the user's choices (hidden, pinned) refer to it: by name, so they survive a change of provider. */
    val prefKey: String get() = raw.trim().lowercase()
}

/** A row on the library's home screen: a short [items] preview of the longer [all]. */
class VodRow(val key: String, val title: String, val items: List<VodItem>, val all: List<VodItem> = items)

/** A whole library of one kind. */
class VodCatalog(val items: List<VodItem>, val categories: List<VodCategory>) {
    val byCategory: Map<String, List<VodItem>> = categories.associate { it.key to it.items }
    val byTitle: Map<String, VodItem> by lazy { HashMap<String, VodItem>(items.size * 2).also { m -> for (i in items) m.putIfAbsent(i.titleKey, i) } }
}

/** What the user has chosen for the library. Categories are named, titles are keyed by title. */
data class VodPrefs(
    val hidden: Set<String> = emptySet(),
    val pinned: List<String> = emptyList(),
    val myList: List<String> = emptyList(),
    val history: List<Watch> = emptyList(),
)

enum class VodOrder(val label: String) { ADDED("Recently added"), NAME("A to Z"), RATING("Top rated"), YEAR("Newest first") }

val VodItem.titleKey: String get() = VodSort.titleKey(name, series)

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
    private val prefs0 = context.getSharedPreferences("akoya_vod", Context.MODE_PRIVATE)
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
        val grouped = LinkedHashMap<String, MutableList<VodItem>>()
        for (k in categories.keys) grouped[k] = ArrayList()
        for (i in items) grouped.getOrPut(i.category) { ArrayList() }.add(i)
        val out = ArrayList<VodCategory>()
        for ((k, list) in grouped) {
            if (list.isEmpty()) continue
            val raw = categories[k] ?: "Other"
            out += VodCategory(k, raw, VodSort.display(raw), VodSort.classify(raw), VodSort.yearOf(raw), list)
        }
        return VodCatalog(items, out)
    }

    // ------------------------------------------------------------------ the user's arrangement

    private val _prefs = MutableStateFlow(loadPrefs())
    val prefs: StateFlow<VodPrefs> = _prefs.asStateFlow()

    private fun change(transform: (VodPrefs) -> VodPrefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        val o = JSONObject()
            .put("hidden", JSONArray(next.hidden.toList())).put("pinned", JSONArray(next.pinned)).put("myList", JSONArray(next.myList))
            .put("history", JSONArray().also { a -> next.history.forEach { a.put(JSONObject().put("t", it.titleKey).put("c", it.category).put("at", it.at)) } })
        prefs0.edit().putString("prefs", o.toString()).apply()
    }

    private fun loadPrefs(): VodPrefs = runCatching {
        val o = JSONObject(prefs0.getString("prefs", "{}") ?: "{}")
        fun strings(name: String): List<String> = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        val h = o.optJSONArray("history")
        VodPrefs(
            hidden = strings("hidden").toSet(), pinned = strings("pinned"), myList = strings("myList"),
            history = (0 until (h?.length() ?: 0)).mapNotNull { i -> h?.optJSONObject(i)?.let { Watch(it.optString("t"), it.optString("c"), it.optLong("at")) } },
        )
    }.getOrDefault(VodPrefs())

    fun setHidden(c: VodCategory, hidden: Boolean) = change { it.copy(hidden = if (hidden) it.hidden + c.prefKey else it.hidden - c.prefKey, pinned = if (hidden) it.pinned - c.prefKey else it.pinned) }
    fun setPinned(c: VodCategory, pinned: Boolean) = change { it.copy(pinned = if (pinned) (it.pinned - c.prefKey) + c.prefKey else it.pinned - c.prefKey) }
    fun toggleMyList(item: VodItem) = change { if (item.titleKey in it.myList) it.copy(myList = it.myList - item.titleKey) else it.copy(myList = listOf(item.titleKey) + it.myList) }

    /** Notes that something was watched, to steer recommendations. The category is stored by name. */
    fun recordWatch(titleKey: String, categoryName: String) = change {
        it.copy(history = (listOf(Watch(titleKey, categoryName, System.currentTimeMillis())) + it.history.filterNot { w -> w.titleKey == titleKey }).take(80))
    }

    fun categoryName(kind: VodKind, item: VodItem): String =
        catalogs[kind]?.categories?.firstOrNull { it.key == item.category }?.prefKey.orEmpty()

    fun sorted(items: List<VodItem>, order: VodOrder): List<VodItem> = when (order) {
        VodOrder.ADDED -> items.sortedByDescending { it.added }
        VodOrder.NAME -> items.sortedBy { it.name.lowercase() }
        VodOrder.RATING -> items.sortedByDescending { it.rating.toFloatOrNull() ?: 0f }
        VodOrder.YEAR -> items.sortedByDescending { it.year.toIntOrNull() ?: 0 }
    }

    /**
     * The library's home screen: the user's own rows first, then what's new, then a handful
     * of the provider's shelves that most people want (trending, this year's, 4K, kids).
     */
    fun homeRows(catalog: VodCatalog, prefs: VodPrefs): List<VodRow> {
        val rows = ArrayList<VodRow>()
        val used = HashSet<String>()
        val shown = catalog.categories.filter { it.prefKey !in prefs.hidden }
        fun preview(list: List<VodItem>) = list.sortedByDescending { it.added }.take(HOME_ROW)
        fun shelf(c: VodCategory, title: String = c.display) {
            if (rows.size < 14 && used.add(c.key)) rows += VodRow(c.key, title, preview(c.items), c.items)
        }

        val mine = prefs.myList.mapNotNull { catalog.byTitle[it] }
        if (mine.isNotEmpty()) rows += VodRow("__mylist", "My list", mine.take(HOME_ROW), mine)

        // Recommendations work on category names, so they carry across providers.
        val byName = shown.associate { it.prefKey to it.items }
        val picks = VodSort.recommend(byName, prefs.history, HOME_ROW)
        if (picks.size >= 6) rows += VodRow("__foryou", "Recommended for you", picks)
        prefs.history.maxByOrNull { it.at }?.let { last ->
            val title = catalog.byTitle[last.titleKey]?.name ?: return@let
            val more = byName[last.category].orEmpty().filter { it.titleKey != last.titleKey }.sortedByDescending { it.rating.toFloatOrNull() ?: 0f }
            if (more.size >= 6) rows += VodRow("__because", "Because you watched $title", more.take(HOME_ROW), more)
        }

        val recent = catalog.items.asSequence().filter { it.added > 0 }.sortedByDescending { it.added }.take(HOME_ROW * 2).toList()
        if (recent.size >= 6) rows += VodRow("__recent", "Recently added", recent.take(HOME_ROW), recent)

        for (key in prefs.pinned) shown.firstOrNull { it.prefKey == key }?.let { shelf(it) }
        shown.firstOrNull { c -> TRENDING.any { it in c.raw.lowercase() } }?.let { shelf(it) }
        val newest = shown.filter { it.facet == Facet.LANGUAGE || it.facet == Facet.YEAR }.maxOfOrNull { it.year } ?: 0
        if (newest > 0) shown.filter { it.year == newest && (it.facet == Facet.LANGUAGE || it.facet == Facet.YEAR) }.take(4).forEach { shelf(it) }
        shown.firstOrNull { it.facet == Facet.UHD }?.let { shelf(it) }
        shown.firstOrNull { it.facet == Facet.KIDS }?.let { shelf(it) }
        return rows
    }

    /** The library as a spreadsheet file (CSV), or null when it can't be loaded. */
    fun exportCsv(kind: VodKind): File? {
        val catalog = catalogs[kind] ?: kotlinx.coroutines.runBlocking { catalog(kind) }.getOrNull() ?: return null
        val names = catalog.categories.associate { it.key to it.raw }
        val file = File(context.cacheDir, "akoya-${kind.label.lowercase()}.csv")
        fun cell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        file.bufferedWriter().use { w ->
            w.write("\uFEFFTitle,Year,Category,Shelf,Rating,Added,Id\n")
            for (i in catalog.items) {
                val raw = names[i.category].orEmpty()
                w.write(listOf(cell(i.name), i.year, cell(raw), cell(VodSort.classify(raw).title), i.rating, if (i.added > 0) day.format(java.util.Date(i.added * 1000)) else "", i.id).joinToString(","))
                w.write("\n")
            }
        }
        return file
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

    private val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

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
                        added = added, ext = ext, series = series, plot = plot,
                        year = year.ifBlank { VodSort.yearOf(name, thisYear + 1).takeIf { it > 0 }?.toString().orEmpty() },
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
        prefs0.edit().putString("resumes", arr.toString()).apply()
    }

    private fun loadResumes(): List<Resume> = runCatching {
        val arr = JSONArray(prefs0.getString("resumes", "[]"))
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
        private const val HOME_ROW = 30
        private val TRENDING = listOf("trending", "popular", "top rated", "top 10", "most watched")

        @Volatile private var instance: VodStore? = null
        fun get(context: Context): VodStore =
            instance ?: synchronized(this) {
                instance ?: VodStore(context.applicationContext).also { instance = it }
            }
    }
}
