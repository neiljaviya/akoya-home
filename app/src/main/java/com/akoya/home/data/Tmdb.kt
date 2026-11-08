package com.akoya.home.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TmdbTitle(
    val id: Int,
    val mediaType: String,
    val title: String,
    val overview: String,
    val year: String,
    val rating: Double,
    val backdrop: String?,
    val poster: String?,
)

data class TmdbRow(val items: List<TmdbTitle>, val error: String?)

/** Which TMDB "watch provider" each streaming app corresponds to (matched by name at runtime). */
data class StreamingService(val label: String, val keywords: List<String>, val trailersOnly: Boolean = false)

val STREAMING_SERVICES: Map<String, StreamingService> = mapOf(
    "com.netflix.ninja" to StreamingService("Netflix", listOf("netflix")),
    "com.amazon.amazonvideo.livingroom" to StreamingService("Prime Video", listOf("amazonprimevideo", "primevideo")),
    "in.startv.hotstar.dplus.tv" to StreamingService("JioHotstar", listOf("jiohotstar", "hotstar", "disneyplushotstar")),
    "in.startv.hotstar" to StreamingService("JioHotstar", listOf("jiohotstar", "hotstar", "disneyplushotstar")),
    "com.graymatrix.did" to StreamingService("ZEE5", listOf("zee5")),
    "com.sonyliv" to StreamingService("SonyLIV", listOf("sonyliv")),
    "com.apple.atve.androidtv.appletv" to StreamingService("Apple TV+", listOf("appletvplus", "appletv")),
    "com.mxtech.videoplayer.television" to StreamingService("MX Player", listOf("mxplayer")),
    "com.google.android.youtube.tv" to StreamingService("YouTube", emptyList(), trailersOnly = true),
)

class TmdbRepository private constructor() {
    private val cache = HashMap<String, Pair<Long, TmdbRow>>()
    private val providerCache = HashMap<String, Map<String, Int>>()

    private fun get(path: String, key: String, params: Map<String, String> = emptyMap()): JSONObject {
        val bearer = key.length > 40
        val query = (params + if (bearer) emptyMap() else mapOf("api_key" to key))
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = URL("https://api.themoviedb.org/3$path?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/json")
        if (bearer) conn.setRequestProperty("Authorization", "Bearer $key")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw IllegalStateException("TMDB key not accepted")
            if (code !in 200..299) throw IllegalStateException("TMDB error $code")
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun providers(key: String, region: String): Map<String, Int> =
        providerCache.getOrPut(region) {
            val out = HashMap<String, Int>()
            for (kind in listOf("movie", "tv")) {
                val arr = get("/watch/providers/$kind", key, mapOf("watch_region" to region)).optJSONArray("results") ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out[norm(o.optString("provider_name"))] = o.optInt("provider_id")
                }
            }
            out
        }

    private fun providerId(key: String, region: String, keywords: List<String>): Int? {
        val all = providers(key, region)
        keywords.forEach { kw -> all[kw]?.let { return it } }
        keywords.forEach { kw -> all.entries.firstOrNull { it.key.startsWith(kw) }?.let { return it.value } }
        return null
    }

    private fun parse(o: JSONObject, fallbackType: String): TmdbTitle {
        val type = o.optString("media_type").ifBlank { fallbackType }
        val date = o.optString(if (type == "tv") "first_air_date" else "release_date")
        return TmdbTitle(
            id = o.optInt("id"),
            mediaType = type,
            title = o.optString(if (type == "tv") "name" else "title").ifBlank { o.optString("name", o.optString("title")) },
            overview = o.optString("overview"),
            year = date.take(4),
            rating = o.optDouble("vote_average", 0.0),
            backdrop = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/w780$it" },
            poster = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }?.let { "https://image.tmdb.org/t/p/w500$it" },
        )
    }

    private fun list(json: JSONObject, type: String): List<TmdbTitle> {
        val arr = json.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { parse(arr.getJSONObject(it), type) }.filter { it.backdrop != null }
    }

    suspend fun trending(pkg: String, key: String, region: String): TmdbRow = withContext(Dispatchers.IO) {
        val cacheKey = "$pkg|$region"
        cache[cacheKey]?.let { (at, row) -> if (System.currentTimeMillis() - at < 6 * 3600_000L && row.error == null) return@withContext row }
        val service = STREAMING_SERVICES[pkg] ?: return@withContext TmdbRow(emptyList(), "No TMDB match for this app")
        val row = runCatching {
            if (service.trailersOnly) {
                TmdbRow(list(get("/trending/all/day", key, mapOf("language" to "en-US")), "movie").take(20), null)
            } else {
                val id = providerId(key, region, service.keywords)
                    ?: return@runCatching TmdbRow(emptyList(), "${service.label} isn't listed on TMDB for region $region")
                val common = mapOf(
                    "with_watch_providers" to id.toString(),
                    "watch_region" to region,
                    "sort_by" to "popularity.desc",
                    "language" to "en-US",
                )
                val movies = list(get("/discover/movie", key, common), "movie")
                val shows = list(get("/discover/tv", key, common), "tv")
                val mixed = buildList {
                    val n = maxOf(movies.size, shows.size)
                    for (i in 0 until n) {
                        shows.getOrNull(i)?.let(::add)
                        movies.getOrNull(i)?.let(::add)
                    }
                }
                TmdbRow(mixed.take(24), null)
            }
        }.getOrElse { TmdbRow(emptyList(), it.message ?: "Couldn't reach TMDB") }
        cache[cacheKey] = System.currentTimeMillis() to row
        row
    }

    /** YouTube key for the title's trailer, if TMDB has one. */
    suspend fun trailer(t: TmdbTitle, key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val arr = get("/${t.mediaType}/${t.id}/videos", key).optJSONArray("results") ?: return@runCatching null
            val vids = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("site") == "YouTube" }
            (vids.firstOrNull { it.optString("type") == "Trailer" } ?: vids.firstOrNull())?.optString("key")
        }.getOrNull()
    }

    /** Quick check used by the phone page when saving a key. */
    suspend fun validate(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { get("/configuration", key); true }.getOrDefault(false)
    }

    fun clear() { cache.clear(); providerCache.clear() }

    companion object {
        val instance by lazy { TmdbRepository() }
    }
}
