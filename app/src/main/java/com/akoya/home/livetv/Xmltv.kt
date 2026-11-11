package com.akoya.home.livetv

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.xml.parsers.SAXParserFactory

/** One programme from a TV guide. Times are in milliseconds since 1970 (UTC). */
data class Programme(val start: Long, val stop: Long, val title: String, val description: String = "") {
    fun progress(now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/**
 * Reads XMLTV, the guide format IPTV providers publish. Guides run to hundreds of megabytes,
 * so this streams through the file and hands each item to a callback instead of loading it.
 */
object Xmltv {
    interface Sink {
        fun channel(id: String, names: List<String>)
        fun programme(channelId: String, start: Long, stop: Long, title: String, description: String)
    }

    fun parse(input: InputStream, sink: Sink) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.newSAXParser().parse(input, Handler(sink))
    }

    /** "20261111063000 +0530" (seconds and zone optional) to epoch milliseconds, or -1. */
    fun time(raw: String): Long {
        val s = raw.trim()
        var n = 0
        while (n < s.length && s[n].isDigit()) n++
        if (n < 12) return -1
        return try {
            val local = LocalDateTime.of(
                s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt(),
                s.substring(8, 10).toInt(), s.substring(10, 12).toInt(),
                if (n >= 14) s.substring(12, 14).toInt() else 0,
            )
            val zone = s.substring(n).trim()
            val offset = when {
                zone.length >= 5 && (zone[0] == '+' || zone[0] == '-') -> {
                    val sign = if (zone[0] == '-') -1 else 1
                    val digits = zone.substring(1).filter { it.isDigit() }
                    ZoneOffset.ofHoursMinutes(sign * digits.substring(0, 2).toInt(), sign * digits.substring(2, 4).toInt())
                }
                else -> ZoneOffset.UTC
            }
            local.toEpochSecond(offset) * 1000
        } catch (e: RuntimeException) {
            -1
        }
    }

    private class Handler(private val sink: Sink) : DefaultHandler() {
        private val text = StringBuilder()
        private var capture = false
        private var channelId: String? = null
        private val names = ArrayList<String>()
        private var progChannel: String? = null
        private var start = -1L
        private var stop = -1L
        private var title = ""
        private var desc = ""

        // Guides name a DTD file that isn't there; never go looking for it.
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            when (qName) {
                "channel" -> { channelId = attrs.getValue("id"); names.clear() }
                "programme" -> {
                    progChannel = attrs.getValue("channel")
                    start = time(attrs.getValue("start").orEmpty())
                    stop = time(attrs.getValue("stop").orEmpty())
                    title = ""; desc = ""
                }
                "display-name" -> if (channelId != null) begin()
                "title" -> if (progChannel != null && title.isEmpty()) begin()
                "desc" -> if (progChannel != null && desc.isEmpty()) begin()
            }
        }

        private fun begin() { text.setLength(0); capture = true }

        override fun characters(ch: CharArray, startAt: Int, length: Int) {
            if (capture && text.length < 2_000) text.append(ch, startAt, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "display-name" -> if (capture) names += text.toString().trim()
                "title" -> if (capture) title = text.toString().trim()
                "desc" -> if (capture) desc = text.toString().trim()
                "channel" -> {
                    channelId?.takeIf { it.isNotBlank() }?.let { sink.channel(it, names.toList()) }
                    channelId = null
                }
                "programme" -> {
                    val c = progChannel
                    if (!c.isNullOrBlank() && start > 0 && stop > start) sink.programme(c, start, stop, title, desc)
                    progChannel = null
                }
            }
            if (qName == "display-name" || qName == "title" || qName == "desc") capture = false
        }
    }
}
