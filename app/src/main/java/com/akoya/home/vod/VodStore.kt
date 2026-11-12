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
import kotlinx.coroutines.launch
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
    /** The provider's own genre text, when its list has one. */
    val genreText: String = "",
    // ---- worked out when the library is arranged
    /** The name without the provider's year, language and quality trimmings. */
    val title: String = name,
    /** Same for every copy of this film or series, however it is filed. */
    val tkey: String = "",
    val region: Region = Region.HOLLYWOOD,
    val genres: List<String> = emptyList(),
    val era: String = "",
)

/** A provider category: [raw] is the provider's name, [display] the tidied one. */
class VodCategory(val key: String, val raw: String, val display: String, val facet: Facet, val year: Int, val items: List<VodItem>) {
    /** How the user's choices (hidden, pinned) refer to it: by name, so they survive a change of provider. */
    val prefKey: String get() = raw.trim().lowercase()
}

/** A row on the library's home screen: a short [items] preview of the longer [all]. */
class VodRow(val key: String, val title: String, val items: List<VodItem>, val all: List<VodItem> = items)

/** A tile on the Categories screen: a genre, a year, or one of the provider's collections. */
class Shelf(val key: String, val name: String, val items: List<VodItem>)

/**
 * A whole library of one kind. Providers file the same film several times (by year, in 4K,
 * under a genre), so [titles] holds each film once per region and [versions] its copies.
 */
class VodCatalog(val items: List<VodItem>, val categories: List<VodCategory>, eraOrder: List<String>) {
    val byCategory: Map<String, List<VodItem>> = categories.associate { it.key to it.items }
    val versions: Map<String, List<VodItem>> = items.groupBy { it.tkey }
    val titles: List<VodItem>
    val byTitle: Map<String, VodItem>
    private val eras = eraOrder

    init {
        val seen = HashSet<String>(items.size * 2)
        val one = ArrayList<VodItem>(items.size)
        // Prefer the copy filed under a plain language shelf over the 4K, genre or multi-audio copy.
        for (pass in 0..1) for (i in items) {
            val plain = !i.name.contains("multi", ignoreCase = true)
            if ((pass == 0) == plain && seen.add(i.region.name + i.tkey)) one += i
        }
        titles = one
        byTitle = HashMap<String, VodItem>(one.size * 2).also { m -> for (i in one) m.putIfAbsent(i.tkey, i) }
    }

    fun titles(region: Region?): List<VodItem> = if (region == null) titles.distinctBy { it.tkey } else titles.filter { it.region == region }

    /** Genre tiles for a region, in the usual order, with how many titles are tagged. */
    fun genreShelves(region: Region?): List<Shelf> {
        val by = HashMap<String, MutableList<VodItem>>()
        for (i in titles(region)) for (g in i.genres) by.getOrPut(g) { ArrayList() }.add(i)
        return VodSort.GENRES.mapNotNull { g -> by[g]?.let { Shelf("g:$g", g, it) } }
    }

    fun yearShelves(region: Region?): List<Shelf> {
        val by = titles(region).filter { it.era.isNotEmpty() }.groupBy { it.era }
        return eras.mapNotNull { e -> by[e]?.let { Shelf("y:$e", e, it) } }
    }

    /** The provider's categories that aren't just a genre or a language-and-year: franchises, trending, 4K, networks. */
    fun collections(region: Region?): List<Pair<VodCategory, List<VodItem>>> = categories.mapNotNull { c ->
        if (VodSort.genreOfCategory(c.raw) != null || VodSort.isLanguageYearShelf(c.raw)) return@mapNotNull null
        val list = (if (region == null) c.items else c.items.filter { it.region == region || (region == Region.BOLLYWOOD && "multi" in c.raw.lowercase()) }).distinctBy { it.tkey }
        if (list.isEmpty()) null else c to list
    }

    fun untagged(region: Region?): Int = titles(region).count { it.genres.isEmpty() }
}

/** What the user has chosen for the library. Categories are named, titles are keyed by title. */
data class VodPrefs(
    val hidden: Set<String> = emptySet(),
    val pinned: List<String> = emptyList(),
    val myList: List<String> = emptyList(),
    val history: List<Watch> = emptyList(),
    /** The region last browsed ("" for everything). */
    val region: String = "",
) {
    val regionOrNull: Region? get() = Region.entries.firstOrNull { it.name == region }
}

