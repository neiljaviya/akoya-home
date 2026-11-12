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

/** The household's shelves: what a title is filed under before anything else. */
enum class Region(val label: String) {
    HOLLYWOOD("Hollywood"),
    BOLLYWOOD("Bollywood"),
    SOUTH("South"),
    GUJARATI("Gujarati"),
    REST("Rest of the world"),
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

    // ---- region -------------------------------------------------------------------------

    private val BRACKET = Regex("""\[([^\]]{1,14})]""")
    private val PAREN_WORD = Regex("""\(\s*([A-Za-z][A-Za-z ]{2,16})\)""")
    private val SOUTH = setOf("tamil", "telugu", "malayalam", "malyalam", "kannada")
    private val SOUTH_CODES = setOf("tam", "tel", "mal", "kan")
    private val ENGLISH_CODES = setOf("eng", "us", "uk", "au")
    private val HINDI_CODES = setOf("hin", "hn")
    private val NOT_A_LANGUAGE = setOf("multi", "hdtc", "hdts", "ts", "cam", "4k", "hd", "uhd", "hdr", "web", "dvd")
    private val REST_WORDS = listOf(
        "punjabi", "bangla", "bengali", "marathi", "pakistani", "afrikaans", "southafrica", "south africa", "turkish",
        "asian", "quran", "hoichoi", "chaupal", "arabic", "korean", "urdu", "bhojpuri",
    )
    private val HINDI_PLATFORMS = listOf("indian daily soap", "zee5", "sonyliv", "jiocinema", "mx player", "mxplayer", "altbalaji", "star bharat", "shemaroo", "bollywood")
    private val SOUTH_PLATFORMS = listOf("sun nxt", "etv win", "star suvarna")

    /**
     * Where a title belongs: Hollywood (English), Bollywood (Hindi, including Hindi dubs),
     * South, Gujarati, or the rest of the world. The title's own markers ("[TUR]", "[HIN+TAM]",
     * "(TAMIL)") win over the category it was filed in.
     */
    fun regionOf(title: String, category: String): Region {
        val c = category.lowercase()
        for (m in PAREN_WORD.findAll(title)) {
            val w = m.groupValues[1].trim().lowercase().removeSuffix(" audio").removeSuffix(" dubbed").trim()
            when {
                w == "gujarati" || w == "gujrati" -> return Region.GUJARATI
                w in SOUTH -> return Region.SOUTH
                w == "hindi" -> return Region.BOLLYWOOD
                w == "english" -> return Region.HOLLYWOOD
                w == "marathi" || w == "bangla" || w == "bengali" || w == "punjabi" -> return Region.REST
            }
        }
        for (m in BRACKET.findAll(title)) {
            val codes = m.groupValues[1].lowercase().split('+', '/', ',').map { it.trim() }.filter { it.isNotEmpty() }
            val whole = m.groupValues[1].trim().lowercase()
            if (whole in NOT_A_LANGUAGE || whole.startsWith("season")) continue
            when {
                codes.any { it in HINDI_CODES } -> return Region.BOLLYWOOD
                codes.any { it in SOUTH_CODES } -> return Region.SOUTH
                codes.any { it in ENGLISH_CODES } -> return Region.HOLLYWOOD
                codes.all { it.length in 2..3 && it.all(Char::isLetter) } -> return Region.REST
            }
        }
        return when {
            ("gujarati" in c || "gujrati" in c) && "hindi" !in c -> Region.GUJARATI
            SOUTH.any { it in c } || SOUTH_PLATFORMS.any { it in c } -> Region.SOUTH
            "hindi" in c || HINDI_PLATFORMS.any { it in c } -> Region.BOLLYWOOD
            REST_WORDS.any { it in c } || c.trim() == "other" -> Region.REST
            else -> Region.HOLLYWOOD
        }
    }

    // ---- genres -------------------------------------------------------------------------

