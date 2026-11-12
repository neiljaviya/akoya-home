package com.akoya.home.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    private fun parse(text: String) = M3uParser.parse(text.lineSequence(), "p1")

    @Test
    fun readsAttributesAndTitle() {
        val list = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="DDNews.in" tvg-logo="https://x/dd.png" group-title="News",DD News (1080p)
            https://example.com/ddnews/index.m3u8
            """.trimIndent(),
        )
        assertEquals(1, list.size)
        val c = list[0]
        assertEquals("DD News (1080p)", c.name)
        assertEquals("News", c.group)
        assertEquals("https://x/dd.png", c.logo)
        assertEquals("DDNews.in", c.tvgId)
        assertEquals("https://example.com/ddnews/index.m3u8", c.url)
        assertTrue(c.id.startsWith("p1:"))
    }

    @Test
    fun titleMayContainCommasAndAttributesMayContainCommas() {
        val list = parse(
            "#EXTINF:-1 tvg-name=\"A, B\" group-title=\"Kids, Family\",Tom, Dick and Harry\nhttp://h/1.ts",
        )
        assertEquals("Tom, Dick and Harry", list[0].name)
        assertEquals("Kids, Family", list[0].group)
    }

    @Test
    fun readsVlcOptionsAndKodiHeaders() {
        val list = parse(
            """
            #EXTINF:-1 group-title="Music",One
            #EXTVLCOPT:http-user-agent=Mozilla/5.0 Test
            #EXTVLCOPT:http-referrer=https://ref.example/
            https://example.com/one.m3u8
            #EXTINF:-1,Two
            http://example.com/two|User-Agent=Agent2&Referer=http://r2/
            """.trimIndent(),
        )
        assertEquals("Mozilla/5.0 Test", list[0].userAgent)
        assertEquals("https://ref.example/", list[0].referrer)
        assertEquals("http://example.com/two", list[1].url)
        assertEquals("Agent2", list[1].userAgent)
        assertEquals("http://r2/", list[1].referrer)
        // Options never leak into the next channel.
        assertEquals("", list[1].group)
    }

    @Test
    fun skipsFilmsAndUnplayableLinks() {
        val list = parse(
            """
            #EXTINF:-1,Live
            http://host:8080/live/u/p/101.ts
            #EXTINF:-1,A Film
            http://host:8080/movie/u/p/55.mkv
            #EXTINF:-1,Episode
            http://host:8080/series/u/p/77.mp4
            #EXTINF:-1,Odd
            rtmp://host/app
            """.trimIndent(),
        )
        assertEquals(listOf("Live"), list.map { it.name })
    }

    @Test
    fun duplicateLinksGetDistinctStableIds() {
        val text = "#EXTINF:-1,A\nhttp://h/1\n#EXTINF:-1,B\nhttp://h/1\n"
        val first = parse(text)
        val second = parse(text)
        assertNotEquals(first[0].id, first[1].id)
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun handlesWindowsLineEndingsBomAndExtGrp() {
        val list = M3uParser.parse("﻿#EXTM3U\r\n#EXTINF:-1,Z\r\n#EXTGRP:Sports\r\nhttp://h/z.m3u8\r\n".lineSequence(), "p")
        assertEquals("Z", list[0].name)
        assertEquals("Sports", list[0].group)
    }
}

class ChannelIndexTest {
    private fun ch(id: String, name: String, url: String, tvg: String = "", pl: String = "p1") =
        Channel(id = id, playlistId = pl, name = name, url = url, tvgId = tvg)

    @Test
    fun nameKeyIgnoresQualityAndLabels() {
        assertEquals("aajtak", ChannelIndex.nameKey("Aaj Tak HD (1080p) [Geo-blocked]"))
        assertEquals("aajtak", ChannelIndex.nameKey("aaj tak"))
        assertEquals("&pictures", ChannelIndex.nameKey("&Pictures HD"))
        assertEquals("cn", ChannelIndex.nameKey("CN HD+"))
        assertEquals("starsports1", ChannelIndex.nameKey("Star Sports 1 FHD"))
        assertNotEquals(ChannelIndex.nameKey("Star Sports 1"), ChannelIndex.nameKey("Star Sports 2"))
    }

    @Test
    fun alternatesFindSameChannelAcrossPlaylistsButNotItself() {
        val a = ch("1", "Aaj Tak", "http://a/1")
        val b = ch("2", "Aaj Tak HD", "http://a/2")
        val c = ch("3", "IN: AAJ TAK (720p)", "http://b/9", pl = "p2")
        val d = ch("4", "Zee News", "http://a/4", tvg = "AajTak.in@SD")
        val e = ch("5", "Other", "http://a/5")
        val same = ch("6", "Aaj Tak", "http://a/1")
        val index = ChannelIndex(listOf(a, b, c, d, e, same), emptyList())
        assertEquals(listOf("2", "3"), index.alternates(a).map { it.id })
        assertEquals(listOf("1", "3"), index.alternates(b).map { it.id })
        assertTrue(index.alternates(e).isEmpty())
        val tagged = ch("7", "Aaj Tak", "http://a/7", tvg = "AajTak.in")
        val index2 = ChannelIndex(listOf(tagged, d), emptyList())
        assertEquals(listOf("4"), index2.alternates(tagged).map { it.id })
    }

    @Test
    fun groupsAreListedUnderTheirSection() {
        val list = listOf(
            ch("1", "A", "http://a/1").copy(group = "IN | News"), ch("2", "B", "http://a/2").copy(group = "INR | Tamil"),
            ch("3", "C", "http://a/3").copy(group = "SPORTS | beIN MAX"), ch("4", "##### FI | FINLAND #####", "http://a/4").copy(group = "INT | Scandinavia"),
            ch("5", "D", "http://b/5", pl = "p2").copy(group = "IN | News"),
        )
        val index = ChannelIndex(list, emptyList())
        assertEquals(listOf("Hindi", "News", "News", "South", "Tamil", "Sports", "beIN MAX"), index.groups.map { it.label })
        assertEquals(listOf(true, false, false, true, false, true, false), index.groups.map { it.isPlaylist })
        // Sections decide the channel order, and separator lines are not channels.
        assertEquals(listOf("1", "5", "2", "3"), index.all.map { it.id })
        assertEquals(2, index.channels("s:Hindi", emptyList()).size)
        assertEquals(1, index.channels("p:p2", emptyList()).size)
        assertEquals("Hindi", index.sectionOf("p1/IN | News"))
        assertEquals("Sports", index.sectionOf("s:Sports"))
        val moved = ChannelIndex(list, emptyList(), ChannelPrefs(groupSections = mapOf("p1/INR | Tamil" to "Hindi")))
        assertEquals("Hindi", moved.sectionOf("p1/INR | Tamil"))
        assertEquals(3, moved.channels("s:Hindi", emptyList()).size)
    }
}

class ChannelManagementTest {
    private fun ch(id: String, name: String, group: String, pl: String = "p1") =
        Channel(id = id, playlistId = pl, name = name, url = "http://h/$id", group = group)

    private val raw = listOf(ch("1", "Zed", "News"), ch("2", "Alpha", "News"), ch("3", "Kid", "Kids"), ch("4", "Film", "Movies"))

    @Test
    fun hiddenGroupsAndChannelsLeaveTheListsButStayManageable() {
        val index = ChannelIndex(raw, emptyList(), ChannelPrefs(hiddenGroups = setOf("p1/Kids"), hiddenChannels = setOf("4")))
        assertEquals(listOf("1", "2"), index.all.map { it.id })
        assertEquals(listOf("Rest of the world", "News"), index.groups.map { it.label })
        assertEquals(1, index.hiddenChannelCount)
        val kids = index.allGroups.first { it.key == "p1/Kids" }
        assertTrue(kids.hidden)
        assertEquals(1, kids.count)
        assertEquals(null, index.byId["3"])
    }

    @Test
    fun renamesSortingAndGroupNames() {
        val index = ChannelIndex(raw, emptyList(), ChannelPrefs(renames = mapOf("1" to "Aardvark"), sortAz = true, groupNames = mapOf("p1/News" to "Headlines")))
        assertEquals(listOf("Aardvark", "Alpha", "Film", "Kid"), index.all.map { it.name })
        assertEquals(1, index.numberOf["1"])
        assertEquals("Headlines", index.label("p1/News"))
        assertEquals(listOf("Aardvark", "Alpha"), index.channels("p1/News", emptyList()).map { it.name })
    }

    @Test
    fun categoriesComeFirstKeepTheirOrderAndSkipOffAirChannels() {
        val prefs = ChannelPrefs(categories = listOf(Category("a", "Evening", listOf("3", "1", "gone", "2"))), offAir = setOf("2"))
        val index = ChannelIndex(raw, emptyList(), prefs)
        assertEquals("c:a", index.groups.first().key)
        assertTrue(index.groups.first().isCustom)
        assertEquals(listOf("3", "1"), index.channels("c:a", emptyList()).map { it.id })
        assertEquals(listOf("1", "3", "4"), index.all.map { it.id })
    }
}

/** Checked against the group names of a real 27,000-channel provider list. */
class ChannelSortTest {
    private fun s(group: String, playlist: String = "Main") = ChannelSort.sectionOf(group, playlist)

    @Test
    fun sections() {
        assertEquals(ChannelSort.HINDI, s("IN | News"))
        assertEquals(ChannelSort.HINDI, s("IN | Entertainment"))
        assertEquals(ChannelSort.GUJARATI, s("INR  | Gujrati"))
        assertEquals(ChannelSort.SOUTH, s("INR | Tamil"))
        assertEquals(ChannelSort.SOUTH, s("INR | Malayalam"))
        assertEquals(ChannelSort.REST, s("INR | Punjabi"))
        assertEquals(ChannelSort.REST, s("INR | Punjabi Religious"))
        assertEquals(ChannelSort.REST, s("PK | Entertainment"))
        assertEquals(ChannelSort.REST, s("INT | Germany"))
        assertEquals(ChannelSort.REST, s("AR | Gulf GCC"))
        assertEquals(ChannelSort.REST, s("Latin"))
        assertEquals(ChannelSort.CANADA, s("CA | Entertainment"))
        assertEquals(ChannelSort.USA, s("US | Locals"))
        assertEquals(ChannelSort.UK, s("UK | Entertainment"))
        assertEquals(ChannelSort.UK, s("IE | Irish TV"))
        assertEquals(ChannelSort.ROUND_THE_CLOCK, s("24/7 | Hindi"))
        assertEquals(ChannelSort.ROUND_THE_CLOCK, s("24/7 | English"))
        assertEquals(ChannelSort.ADULT, s("XXX | Adults"))
        assertEquals(ChannelSort.ADULT, s("XXX | Indian Adults"))
        for (g in listOf("SPORTS | beIN MAX", "LIVE | EPL Hub", "PPV", "US | NFL Teams", "US | Sports", "CA | Sports", "UK | Sky Sports", "UK | TNT Sports",
            "IN/PK | Sports", " | IPL", "Asian Sports", "Chinese Sport", "Fite TV", "NL | Sports", "DSTV | Sports", "LIVE | Cricket Events", "US | MLB")) {
            assertEquals(g, ChannelSort.SPORTS, s(g))
        }
        // The free lists have no region in their group names: the playlist decides.
        assertEquals(ChannelSort.HINDI, s("News", "India (free)"))
        assertEquals(ChannelSort.USA, s("Movies", "USA (free)"))
        assertEquals(ChannelSort.SPORTS, s("Sports", "India (free)"))
    }

    @Test
    fun labelsAndSeparators() {
        assertEquals("News", ChannelSort.label("IN | News", ChannelSort.HINDI))
        assertEquals("Gujrati", ChannelSort.label("INR  | Gujrati", ChannelSort.GUJARATI))
        assertEquals("beIN MAX", ChannelSort.label("SPORTS | beIN MAX", ChannelSort.SPORTS))
        assertEquals("CA Sports", ChannelSort.label("CA | Sports", ChannelSort.SPORTS))
        assertEquals("Germany", ChannelSort.label("INT | Germany", ChannelSort.REST))
        assertEquals("PK News", ChannelSort.label("PK | News", ChannelSort.REST))
        assertEquals("IPL", ChannelSort.label(" | IPL", ChannelSort.SPORTS))
        assertEquals("Latin", ChannelSort.label("Latin", ChannelSort.REST))
        assertEquals("Other", ChannelSort.label("", ChannelSort.REST))
        assertTrue(ChannelSort.isSeparator("##### FI | FINLAND #####"))
        assertTrue(ChannelSort.isSeparator("•●★- GREECE DOCUMENTARY UHD 4K -★●•"))
        assertEquals(false, ChannelSort.isSeparator("UK-BBC One FHD"))
        assertEquals(false, ChannelSort.isSeparator(".UK = Absolute Classic Rock"))
        assertEquals(false, ChannelSort.isSeparator("4K | Star 4K (Ads Free)"))
    }
}
