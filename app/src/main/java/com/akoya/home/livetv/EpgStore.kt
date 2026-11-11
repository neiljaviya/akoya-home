package com.akoya.home.livetv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** What's on a channel now and what follows. */
class NowNext(val now: Programme?, val next: Programme?)

/** The channels a guide download should keep: everything else in the file is skipped. */
class GuideWanted(val ids: Set<String>, val names: Set<String>)

/**
 * The TV guide. Programmes live in a small database on the TV (guides are far too big to
 * hold in memory on a television), keyed by the guide's own channel id. A channel finds its
 * guide by its playlist id ("tvg-id") or, failing that, by name, which is how free channels
 * can borrow the guide that came with another playlist.
 */
class EpgStore private constructor(context: Context) {
    private val helper = object : SQLiteOpenHelper(context, "akoya_epg.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE prog (src TEXT NOT NULL, ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL, descr TEXT NOT NULL)")
            db.execSQL("CREATE INDEX prog_ch ON prog (ch, start)")
            db.execSQL("CREATE INDEX prog_src ON prog (src)")
            db.execSQL("CREATE TABLE alias (src TEXT NOT NULL, name TEXT NOT NULL, ch TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS prog")
            db.execSQL("DROP TABLE IF EXISTS alias")
            onCreate(db)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }
    }

    /** Guide channel ids that have programmes, and channel-name to guide-id links. */
    @Volatile private var ids: Set<String> = emptySet()
    @Volatile private var aliases: Map<String, String> = emptyMap()
    private val cache = ConcurrentHashMap<String, NowNext>()

    /** Goes up whenever the guide's contents change, so screens know to look again. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    val loading: StateFlow<Set<String>> = _loading.asStateFlow()

    /** Call once, off the main thread, before the first lookup. */
    fun load() {
        runCatching { reloadKeys() }.onFailure { Log.w(TAG, "Guide database unreadable", it) }
        _version.update { it + 1 }
    }

    val isEmpty: Boolean get() = ids.isEmpty()

    /** The guide id for a channel, or null when the guide has nothing for it. */
    fun keyFor(ch: Channel): String? {
        if (ids.isEmpty()) return null
        if (ch.tvgId.isNotBlank()) {
            val full = ch.tvgId.lowercase()
            if (full in ids) return full
            val base = full.substringBefore('@')
            if (base in ids) return base
        }
        val name = ChannelIndex.nameKey(ch.name)
        return if (name.length >= 3) aliases[name] else null
    }

    /** A remembered answer, if it is still current. Safe on the main thread. */
    fun cached(key: String, now: Long): NowNext? =
        cache[key]?.takeIf { nn -> nn.now?.let { now < it.stop && now >= it.start } ?: (nn.next?.let { now < it.start } ?: false) }

    /** Reads the database: call off the main thread. */
    fun nowNext(key: String, now: Long): NowNext {
        cached(key, now)?.let { return it }
        val list = query(key, "stop > ?", now, 3)
        val current = list.firstOrNull { it.start <= now }
        val next = list.firstOrNull { it.start > now && it.start >= (current?.stop ?: 0L) - 60_000 }
        return NowNext(current, next).also { if (cache.size < 4_000) cache[key] = it }
    }

    /** Programmes that overlap [from]..[to], oldest first. Call off the main thread. */
    fun range(key: String, from: Long, to: Long): List<Programme> {
        val out = ArrayList<Programme>()
        runCatching {
            helper.readableDatabase.rawQuery(
                "SELECT start, stop, title, descr FROM prog WHERE ch = ? AND stop > ? AND start < ? ORDER BY start LIMIT 200",
                arrayOf(key, from.toString(), to.toString()),
            ).use { c ->
                var lastStart = -1L
                while (c.moveToNext()) {
                    val start = c.getLong(0)
                    // The same channel can arrive from two guides; keep one copy.
                    if (start == lastStart) continue
                    lastStart = start
                    out += Programme(start, c.getLong(1), c.getString(2), c.getString(3))
                }
            }
        }
        return out
    }

    private fun query(key: String, where: String, arg: Long, limit: Int): List<Programme> {
        val out = ArrayList<Programme>(limit)
        runCatching {
            helper.readableDatabase.rawQuery(
                "SELECT start, stop, title, descr FROM prog WHERE ch = ? AND $where ORDER BY start LIMIT ${limit * 2}",
                arrayOf(key, arg.toString()),
            ).use { c ->
                var lastStart = -1L
                while (c.moveToNext() && out.size < limit) {
                    val start = c.getLong(0)
                    if (start == lastStart) continue
                    lastStart = start
                    out += Programme(start, c.getLong(1), c.getString(2), c.getString(3))
                }
            }
        }
        return out
    }

    /**
     * Downloads the guide for one playlist and replaces what was stored for it.
     * The old guide stays if anything goes wrong. Returns how many programmes were kept.
     */
    fun refresh(src: String, urls: List<String>, wanted: GuideWanted, keepPastMs: Long, keepFutureMs: Long): Result<Int> {
        if (urls.isEmpty()) return Result.success(0)
        if (!_loading.compareAndAdd(src)) return Result.success(0)
        val now = System.currentTimeMillis()
        val from = now - keepPastMs
        val to = now + keepFutureMs
        val db = helper.writableDatabase
        val result = runCatching {
            var count = 0
            db.beginTransactionNonExclusive()
            try {
                db.delete("prog", "src = ?", arrayOf(src))
                db.delete("alias", "src = ?", arrayOf(src))
                // Also clear anything too old to matter, whichever playlist it came from.
                db.delete("prog", "stop < ?", arrayOf((now - MAX_PAST_MS).toString()))
                val insert = db.compileStatement("INSERT INTO prog (src, ch, start, stop, title, descr) VALUES (?, ?, ?, ?, ?, ?)")
                val insertAlias = db.compileStatement("INSERT INTO alias (src, name, ch) VALUES (?, ?, ?)")
                var failure: Throwable? = null
                var read = 0
                for (url in urls) {
                    runCatching {
                        open(url).use { input ->
                            val accepted = HashSet<String>()
                            Xmltv.parse(input, object : Xmltv.Sink {
                                override fun channel(id: String, names: List<String>) {
                                    val key = id.lowercase()
                                    val keys = names.map { ChannelIndex.nameKey(it) }.filter { it.length >= 3 }
                                    if (key in wanted.ids || keys.any { it in wanted.names }) {
                                        accepted += key
                                        for (k in keys.distinct()) {
                                            insertAlias.bindString(1, src); insertAlias.bindString(2, k); insertAlias.bindString(3, key)
                                            insertAlias.executeInsert()
                                        }
                                    }
                                }

                                override fun programme(channelId: String, start: Long, stop: Long, title: String, description: String) {
                                    if (stop < from || start > to || count >= MAX_ROWS) return
                                    val key = channelId.lowercase()
                                    if (key !in accepted && key !in wanted.ids) return
                                    insert.bindString(1, src); insert.bindString(2, key)
                                    insert.bindLong(3, start); insert.bindLong(4, stop)
                                    insert.bindString(5, title.take(160)); insert.bindString(6, description.take(400))
                                    insert.executeInsert()
                                    count++
                                }
                            })
                        }
                        read++
                    }.onFailure { failure = it; Log.w(TAG, "Guide $url failed", it) }
                }
                // Provider guides are often cut off or broken part-way; keep whatever was read before that.
                if (read == 0 && count == 0) throw failure ?: IllegalStateException("No guide could be downloaded")
                if (count == 0) throw IllegalStateException("The guide has nothing for these channels")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            reloadKeys()
            count
        }
        cache.clear()
        _loading.update { it - src }
        _version.update { it + 1 }
        return result
    }

    /** Forgets a playlist's guide (the playlist was removed). */
    fun remove(src: String) {
        runCatching {
            val db = helper.writableDatabase
            db.delete("prog", "src = ?", arrayOf(src))
            db.delete("alias", "src = ?", arrayOf(src))
            reloadKeys()
        }
        cache.clear()
        _version.update { it + 1 }
    }

    private fun reloadKeys() {
        val db = helper.readableDatabase
        val found = HashSet<String>()
        db.rawQuery("SELECT DISTINCT ch FROM prog", null).use { c -> while (c.moveToNext()) found += c.getString(0) }
        val links = HashMap<String, String>()
        db.rawQuery("SELECT name, ch FROM alias", null).use { c ->
            while (c.moveToNext()) {
                val ch = c.getString(1)
                if (ch in found) links.putIfAbsent(c.getString(0), ch)
            }
        }
        ids = found
        aliases = links
    }

    /** Opens a guide link, following redirects and unpacking .gz files. */
    private fun open(url: String): InputStream {
        var next = url
        repeat(6) {
            val c = URL(next).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", LiveTvStore.PLAYLIST_AGENT)
            val code = c.responseCode
            when {
                code in 300..399 -> {
                    val loc = c.getHeaderField("Location") ?: throw IllegalStateException("The guide link redirected nowhere")
                    c.disconnect()
                    next = URL(URL(next), loc).toString()
                }
                code in 200..299 -> {
                    val raw = BufferedInputStream(c.inputStream, 64 * 1024)
                    raw.mark(4)
                    val a = raw.read(); val b = raw.read()
                    raw.reset()
                    return if (a == 0x1f && b == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
                }
                else -> { c.disconnect(); throw IllegalStateException("The guide link answered with error $code") }
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun MutableStateFlow<Set<String>>.compareAndAdd(id: String): Boolean {
        while (true) {
            val cur = value
            if (id in cur) return false
            if (compareAndSet(cur, cur + id)) return true
        }
    }

    companion object {
        private const val TAG = "AkoyaGuide"
        private const val MAX_ROWS = 400_000
        const val MAX_PAST_MS = 4L * 86_400_000

        @Volatile private var instance: EpgStore? = null
        fun get(context: Context): EpgStore =
            instance ?: synchronized(this) {
                instance ?: EpgStore(context.applicationContext).also { instance = it }
            }
    }
}
