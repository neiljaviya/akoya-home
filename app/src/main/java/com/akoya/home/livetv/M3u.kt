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
)

/**
 * Reads M3U / M3U8 channel lists (the "#EXTINF" format every IPTV provider uses).
 * Pure Kotlin so it can be unit-tested off the TV.
 */
object M3uParser {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(lines: Sequence<String>, playlistId: String): List<Channel> {
        val out = ArrayList<Channel>()
        val seen = HashSet<String>()
        var name: String? = null
        var logo = ""
        var group = ""
        var tvgId = ""
        var userAgent = ""
        var referrer = ""

        fun reset() {
            name = null; logo = ""; group = ""; tvgId = ""; userAgent = ""; referrer = ""
        }

        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            when {
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
                        )
                    }
                    reset()
                }
            }
        }
        return out
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
