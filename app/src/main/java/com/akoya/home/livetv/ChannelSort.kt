package com.akoya.home.livetv

/**
 * Files a provider's channel groups ("IN | News", "INR | Tamil", "SPORTS | beIN MAX",
 * "INT | Germany") into a handful of sections, so a list of hundreds of groups opens on
 * the ten or so headings a household actually thinks in. Pure Kotlin, unit-tested against
 * a real provider's group names.
 */
object ChannelSort {
    const val HINDI = "Hindi"
    const val GUJARATI = "Gujarati"
    const val SOUTH = "South"
    const val CANADA = "Canada"
    const val USA = "USA"
    const val UK = "UK"
    const val SPORTS = "Sports"
    const val ROUND_THE_CLOCK = "24/7"
    const val REST = "Rest of the world"
    const val ADULT = "Adults"

    /** Sections in the order they are listed. */
    val SECTIONS = listOf(HINDI, GUJARATI, SOUTH, CANADA, USA, UK, SPORTS, ROUND_THE_CLOCK, REST, ADULT)

    private val SOUTH_WORDS = listOf("tamil", "telugu", "malayalam", "kannada")
    private val SPORT_PREFIXES = setOf("sports", "live", "ppv")
    private val SPORT_WORDS = listOf("sport", "ipl", "nfl", "nba", "nhl", "mlb", "wnba", "fite tv", "dazn", "espn", "cricket", "football", "wrestling")
    private val GENERIC = setOf("sports", "news", "movies", "entertainment", "kids", "music", "general", "religious", "documentaries", "documentary")
    private val SEPARATOR = Regex("""^\s*[#•●★*=~\-_.|]{3,}""")

    /** The part before the first "|" ("IN", "INR", "SPORTS"), lower-cased; empty when there is none. */
    private fun prefix(group: String): String = if ('|' in group) group.substringBefore('|').trim().lowercase() else ""

    fun sectionOf(group: String, playlistName: String = ""): String {
        val g = group.lowercase()
        val p = prefix(group)
        val list = playlistName.lowercase()
        return when {
            p == "xxx" || "adult" in g || "+18" in g -> ADULT
            p in SPORT_PREFIXES || g.trim() in SPORT_PREFIXES || SPORT_WORDS.any { it in g } -> SPORTS
            p == "24/7" -> ROUND_THE_CLOCK
            "gujarati" in g || "gujrati" in g -> GUJARATI
            SOUTH_WORDS.any { it in g } -> SOUTH
            p == "in" -> HINDI
            p == "ca" -> CANADA
            p == "us" -> USA
            p == "uk" || p == "ie" -> UK
            p.isNotEmpty() -> REST
            // No region in the group name (the free lists): go by the playlist it came from.
            "india" in list -> HINDI
            "usa" in list || "united states" in list -> USA
            "canada" in list -> CANADA
            "uk" in list || "united kingdom" in list -> UK
            else -> REST
        }
    }

    /**
     * The group's name inside its section: "IN | News" is just "News" under Hindi, while
     * "CA | Sports" stays "CA Sports" under Sports, where a bare "Sports" would say nothing.
     */
    fun label(group: String, section: String): String {
        if ('|' !in group) return group.trim().ifBlank { "Other" }
        val head = group.substringBefore('|').trim()
        val rest = group.substringAfter('|').trim()
        if (rest.isEmpty()) return head.ifBlank { "Other" }
        if (head.isEmpty()) return rest
        val keepHead = (section == SPORTS || section == REST) && rest.lowercase() in GENERIC
        return if (keepHead) "$head $rest" else rest
    }

    /** Providers pad their lists with header lines ("##### FINLAND #####"); those aren't channels. */
    fun isSeparator(name: String): Boolean = SEPARATOR.containsMatchIn(name)
}
