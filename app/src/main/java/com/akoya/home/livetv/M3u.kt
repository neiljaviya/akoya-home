package com.akoya.home.livetv

/** One live channel from a playlist. */
data class Channel(
    val id: String,
    val playlistId: String,
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "",
    val tvgId: String = "",
    val userAgent: String = "",
    val referrer: String = "",
    /** Catch-up style: "" (none), "xc", "shift", "append", "default" or "flussonic". */
    val catchup: String = "",
    /** Link template for "append" and "default" catch-up. */
    val catchupSource: String = "",
    /** How many days back the provider keeps this channel. */
    val catchupDays: Int = 0,
) {
    val hasCatchup: Boolean get() = catchup.isNotEmpty() && catchupDays > 0
}

/** A parsed playlist: its channels and any guide (XMLTV) links named in its header. */
class ParsedPlaylist(val channels: List<Channel>, val epgUrls: List<String>)

/**
 * Reads M3U / M3U8 channel lists (the "#EXTINF" format every IPTV provider uses).
 * Pure Kotlin so it can be unit-tested off the TV.
 */
object M3uParser {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(lines: Sequence<String>, playlistId: String): List<Channel> = parsePlaylist(lines, playlistId).channels

    fun parsePlaylist(lines: Sequence<String>, playlistId: String): ParsedPlaylist {
        val out = ArrayList<Channel>()
        val epg = ArrayList<String>()
        var listCatchup = ""
        var listCatchupSource = ""
        var listCatchupDays = 0
        var catchup = ""
        var catchupSource = ""
        var catchupDays = 0
        val seen = HashSet<String>()
        var name: String? = null
        var logo = ""
        var group = ""
        var tvgId = ""
        var userAgent = ""
        var referrer = ""

        fun reset() {
            name = null; logo = ""; group = ""; tvgId = ""; userAgent = ""; referrer = ""
            catchup = ""; catchupSource = ""; catchupDays = 0
        }

        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    for (m in ATTR.findAll(line)) {
                        val v = m.groupValues[2].trim()
                        when (m.groupValues[1].lowercase()) {
                            "url-tvg", "x-tvg-url", "tvg-url" ->
                                v.split(',').map { it.trim() }.filter { it.startsWith("http", true) }.forEach { if (it !in epg) epg += it }
                            "catchup", "catchup-type" -> listCatchup = v.lowercase()
                            "catchup-source" -> listCatchupSource = v
                            "catchup-days" -> listCatchupDays = v.toIntOrNull() ?: 0
                        }
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    reset()
                    val comma = titleComma(line)
                    val head = if (comma >= 0) line.substring(0, comma) else line
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    for (m in ATTR.findAll(head)) {
                        val v = m.groupValues[2].trim()
                        when (m.groupValues[1].lowercase()) {
                            "tvg-logo", "logo" -> logo = v
                            "group-title" -> group = v
                            "tvg-id" -> tvgId = v
                            "tvg-name" -> if (name.isNullOrBlank()) name = v
                            "user-agent", "http-user-agent" -> userAgent = v
                            "referrer", "http-referrer" -> referrer = v
                            "catchup", "catchup-type" -> catchup = v.lowercase()
                            "catchup-source" -> catchupSource = v
                            "catchup-days" -> catchupDays = v.toIntOrNull() ?: catchupDays
                            // Older spellings: days of archive, with the Xtream-style link.
                            "timeshift", "tvg-rec" -> (v.toIntOrNull() ?: 0).let { d -> if (d > 0 && catchupDays == 0) catchupDays = d }
                        }
                    }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> if (group.isBlank()) group = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11)
                    val eq = opt.indexOf('=')
                    if (eq > 0) {
                        val v = opt.substring(eq + 1).trim()
                        when (opt.substring(0, eq).trim().lowercase()) {
                            "http-user-agent" -> userAgent = v
                            "http-referrer", "http-referer" -> referrer = v
                        }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    var url = line
                    // Kodi style: http://host/stream|User-Agent=x&Referer=y
                    val bar = url.indexOf('|')
                    if (bar > 0) {
                        for (pair in url.substring(bar + 1).split('&')) {
                            val eq = pair.indexOf('=')
                            if (eq <= 0) continue
                            val v = pair.substring(eq + 1).trim()
                            when (pair.substring(0, eq).trim().lowercase()) {
                                "user-agent" -> if (userAgent.isBlank()) userAgent = v
                                "referer", "referrer" -> if (referrer.isBlank()) referrer = v
                            }
                        }
                        url = url.substring(0, bar).trim()
                    }
                    // Channel settings win over the playlist-wide ones in the header.
                    val cuSource = catchupSource.ifBlank { listCatchupSource }
                    val declaredDays = if (catchupDays > 0) catchupDays else listCatchupDays
                    val cuType = normaliseCatchup(
                        catchup.ifBlank { listCatchup }.ifBlank {
                            // Only a day count given ("timeshift=3"): that's the Xtream style.
                            if (declaredDays > 0) (if (cuSource.isBlank()) "xc" else "default") else ""
                        },
                        cuSource,
                    )
                    // A catch-up type with no day count: assume a short archive.
                    val cuDays = if (cuType.isEmpty()) 0 else if (declaredDays > 0) declaredDays else 3
                    if (isPlayable(url) && !isVod(url)) {
                        var id = playlistId + ":" + hash(url)
                        var n = 1
                        while (!seen.add(id)) id = playlistId + ":" + hash(url) + "-" + (++n)
                        out += Channel(
                            id = id,
                            playlistId = playlistId,
                            name = name?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "Channel" },
                            url = url,
                            logo = logo,
                            group = group.substringBefore(';').trim(),
                            tvgId = tvgId,
                            userAgent = userAgent,
                            referrer = referrer,
                            catchup = cuType,
                            catchupSource = cuSource,
                            catchupDays = cuDays,
                        )
                    }
                    reset()
                }
            }
        }
        return ParsedPlaylist(out, epg)
    }

    private fun normaliseCatchup(type: String, source: String): String = when (type) {
        "" -> ""
        "xc", "xtream", "xtream-codes" -> "xc"
        "shift", "timeshift" -> "shift"
        "append" -> if (source.isBlank()) "shift" else "append"
        "default" -> if (source.isBlank()) "shift" else "default"
        "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> "flussonic"
        else -> if (source.isNotBlank()) "default" else ""
    }

    /** Index of the comma that separates the attributes from the channel title. */
    private fun titleComma(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return i
            }
        }
        // Unbalanced quotes: fall back to the last comma.
        return line.lastIndexOf(',')
    }

    private fun isPlayable(url: String): Boolean =
        url.startsWith("http://", true) || url.startsWith("https://", true)

    /** Provider playlists often mix in films and series; Live TV only keeps the channels. */
    private fun isVod(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return "/movie/" in path || "/series/" in path ||
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi")
    }

    /** Stable 64-bit FNV-1a hash, so a channel keeps its id (and its favourite star) across refreshes. */
    fun hash(s: String): String {
        var h = -0x340d631b7bdddcdbL
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h)
    }
}
