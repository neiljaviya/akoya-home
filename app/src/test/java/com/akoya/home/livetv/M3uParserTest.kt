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
    fun groupsListPlaylistsOnlyWhenThereAreSeveral() {
        val one = ChannelIndex(listOf(ch("1", "A", "http://a/1").copy(group = "News"), ch("2", "B", "http://a/2").copy(group = "Kids")), emptyList())
        assertEquals(listOf("News", "Kids"), one.groups.map { it.label })
        val two = ChannelIndex(listOf(ch("1", "A", "http://a/1"), ch("2", "B", "http://b/2", pl = "p2")), emptyList())
        assertEquals(listOf(true, true), two.groups.map { it.isPlaylist })
        assertEquals(1, two.channels("p:p2", emptyList()).size)
    }
}

class GuideAndCatchupTest {
    @Test
    fun headerGuideLinksAndCatchupAreRead() {
        val text = """
            #EXTM3U url-tvg="http://epg.example/a.xml.gz, http://epg.example/b.xml" catchup="shift" catchup-days="5"
            #EXTINF:-1 tvg-id="one",One
            http://h/one.m3u8
            #EXTINF:-1 tvg-id="two" catchup="default" catchup-source="http://h/two-{utc}-{duration}.m3u8" catchup-days="2",Two
            http://h/two.m3u8
        """.trimIndent()
        val parsed = M3uParser.parsePlaylist(text.lineSequence(), "p")
        assertEquals(listOf("http://epg.example/a.xml.gz", "http://epg.example/b.xml"), parsed.epgUrls)
        assertEquals("shift", parsed.channels[0].catchup)
        assertEquals(5, parsed.channels[0].catchupDays)
        assertEquals("default", parsed.channels[1].catchup)
        assertEquals(2, parsed.channels[1].catchupDays)
        assertTrue(parsed.channels[1].hasCatchup)
    }

    @Test
    fun timeshiftAttributeMeansXtreamStyleAndPlainChannelsHaveNone() {
        val parsed = M3uParser.parsePlaylist("#EXTM3U\n#EXTINF:-1 timeshift=\"3\",A\nhttp://h:80/live/u/p/7.ts\n#EXTINF:-1,B\nhttp://h/b\n".lineSequence(), "p")
        assertEquals("xc", parsed.channels[0].catchup)
        assertEquals(3, parsed.channels[0].catchupDays)
        assertEquals("", parsed.channels[1].catchup)
        assertEquals(false, parsed.channels[1].hasCatchup)
    }

    private fun ch(url: String, type: String, source: String = "") =
        Channel(id = "1", playlistId = "p", name = "N", url = url, catchup = type, catchupSource = source, catchupDays = 3)

    // 2026-11-11 06:30:00 UTC, one hour long; "now" is two hours after the start.
    private val start = 1_794_378_600_000L
    private val stop = start + 3_600_000
    private val now = start + 7_200_000

    @Test
    fun xtreamCatchupUsesServerClockAndMinutes() {
        val url = Catchup.url(ch("http://h:8080/live/user/pass/77.ts", "xc"), start, stop, now, java.time.ZoneId.of("Asia/Kolkata"))
        assertEquals("http://h:8080/timeshift/user/pass/60/2026-11-11:12-00/77.ts", url)
        val short = Catchup.url(ch("http://h:8080/user/pass/77", "xc"), start, stop, now)
        assertEquals("http://h:8080/timeshift/user/pass/60/2026-11-11:06-30/77.ts", short)
    }

    @Test
    fun otherCatchupStyles() {
        val s = start / 1000
        assertEquals("http://h/a.m3u8?utc=$s&lutc=${now / 1000}", Catchup.url(ch("http://h/a.m3u8", "shift"), start, stop, now))
        assertEquals("http://h/a.m3u8?t=1&utc=$s&lutc=${now / 1000}", Catchup.url(ch("http://h/a.m3u8?t=1", "shift"), start, stop, now))
        assertEquals("http://h/a.m3u8?from=$s&len=3600", Catchup.url(ch("http://h/a.m3u8", "append", "?from=\${start}&len={duration}"), start, stop, now))
        assertEquals("http://x/2026/11/11/06-30.ts?o=7200", Catchup.url(ch("http://h/a", "default", "http://x/{Y}/{m}/{d}/{H}-{M}.ts?o={offset}"), start, stop, now))
        assertEquals("http://h/ch/index-$s-3600.m3u8?token=9", Catchup.url(ch("http://h/ch/index.m3u8?token=9", "flussonic"), start, stop, now))
        assertEquals("http://h/ch/timeshift_abs-$s.ts", Catchup.url(ch("http://h/ch/mpegts", "flussonic"), start, stop, now))
    }