    /** Genres in the order their rows appear. */
    val GENRES = listOf(
        "Action", "Comedy", "Drama", "Thriller", "Sci-Fi", "Horror", "Romance", "Crime", "Adventure", "Family", "Kids", "Animation",
        "Fantasy", "Mystery", "Documentary", "History", "War", "Western", "Music", "Reality",
    )
    private val GENRE_ALIASES = mapOf(
        "science fiction" to "Sci-Fi", "sci-fi" to "Sci-Fi", "scifi" to "Sci-Fi", "sci fi" to "Sci-Fi",
        "romantic" to "Romance", "suspense" to "Thriller", "documentaries" to "Documentary", "biography" to "History",
        "children" to "Kids", "musical" to "Music", "war & politics" to "War", "politics" to "War", "talk" to "Reality", "reality-tv" to "Reality",
        "cartoon" to "Animation", "anime" to "Animation", "animated" to "Animation",
    ) + GENRES.associateBy { it.lowercase() }
    private val GENRE_SPLIT = Regex("""\s*[,/|;&]\s*|\s+and\s+""")

    /** "Action & Adventure, Science Fiction" becomes [Action, Adventure, Sci-Fi]. Unknown words are dropped. */
    fun genresOf(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val whole = GENRE_ALIASES[text.trim().lowercase()]
        if (whole != null) return listOf(whole)
        return GENRE_SPLIT.split(text.trim()).mapNotNull { GENRE_ALIASES[it.trim().lowercase()] }.distinct()
    }

    /** The genre a provider category stands for ("VOD | Science Fiction" is Sci-Fi), or null if it isn't a genre shelf. */
    fun genreOfCategory(raw: String): String? = GENRE_ALIASES[display(raw).trim().lowercase()]

    // ---- titles -------------------------------------------------------------------------

    private val TAIL_MULTI = Regex("""\s*\|\s*multi\s*-?\s*audio.*$""", RegexOption.IGNORE_CASE)
    private val YEAR_PAREN = Regex("""\s*\(\s*(19|20)\d{2}\s*\)""")
    private val YEAR_DASH = Regex("""\s+-\s+(19|20)\d{2}\s*$""")
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** The title without the provider's trimmings: "Civil War - 2024 | MultiAudio" becomes "Civil War". */
    fun cleanTitle(name: String): String {
        var s = TAIL_MULTI.replace(name, "")
        s = BRACKET.replace(s, " ")
        s = PAREN_WORD.replace(s) { m -> if (regionWord(m.groupValues[1])) " " else m.value }
        s = YEAR_DASH.replace(YEAR_PAREN.replace(s, " ").trim(), "")
        return s.replace(Regex("""\s{2,}"""), " ").trim().ifBlank { name.trim() }
    }

    private fun regionWord(w: String): Boolean {
        val x = w.trim().lowercase().removeSuffix(" audio").removeSuffix(" dubbed").trim()
        return x in SOUTH || x in setOf("gujarati", "gujrati", "hindi", "english", "marathi", "bangla", "bengali", "punjabi")
    }

    /** The decade or year shelf a year belongs on. */
    fun eraOf(year: Int, thisYear: Int): String = when {
        year <= 0 -> ""
        year >= thisYear - 6 -> year.toString()
        year >= 2010 -> "2010s"
        year >= 2000 -> "2000s"
        year >= 1990 -> "90s"
        year >= 1980 -> "80s"
        else -> "Classics"
    }

    /** True for categories that only say a language and a year or decade: the year shelves cover those. */
    fun isLanguageYearShelf(raw: String): Boolean {
        val words = WORD_BREAK.split(display(raw).lowercase()).filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { it in LANGUAGES || it == "bangla" || it.all(Char::isDigit) || it in setOf("90s", "80s", "70s", "60s") }
    }

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

    /**
     * Same film or series wherever it is filed and however the provider wrote it:
     * "The Suicide Squad - 2021" and "The Suicide Squad (2021) [4K]" share a key.
     */
    fun titleKey(name: String, series: Boolean): String {
        val year = yearOf(name)
        val plain = NOT_WORD.replace(cleanTitle(name).lowercase(), " ").trim()
        return (if (series) "s|" else "m|") + plain + if (year > 0) "|$year" else ""
    }

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