enum class VodOrder(val label: String) { ADDED("Recently added"), NAME("A to Z"), RATING("Top rated"), YEAR("Newest first") }

val VodItem.titleKey: String get() = tkey.ifEmpty { VodSort.titleKey(name, series) }

/** Progress of a genre-tagging run. */
data class TagRun(val done: Int, val total: Int, val found: Int, val running: Boolean)

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
                arrange(kind, items, categories).also { catalogs[kind] = it }
            }
        }
    }

    private fun arrange(kind: VodKind, raw: List<VodItem>, categories: Map<String, String>): VodCatalog {
        val learned = readGenres(kind)
        // First pass: clean titles, and note which titles the provider filed under a genre shelf.
        val keys = ArrayList<String>(raw.size)
        val shelfGenres = HashMap<String, MutableSet<String>>()
        for (i in raw) {
            val key = VodSort.titleKey(i.name, i.series)
            keys += key
            val set = shelfGenres.getOrPut(key) { LinkedHashSet(2) }
            VodSort.genreOfCategory(categories[i.category].orEmpty())?.let { set += it }
            if (i.genreText.isNotBlank()) set += VodSort.genresOf(i.genreText)
            learned[key]?.let { set += it }
        }
        // Second pass: every copy of a title gets the title's genres, its region and its year shelf.
        val items = ArrayList<VodItem>(raw.size)
        raw.forEachIndexed { n, i ->
            val key = keys[n]
            val year = i.year.toIntOrNull() ?: 0
            items += i.copy(
                title = VodSort.cleanTitle(i.name), tkey = key,
                region = VodSort.regionOf(i.name, categories[i.category].orEmpty()),
                genres = shelfGenres[key]?.let { g -> VodSort.GENRES.filter { it in g } }.orEmpty(),
                era = VodSort.eraOf(year, thisYear),
            )
        }
        val grouped = LinkedHashMap<String, MutableList<VodItem>>()
        for (k in categories.keys) grouped[k] = ArrayList()
        for (i in items) grouped.getOrPut(i.category) { ArrayList() }.add(i)
        val out = ArrayList<VodCategory>()
        for ((k, list) in grouped) {
            if (list.isEmpty()) continue
            val name = categories[k] ?: "Other"
            out += VodCategory(k, name, VodSort.display(name), VodSort.classify(name), VodSort.yearOf(name), list)
        }
        val eras = (thisYear + 1 downTo thisYear - 6).map { it.toString() } + listOf("2010s", "2000s", "90s", "80s", "Classics")
        return VodCatalog(items, out, eras)
    }

    // ------------------------------------------------------------------ genres learned title by title

    private fun genreFile(kind: VodKind) = File(dir, "genres_${kind.name.lowercase()}.tsv")
    private val learnedNow = HashMap<VodKind, HashMap<String, List<String>>>()

    private fun readGenres(kind: VodKind): HashMap<String, List<String>> = synchronized(learnedNow) {
        learnedNow.getOrPut(kind) {
            val m = HashMap<String, List<String>>()
            runCatching {
                genreFile(kind).takeIf { it.isFile }?.forEachLine { line ->
                    val t = line.indexOf('\t')
                    if (t > 0) m[line.substring(0, t)] = line.substring(t + 1).split(',').filter { it.isNotBlank() }
                }
            }
            m
        }
    }

    /** Remembers a title's genres (from its detail page or the tagging run). An empty list means "looked, none given". */
    private fun learn(kind: VodKind, key: String, genres: List<String>) {
        val m = readGenres(kind)
        synchronized(learnedNow) {
            if (m[key] == genres) return
            m[key] = genres
            runCatching { genreFile(kind).appendText(key + "\t" + genres.joinToString(",") + "\n") }
        }
    }

    /** Progress of a "tag more titles by genre" run; null when none has been started. */
    private val _tagging = MutableStateFlow<TagRun?>(null)
    val tagging: StateFlow<TagRun?> = _tagging.asStateFlow()
    private var tagJob: kotlinx.coroutines.Job? = null
    private val tagScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + com.akoya.home.livetv.Background.dispatcher)

    /**
     * Asks the provider for the details of each film that has no genre yet, one at a time and
     * unhurried, and files the film under what comes back. Results are kept, so a run can be
     * stopped and picked up again. The library is re-sorted when it ends.
     */
    fun tagGenres(region: Region?) {
        val catalog = catalogs[VodKind.MOVIES] ?: return
        tagJob?.cancel()
        val known = readGenres(VodKind.MOVIES)
        val todo = catalog.titles(region).filter { it.genres.isEmpty() && it.tkey !in known }
        if (todo.isEmpty()) { _tagging.value = TagRun(0, 0, 0, running = false); return }
        _tagging.value = TagRun(0, todo.size, 0, running = true)
        tagJob = tagScope.launch {
            var done = 0
            var found = 0
            try {
                for (item in todo) {
                    val info = movieInfo(item)
                    done++
                    if (info != null) {
                        val g = VodSort.genresOf(info.genre)
                        learn(VodKind.MOVIES, item.tkey, g)
                        if (g.isNotEmpty()) found++
                    }
                    _tagging.value = TagRun(done, todo.size, found, running = true)
                    kotlinx.coroutines.delay(900)
                }
            } finally {
                _tagging.value = TagRun(done, todo.size, found, running = false)
                if (found > 0) rearrange(VodKind.MOVIES)
            }
        }
    }

    fun stopTagging() { tagJob?.cancel() }

    /** Goes up whenever a library has been re-sorted, so screens fetch it again. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun rearrange(kind: VodKind) {
        synchronized(catalogs) { catalogs.remove(kind) }
        _revision.value++
    }

    // ------------------------------------------------------------------ the user's arrangement

    private val _prefs = MutableStateFlow(loadPrefs())
    val prefs: StateFlow<VodPrefs> = _prefs.asStateFlow()

    private fun change(transform: (VodPrefs) -> VodPrefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        val o = JSONObject()
            .put("hidden", JSONArray(next.hidden.toList())).put("pinned", JSONArray(next.pinned)).put("myList", JSONArray(next.myList)).put("region", next.region)
            .put("history", JSONArray().also { a -> next.history.forEach { a.put(JSONObject().put("t", it.titleKey).put("c", it.category).put("at", it.at)) } })
        prefs0.edit().putString("prefs", o.toString()).apply()
    }

    private fun loadPrefs(): VodPrefs = runCatching {
        val o = JSONObject(prefs0.getString("prefs", "{}") ?: "{}")
        fun strings(name: String): List<String> = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        val h = o.optJSONArray("history")
        VodPrefs(
            hidden = strings("hidden").toSet(), pinned = strings("pinned"), myList = strings("myList"), region = o.optString("region"),
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

    /** Other copies of the same title in the same region (the 4K one, the multi-audio one), with a label for each. */
    fun versions(kind: VodKind, item: VodItem): List<Pair<String, VodItem>> {
        val c = catalogs[kind] ?: return emptyList()
        val names = c.categories.associate { it.key to it.display }
        return c.versions[item.tkey].orEmpty().filter { it.key != item.key && it.region == item.region }
            .map { (names[it.category] ?: "Another copy") to it }.distinctBy { it.first }.take(4)
    }

    fun categoryName(kind: VodKind, item: VodItem): String =
        catalogs[kind]?.categories?.firstOrNull { it.key == item.category }?.prefKey.orEmpty()

    fun sorted(items: List<VodItem>, order: VodOrder): List<VodItem> = when (order) {
        VodOrder.ADDED -> items.sortedByDescending { it.added }
        VodOrder.NAME -> items.sortedBy { it.title.lowercase() }
        VodOrder.RATING -> items.sortedByDescending { it.rating.toFloatOrNull() ?: 0f }
        VodOrder.YEAR -> items.sortedByDescending { it.year.toIntOrNull() ?: 0 }
    }

    fun setRegion(region: Region?) = change { it.copy(region = region?.name.orEmpty()) }

    /**
     * The library's home screen for one region (or everything): the user's own rows, what's
     * new, what's trending, then a row per genre. Every film appears once.
     */
    fun homeRows(catalog: VodCatalog, prefs: VodPrefs, region: Region?): List<VodRow> {
        val rows = ArrayList<VodRow>()
        val titles = catalog.titles(region)
        fun newest(list: List<VodItem>) = list.sortedByDescending { it.added }
        fun add(key: String, title: String, all: List<VodItem>, min: Int = 1) {
            if (all.size >= min && rows.size < 18) rows += VodRow(key, title, all.take(HOME_ROW), all)
        }

        val mine = prefs.myList.mapNotNull { catalog.byTitle[it] }
        add("__mylist", "My list", mine)

        // Recommendations: the genres and shelves of what was watched lately, within this region.
        val watched = prefs.history.sortedByDescending { it.at }
        if (watched.isNotEmpty()) {
            val seen = watched.mapTo(HashSet()) { it.titleKey }
            val taste = HashMap<String, Float>()
            watched.forEachIndexed { n, w ->
                val weight = 1f / (1f + n * 0.25f)
                catalog.byTitle[w.titleKey]?.genres?.forEach { g -> taste[g] = (taste[g] ?: 0f) + weight }
            }
            if (taste.isNotEmpty()) {
                val picks = titles.asSequence().filter { it.tkey !in seen && it.genres.isNotEmpty() }
                    .map { i -> (i.genres.sumOf { (taste[it] ?: 0f).toDouble() }.toFloat() * 4f + (i.rating.toFloatOrNull() ?: 0f) + if (i.era.length == 4) 2f else 0f) to i }
                    .filter { it.first > 6f }.sortedByDescending { it.first }.take(HOME_ROW).map { it.second }.toList()
                add("__foryou", "Recommended for you", picks, min = 6)
            }
            val last = watched.first()
            catalog.byTitle[last.titleKey]?.let { t ->
                val g = t.genres.firstOrNull() ?: return@let
                val more = titles.filter { g in it.genres && it.tkey != t.tkey && it.region == t.region }.sortedByDescending { it.rating.toFloatOrNull() ?: 0f }
                add("__because", "Because you watched ${t.title}", more, min = 6)
            }
        }

        add("__recent", "Recently added", newest(titles.filter { it.added > 0 }).take(HOME_ROW * 3), min = 6)

        val shown = catalog.collections(region).filter { it.first.prefKey !in prefs.hidden }
        for (key in prefs.pinned) shown.firstOrNull { it.first.prefKey == key }?.let { (c, list) -> add(c.key, c.display, newest(list)) }
        shown.filter { (c, _) -> TRENDING.any { it in c.raw.lowercase() } }.take(2).forEach { (c, list) -> add(c.key, c.display, newest(list), min = 4) }
        for (shelf in catalog.genreShelves(region)) add(shelf.key, shelf.name, newest(shelf.items), min = 8)
        return rows.distinctBy { it.key }
    }

    /** The library as a spreadsheet file (CSV), or null when it can't be loaded. */
    fun exportCsv(kind: VodKind): File? {
        val catalog = catalogs[kind] ?: kotlinx.coroutines.runBlocking { catalog(kind) }.getOrNull() ?: return null
        val names = catalog.categories.associate { it.key to it.raw }
        val file = File(context.cacheDir, "akoya-${kind.label.lowercase()}.csv")
        fun cell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        file.bufferedWriter().use { w ->
            w.write("\uFEFFTitle,Year,Region,Genres,Category,Rating,Added,Id\n")
            for (i in catalog.items) {
                val raw = names[i.category].orEmpty()
                w.write(listOf(cell(i.name), i.year, i.region.label, cell(i.genres.joinToString(", ")), cell(raw), i.rating, if (i.added > 0) day.format(java.util.Date(i.added * 1000)) else "", i.id).joinToString(","))
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
                var id = ""; var name = ""; var poster = ""; var cat = ""; var rating = ""; var added = 0L; var ext = ""; var plot = ""; var year = ""; var genre = ""
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
                        "genre" -> genre = r.text().take(120)
                        "releaseDate", "release_date", "year" -> r.text().take(4).let { if (it.length == 4 && it.all(Char::isDigit)) year = it }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id.isNotEmpty() && name.isNotBlank()) {
                    into += VodItem(
                        key = a.playlistId + (if (series) ":s:" else ":m:") + id, id = id, playlistId = a.playlistId, name = name.trim(),
                        poster = poster, category = a.playlistId + ":" + cat, rating = rating.takeIf { it.toFloatOrNull()?.let { v -> v > 0f } == true }.orEmpty(),
                        added = added, ext = ext, series = series, plot = plot, genreText = genre,
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
            VodSort.genresOf(info.str("genre")).let { g -> if (g.isNotEmpty() && item.genres.isEmpty() && item.tkey.isNotEmpty()) learn(VodKind.MOVIES, item.tkey, g) }
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