    @Test
    fun programmeStillOnAirEndsNowAndNoCatchupGivesNull() {
        val live = Catchup.url(ch("http://h/a.m3u8", "append", "?d={duration}"), start, start + 4 * 3_600_000, now)
        assertEquals("http://h/a.m3u8?d=7200", live)
        assertEquals(null, Catchup.url(Channel(id = "1", playlistId = "p", name = "N", url = "http://h/a"), start, stop, now))
        assertTrue(Catchup.covers(ch("http://h/a", "shift"), now - 2 * 86_400_000L, now))
        assertEquals(false, Catchup.covers(ch("http://h/a", "shift"), now - 4 * 86_400_000L, now))
        assertEquals(false, Catchup.covers(ch("http://h/a", "shift"), now + 1000, now))
    }

    @Test
    fun xmltvTimes() {
        assertEquals(start, Xmltv.time("20261111063000 +0000"))
        assertEquals(start, Xmltv.time("20261111120000 +0530"))
        assertEquals(start, Xmltv.time("20261111013000 -0500"))
        assertEquals(start, Xmltv.time("202611110630"))
        assertEquals(-1L, Xmltv.time("soon"))
    }

    @Test
    fun xmltvStreamIsRead() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv>
              <channel id="AajTak.in"><display-name>Aaj Tak</display-name><display-name>AAJ TAK HD</display-name><icon src="x"/></channel>
              <programme start="20261111063000 +0000" stop="20261111073000 +0000" channel="AajTak.in">
                <title lang="hi">News &amp; Views</title><title lang="en">Second title</title><desc>Morning bulletin.</desc><category>News</category>
              </programme>
              <programme start="bad" stop="20261111073000 +0000" channel="AajTak.in"><title>Skipped</title></programme>
            </tv>"""
        val channels = ArrayList<Pair<String, List<String>>>()
        val progs = ArrayList<Programme>()
        Xmltv.parse(xml.byteInputStream(), object : Xmltv.Sink {
            override fun channel(id: String, names: List<String>) { channels += id to names }
            override fun programme(channelId: String, start: Long, stop: Long, title: String, description: String) {
                assertEquals("AajTak.in", channelId)
                progs += Programme(start, stop, title, description)
            }
        })
        assertEquals(listOf("AajTak.in" to listOf("Aaj Tak", "AAJ TAK HD")), channels)
        assertEquals(listOf(Programme(start, stop, "News & Views", "Morning bulletin.")), progs)
        assertEquals(0.5f, progs[0].progress(start + 1_800_000), 0.001f)
    }

    @Test
    fun frameRateChoice() {
        assertEquals(50f, FrameRate.pick(25f, listOf(50f, 60f), 60f))
        assertEquals(null, FrameRate.pick(30f, listOf(50f, 60f), 60f))
        assertEquals(60f, FrameRate.pick(60f, listOf(50f, 60f), 50f))
        assertEquals(null, FrameRate.pick(25f, listOf(60f), 60f))
        assertEquals(24f, FrameRate.pick(23.976f, listOf(24f, 50f, 60f), 60f))
        assertEquals(59.94f, FrameRate.pick(29.97f, listOf(50f, 59.94f, 60f), 60f))
        assertEquals(null, FrameRate.pick(29.97f, listOf(50f, 60f), 60f))
        assertEquals(25f, FrameRate.snap(25.02f))
        assertEquals(0f, FrameRate.snap(41f))
        val times = LongArray(30) { it * 40_000L }
        assertEquals(25f, FrameRate.fromTimestamps(times, 30))
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
        assertEquals(listOf("News"), index.groups.map { it.label })
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
