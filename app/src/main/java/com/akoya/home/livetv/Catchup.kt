package com.akoya.home.livetv

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds the link for watching something that has already aired ("catch-up").
 * Providers do this in a handful of ways; the playlist says which one each channel uses.
 * Pure Kotlin so every style is unit-tested.
 */
object Catchup {
    private val XC_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")
    private val XC_LIVE = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.\w+)?$""")

    /**
     * @param startMs when the wanted programme (or point in time) began
     * @param stopMs when it ended; a programme still on air ends "now"
     * @param serverZone the provider's time zone (Xtream-style links use the server's clock)
     * @return the link, or null when this channel has no usable catch-up
     */
    fun url(ch: Channel, startMs: Long, stopMs: Long, nowMs: Long, serverZone: ZoneId = ZoneOffset.UTC): String? {
        if (!ch.hasCatchup) return null
        val start = startMs / 1000
        val end = (minOf(stopMs, nowMs) / 1000).coerceAtLeast(start + 60)
        val now = nowMs / 1000
        val duration = end - start
        return when (ch.catchup) {
            "xc" -> {
                val m = XC_LIVE.find(ch.url.substringBefore('?')) ?: return null
                val (host, user, pass, id) = m.destructured
                val at = XC_TIME.format(Instant.ofEpochSecond(start).atZone(serverZone))
                "$host/timeshift/$user/$pass/${(duration + 59) / 60}/$at/$id.ts"
            }
            "shift" -> ch.url + (if ('?' in ch.url) "&" else "?") + "utc=$start&lutc=$now"
            "append" -> ch.url + fill(ch.catchupSource, start, end, now)
            "default" -> fill(ch.catchupSource, start, end, now)
            "flussonic" -> {
                val query = ch.url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
                val path = ch.url.substringBefore('?')
                val base = path.substringBeforeLast('/')
                val file = path.substringAfterLast('/')
                when {
                    file.endsWith(".m3u8") -> "$base/${file.removeSuffix(".m3u8").substringBefore('-')}-$start-$duration.m3u8$query"
                    else -> "$base/timeshift_abs-$start.ts$query"
                }
            }
            else -> null
        }
    }

    /** Replaces the placeholders providers use in catch-up link templates. */
    fun fill(template: String, start: Long, end: Long, now: Long): String {
        val t = Instant.ofEpochSecond(start).atZone(ZoneOffset.UTC)
        fun two(n: Int) = n.toString().padStart(2, '0')
        var s = template
        val values = listOf(
            "utc" to start.toString(), "start" to start.toString(),
            "lutc" to now.toString(), "now" to now.toString(), "timestamp" to now.toString(),
            "utcend" to end.toString(), "end" to end.toString(),
            "duration" to (end - start).toString(),
            "offset" to (now - start).toString(),
            "Y" to t.year.toString(), "m" to two(t.monthValue), "d" to two(t.dayOfMonth),
            "H" to two(t.hour), "M" to two(t.minute), "S" to two(t.second),
        )
        for ((k, v) in values) s = s.replace("\${$k}", v).replace("{$k}", v)
        return s
    }

    /** True when [startMs] is still inside the channel's archive. */
    fun covers(ch: Channel, startMs: Long, nowMs: Long): Boolean =
        ch.hasCatchup && startMs < nowMs && startMs > nowMs - ch.catchupDays * 86_400_000L
}
