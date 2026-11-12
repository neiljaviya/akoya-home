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

/** Rules checked against names taken from a real provider's library. */
class RegionAndGenreTest {
    @Test
    fun regionsFromTitleMarkersBeatTheCategory() {
        assertEquals(Region.REST, VodSort.regionOf("Kayıkçı (1999) [TUR]", "VOD | English 2010-2019"))
        assertEquals(Region.REST, VodSort.regionOf("Knock, Knock, It's Mom! (2021) [FRN]", "VOD | English 2021"))
        assertEquals(Region.BOLLYWOOD, VodSort.regionOf("The 100 (2025) [HIN+TEL]", "VOD | MultiAudio"))
        assertEquals(Region.BOLLYWOOD, VodSort.regionOf("The Early Spring (2026) [HIN]", "Netflix Series"))
        assertEquals(Region.HOLLYWOOD, VodSort.regionOf("Dead-End Job (2026) [ENG]", "Netflix Series"))
        assertEquals(Region.GUJARATI, VodSort.regionOf("Geeta 2026 (GUJARATI)", "Indian Daily Soap"))
        assertEquals(Region.REST, VodSort.regionOf("Hastinapurche Veer (MARATHI)", "Indian Daily Soap"))
        assertEquals(Region.SOUTH, VodSort.regionOf("Some Show (TAMIL)", "Indian Daily Soap"))
        assertEquals(Region.HOLLYWOOD, VodSort.regionOf("Film (2024) [HDTC]", "VOD | English 2024"))
        assertEquals(Region.HOLLYWOOD, VodSort.regionOf("Eagles of Death Metal: Nos Amis (Our Friends) - 2017", "VOD | Music"))
    }

    @Test
    fun regionsFromCategories() {
        assertEquals(Region.HOLLYWOOD, VodSort.regionOf("Barbie - 2023", "4K VOD | English"))
        assertEquals(Region.HOLLYWOOD, VodSort.regionOf("Civil War - 2024 | MultiAudio", "VOD | MultiAudio"))
        assertEquals(Region.HOLLYWOOD, VodSort.regionOf("Skyfall - 2012", "VOD | 007 James Bond"))
        assertEquals(Region.HOLLYWOOD, VodSort.regionOf("Better Call Saul", "Hotstar"))
        assertEquals(Region.BOLLYWOOD, VodSort.regionOf("Coolie - 1983", "VOD | Hindi Old is Gold"))
        assertEquals(Region.BOLLYWOOD, VodSort.regionOf("Mere Ghar Ram Aaye Hain", "Indian Daily Soap"))
        assertEquals(Region.BOLLYWOOD, VodSort.regionOf("X", "HotStar Series - Hindi"))
        assertEquals(Region.BOLLYWOOD, VodSort.regionOf("Dil Padosi (2026)", "Hindi / Gujrati Shemaroo"))
        assertEquals(Region.SOUTH, VodSort.regionOf("Zombie Reddy - 2021", "VOD | Telugu Modern"))
        assertEquals(Region.SOUTH, VodSort.regionOf("X", "Hindi SonyLiV - Tamil"))
        assertEquals(Region.SOUTH, VodSort.regionOf("X", "Hotstar Series - Kannada"))
        assertEquals(Region.GUJARATI, VodSort.regionOf("Vahu Hi-Fi Sasu Wi-Fi", "VOD | Gujarati Natak"))
        assertEquals(Region.REST, VodSort.regionOf("X", "VOD | Punjabi Modern"))
        assertEquals(Region.REST, VodSort.regionOf("X", "Pakistani Dramas"))
        assertEquals(Region.REST, VodSort.regionOf("ZARA THORA JINHA", "Other"))
        assertEquals(Region.REST, VodSort.regionOf("X", "Afrikaans Series"))
    }

    @Test
    fun genres() {
        assertEquals("Sci-Fi", VodSort.genreOfCategory("VOD | Science Fiction"))
        assertEquals("Comedy", VodSort.genreOfCategory("VOD | Comedy"))
        assertEquals(null, VodSort.genreOfCategory("VOD | English 2023"))
        assertEquals(null, VodSort.genreOfCategory("VOD | TV Movie"))
        assertEquals(listOf("Action", "Adventure", "Sci-Fi"), VodSort.genresOf("Action & Adventure, Science Fiction"))
        assertEquals(listOf("Sci-Fi", "Fantasy"), VodSort.genresOf("Sci-Fi & Fantasy"))
        assertEquals(listOf("Drama", "Crime"), VodSort.genresOf("Drama / Crime / Soap"))
        assertEquals(emptyList<String>(), VodSort.genresOf(""))
    }

    @Test
    fun copiesOfOneFilmShareAKeyAndACleanTitle() {
        val a = VodSort.titleKey("The Suicide Squad - 2021", false)
        assertEquals(a, VodSort.titleKey("The Suicide Squad (2021)", false))
        assertEquals(a, VodSort.titleKey("The Suicide Squad - 2021 | Multi Audio", false))
        assertEquals(a, VodSort.titleKey("the suicide squad (2021) [4K]", false))
        assertTrue(a != VodSort.titleKey("The Suicide Squad - 2016", false))
        assertTrue(a != VodSort.titleKey("The Suicide Squad - 2021", true))
        assertEquals("Civil War", VodSort.cleanTitle("Civil War - 2024 | MultiAudio"))
        assertEquals("Kayıkçı", VodSort.cleanTitle("Kayıkçı (1999) [TUR]"))
        assertEquals("Geeta 2026", VodSort.cleanTitle("Geeta 2026 (GUJARATI)"))
        assertEquals("Us", VodSort.cleanTitle("Us (2019)"))
        assertEquals("Blade Runner 2049", VodSort.cleanTitle("Blade Runner 2049 - 2017"))
        assertEquals("UFC 333 Prelims", VodSort.cleanTitle("UFC 333 Prelims"))
    }

    @Test
    fun yearShelvesAndPlainLanguageShelves() {
        assertEquals("2026", VodSort.eraOf(2026, 2026))
        assertEquals("2020", VodSort.eraOf(2020, 2026))
        assertEquals("2010s", VodSort.eraOf(2019, 2026))
        assertEquals("90s", VodSort.eraOf(1994, 2026))
        assertEquals("Classics", VodSort.eraOf(1957, 2026))
        assertEquals("", VodSort.eraOf(0, 2026))
        assertTrue(VodSort.isLanguageYearShelf("VOD | English 2010-2019"))
        assertTrue(VodSort.isLanguageYearShelf("VOD | Tamil (2024)"))
        assertTrue(VodSort.isLanguageYearShelf("VOD | Hindi 90s"))
        assertTrue(VodSort.isLanguageYearShelf("VOD | Hindi"))
        assertEquals(false, VodSort.isLanguageYearShelf("VOD | Hindi Trending"))
        assertEquals(false, VodSort.isLanguageYearShelf("VOD | Hollywood Modern"))
        assertEquals(false, VodSort.isLanguageYearShelf("VOD | 007 James Bond"))
        assertEquals(false, VodSort.isLanguageYearShelf("Netflix Series"))
    }
}
