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
