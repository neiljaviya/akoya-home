package com.akoya.home.vod

import com.akoya.home.livetv.Channel
import com.akoya.home.livetv.SourceMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VodSortTest {
    @Test
    fun categoriesAreFiledByWhatTheirNameSays() {
        assertEquals(Facet.UHD, VodSort.classify("4K VOD | English"))
        assertEquals(Facet.UHD, VodSort.classify("4K VOD | Hindi"))
        assertEquals(Facet.LANGUAGE, VodSort.classify("VOD | Hindi 2026"))
        assertEquals(Facet.LANGUAGE, VodSort.classify("VOD | English 2026"))
        assertEquals(Facet.COLLECTION, VodSort.classify("VOD | Fifa world cup 2026"))
        assertEquals(Facet.YEAR, VodSort.classify("VOD | 2024"))
        assertEquals(Facet.YEAR, VodSort.classify("Movies 2021"))
        assertEquals(Facet.ERA, VodSort.classify("2010-2019"))
        assertEquals(Facet.ERA, VodSort.classify("VOD | 2000 - 2009"))
        assertEquals(Facet.ERA, VodSort.classify("Nineties"))
        assertEquals(Facet.ERA, VodSort.classify("Classic"))
        assertEquals(Facet.KIDS, VodSort.classify("VOD | Kids"))
        assertEquals(Facet.COLLECTION, VodSort.classify("Trending"))
        assertEquals(Facet.COLLECTION, VodSort.classify("Multi Audio"))
        assertEquals(Facet.COLLECTION, VodSort.classify("Modern"))
    }

    @Test
    fun displayNamesDropTheProviderPrefix() {
        assertEquals("4K · English", VodSort.display("4K VOD | English"))
        assertEquals("Hindi 2026", VodSort.display("VOD | Hindi 2026"))
        assertEquals("Fifa world cup 2026", VodSort.display("VOD | Fifa world cup 2026"))
        assertEquals("Trending", VodSort.display("Trending"))
        assertEquals("2010-2019", VodSort.display("2010-2019"))
    }

    @Test
    fun yearsAreReadFromNames() {
        assertEquals(2024, VodSort.yearOf("Dune: Part Two (2024)"))
        assertEquals(2026, VodSort.yearOf("VOD | Hindi 2026"))
        assertEquals(1982, VodSort.yearOf("Blade Runner 2049 - 1982", latest = 2027))
        assertEquals(0, VodSort.yearOf("Se7en"))
        assertEquals(0, VodSort.yearOf("Channel 12345"))
    }

    private fun item(id: String, name: String, cat: String, rating: String = "", added: Long = 0) =
        VodItem("p:m:$id", id, "p", name, "", cat, rating, added, "mp4", series = false)

    @Test
    fun recommendationsFollowRecentViewingAndSkipWhatWasWatched() {
        val hindi = listOf(item("1", "Seen", "hindi", "9", 50), item("2", "Good new", "hindi", "8", 100), item("3", "Poor old", "hindi", "3", 1))
        val english = listOf(item("4", "Other", "english", "9.5", 100))
        val kids = listOf(item("5", "Cartoon", "kids", "10", 100))
        val history = listOf(Watch(VodSort.titleKey("Seen", false), "hindi", 2000), Watch("m|gone", "hindi", 1500), Watch("m|x", "english", 1000))
        val picks = VodSort.recommend(mapOf("hindi" to hindi, "english" to english, "kids" to kids), history)
        assertEquals(listOf("Good new", "Poor old", "Other"), picks.map { it.name })
        assertTrue(VodSort.recommend(mapOf("hindi" to hindi), emptyList()).isEmpty())
    }

    private fun ch(id: String, name: String, group: String = "", tvg: String = "") =
        Channel(id = id, playlistId = "p", name = name, url = "http://h/$id", group = group, tvgId = tvg)

    @Test
    fun arrangementCarriesOverToANewSource() {
        val old = listOf(ch("p:1", "Star Plus HD", "Hindi", "StarPlus.in"), ch("p:2", "Aaj Tak", "News"), ch("p:3", "Local Only", "Misc"), ch("p:4", "Colors HD", "Hindi"))
        val fresh = listOf(
            ch("p:x9", "IN: Star Plus", "India", "starplus.in"),
            ch("p:x10", "AAJ TAK HD", "Other"), ch("p:x11", "Aaj Tak", "News"),
            ch("p:x12", "Colors", "Movies"), ch("p:x13", "Colors FHD", "Hindi"),
        )
        val map = SourceMatch.map(old, fresh, setOf("p:1", "p:2", "p:3", "p:4", "p:missing"))
        assertEquals("p:x9", map["p:1"])   // by guide id
        assertEquals("p:x11", map["p:2"])  // exact name beats the HD variant
        assertEquals(null, map["p:3"])     // not offered by the new provider
        assertEquals("p:x13", map["p:4"])  // loose name, same group preferred
        assertEquals(3, map.size)
    }
}
