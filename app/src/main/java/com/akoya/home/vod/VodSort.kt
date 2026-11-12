package com.akoya.home.vod

/** How a provider category is filed on the Categories screen. */
enum class Facet(val title: String) {
    UHD("4K"),
    LANGUAGE("By language"),
    YEAR("By year"),
    ERA("By era"),
    KIDS("Kids and family"),
    COLLECTION("Collections"),
}

/** One thing the user watched, for recommendations. */
data class Watch(val titleKey: String, val category: String, val at: Long)

/**
 * Makes sense of a provider's category names ("4K VOD | English", "VOD | Hindi 2026",
 * "2010-2019", "Nineties") and picks recommendations. Pure Kotlin, unit-tested.
 */
object VodSort {
    private val PREFIX = Regex("""^\s*(4k\s+|uhd\s+)?(vod|movies?|films?|series|tv\s*shows?)\s*[|:\-–]+\s*""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""(?<!\d)(19|20)\d{2}(?!\d)""")
    private val RANGE = Regex("""(?<!\d)(19|20)\d{2}\s*[-–]\s*((19|20)\d{2}|\d{2})(?!\d)""")
    private val WORD_BREAK = Regex("""[^\p{L}\p{N}]+""")
    private val LANGUAGES = setOf(
        "english", "hindi", "tamil", "telugu", "punjabi", "gujarati", "malayalam", "kannada", "bengali", "marathi", "urdu",
        "bhojpuri", "odia", "assamese", "arabic", "french", "spanish", "german", "italian", "turkish", "korean", "japanese",
        "chinese", "russian", "portuguese", "persian", "farsi", "dutch", "polish", "greek", "thai", "indonesian", "filipino",
        "bollywood", "hollywood", "tollywood", "kollywood", "dubbed", "pakistani", "south",
    )
    private val KIDS = setOf("kids", "kid", "children", "cartoon", "cartoons", "animation", "animated", "anime", "family", "disney", "pixar")
    private val ERA = setOf("nineties", "eighties", "seventies", "sixties", "fifties", "90s", "80s", "70s", "60s", "classic", "classics", "retro", "oldies", "old")
    private val FILLER = setOf("vod", "movies", "movie", "films", "film", "series", "new", "latest", "release", "releases", "the")

    fun classify(raw: String): Facet {
        val words = WORD_BREAK.split(raw.lowercase()).filter { it.isNotEmpty() }
        return when {
            "4k" in words || "uhd" in words -> Facet.UHD
            words.any { it in KIDS } -> Facet.KIDS
            words.any { it in LANGUAGES } -> Facet.LANGUAGE
            RANGE.containsMatchIn(raw) || words.any { it in ERA } -> Facet.ERA
            // A bare year ("2024", "Movies 2024") is a year shelf; "World Cup 2026" is a collection.
            YEAR.containsMatchIn(raw) && words.all { it in FILLER || it.all(Char::isDigit) } -> Facet.YEAR
            else -> Facet.COLLECTION
        }
    }

    /** "4K VOD | English" becomes "4K · English"; "VOD | Hindi 2026" becomes "Hindi 2026". */
    fun display(raw: String): String {
        val m = PREFIX.find(raw) ?: return raw.trim()
        val rest = raw.substring(m.range.last + 1).trim()
        if (rest.isEmpty()) return raw.trim()
        return if (m.groupValues[1].isNotBlank()) "4K · $rest" else rest
    }

    /** The last plausible year in a name, or 0: "Dune Part Two (2024)" gives 2024. */
    fun yearOf(text: String, latest: Int = 2100): Int =
        YEAR.findAll(text).map { it.value.toInt() }.filter { it in 1900..latest }.lastOrNull() ?: 0

    /** Same film or series under any provider: the lower-cased name, kept apart by kind. */
    fun titleKey(name: String, series: Boolean): String = (if (series) "s|" else "m|") + name.trim().lowercase()

    /**
     * Titles from the categories the user has been watching, newest and best-rated first.
     * Recent viewing counts for more than old viewing; anything already watched is left out.
     */
    fun recommend(byCategory: Map<String, List<VodItem>>, history: List<Watch>, limit: Int = 30): List<VodItem> {
        if (history.isEmpty()) return emptyList()
        val weights = HashMap<String, Float>()
        history.sortedByDescending { it.at }.forEachIndexed { i, w -> weights[w.category] = (weights[w.category] ?: 0f) + 1f / (1f + i * 0.25f) }
        val seen = history.mapTo(HashSet()) { it.titleKey }
        val scored = ArrayList<Pair<Float, VodItem>>()
        for ((category, weight) in weights.entries.sortedByDescending { it.value }.take(6)) {
            val items = byCategory[category] ?: continue
            val newest = items.maxOfOrNull { it.added } ?: 0L
            val oldest = items.minOfOrNull { it.added } ?: 0L
            val span = (newest - oldest).coerceAtLeast(1L).toFloat()
            for (item in items) {
                if (titleKey(item.name, item.series) in seen) continue
                val freshness = if (item.added > 0) (item.added - oldest) / span * 3f else 0f
                scored += (weight * 10f + (item.rating.toFloatOrNull() ?: 0f).coerceIn(0f, 10f) + freshness) to item
            }
        }
        val picked = LinkedHashMap<String, VodItem>()
        for ((_, item) in scored.sortedByDescending { it.first }) {
            picked.putIfAbsent(titleKey(item.name, item.series), item)
            if (picked.size >= limit) break
        }
        return picked.values.toList()
    }
}
