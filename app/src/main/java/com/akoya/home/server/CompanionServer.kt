package com.akoya.home.server

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.akoya.home.data.ConfigStore
import com.akoya.home.data.Festivals
import com.akoya.home.data.ScreensaverStyle
import com.akoya.home.data.TmdbRepository
import com.akoya.home.data.WallpaperMode
import com.akoya.home.data.enumOrNull
import com.akoya.home.livetv.LiveTvStore
import com.akoya.home.livetv.PLAYLIST_PRESETS
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLConnection
import kotlin.random.Random

/**
 * Small web server for the phone companion page: http://<tv-ip>:8080
 * Every API call needs the 4-digit PIN shown on the TV.
 */
class CompanionServer private constructor(private val ctx: Context) : NanoHTTPD(PORT) {

    private val store = ConfigStore.get(ctx)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/" || uri == "/index.html") return asset("web/index.html", "text/html; charset=utf-8")
        if (!uri.startsWith("/api/")) return text(Response.Status.NOT_FOUND, "Not found")
        val pin = session.headers["x-pin"] ?: session.parameters["pin"]?.firstOrNull()
        if (pin.isNullOrBlank() || pin != store.current.companionPin) {
            drain(session)
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Wrong PIN"))
        }
        return try {
            route(session, uri.removePrefix("/api"))
        } catch (e: SecurityException) {
            json(Response.Status.FORBIDDEN, JSONObject().put("error", e.message ?: "Not allowed"))
        } catch (e: Exception) {
            Log.w(TAG, "API error on $uri", e)
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "Error"))
        }
    }

    private fun param(s: IHTTPSession, name: String): String? = s.parameters[name]?.firstOrNull()

    private fun route(s: IHTTPSession, path: String): Response {
        val m = s.method
        return when {
            path == "/status" -> status()
            // ---------- photos (screensaver + wallpapers live in the app's own folder) ----------
            path == "/images" -> {
                val dir = imageDir(param(s, "folder"))
                val arr = JSONArray()
                store.images(dir).forEach { f ->
                    arr.put(JSONObject().put("name", f.name).put("size", f.length()).put("modified", f.lastModified()))
                }
                json(Response.Status.OK, JSONObject().put("files", arr).put("wallpaper", store.current.wallpaperFile ?: JSONObject.NULL))
            }
            path == "/image" && m == Method.GET -> fileResponse(File(imageDir(param(s, "folder")), safeName(param(s, "name"))))
            path == "/image" && (m == Method.PUT || m == Method.POST) -> {
                var name = safeName(param(s, "name"))
                if (!name.lowercase().endsWith(".jpg") && !name.lowercase().endsWith(".jpeg") && !name.lowercase().endsWith(".png")) name += ".jpg"
                val target = File(imageDir(param(s, "folder")), name)
                receive(s, target)
                ok(JSONObject().put("saved", target.name))
            }
            path == "/image" && m == Method.DELETE -> {
                val f = File(imageDir(param(s, "folder")), safeName(param(s, "name")))
                ok(JSONObject().put("deleted", f.delete()))
            }
            path == "/wallpaper" -> {
                val name = safeName(param(s, "name"))
                store.update { it.copy(wallpaperMode = WallpaperMode.IMAGE, wallpaperFile = name) }
                ok(JSONObject().put("wallpaper", name))
            }
            path == "/settings" && m == Method.POST -> saveSettings(readJson(s))
            // ---------- live TV playlists ----------
            path == "/livetv" && m == Method.GET -> liveTv()
            path == "/livetv/add" && m == Method.POST -> {
                val b = readJson(s)
                val live = LiveTvStore.get(ctx)
                try {
                    if (b.optString("replace").isNotBlank()) {
                        live.replaceSource(b.optString("replace"), b.optString("name"), b.optString("url"), b.optString("server"), b.optString("username"), b.optString("password"))
                        if (b.optString("guide").isNotBlank()) live.setGuideUrl(b.optString("replace"), b.optString("guide"))
                        return liveTv()
                    }
                    val added = when {
                        b.optString("preset").isNotBlank() ->
                            live.addPreset(b.optString("preset")) ?: throw IllegalArgumentException("Unknown list")
                        b.optString("server").isNotBlank() ->
                            live.addXtream(b.optString("name"), b.optString("server"), b.optString("username"), b.optString("password"))
                        else -> live.addM3u(b.optString("name"), b.optString("url"))
                    }
                    if (b.optString("guide").isNotBlank()) live.setGuideUrl(added.id, b.optString("guide"))
                    liveTv()
                } catch (e: IllegalArgumentException) {
                    json(Response.Status.BAD_REQUEST, JSONObject().put("error", e.message ?: "Couldn't add that"))
                }
            }
            path == "/livetv/guide" && m == Method.POST -> {
                try {
                    LiveTvStore.get(ctx).setGuideUrl(param(s, "id").orEmpty(), readJson(s).optString("guide"))
                    liveTv()
                } catch (e: IllegalArgumentException) {
                    json(Response.Status.BAD_REQUEST, JSONObject().put("error", e.message ?: "Couldn't use that link"))
                }
            }
            path == "/livetv/export" && m == Method.GET ->
                LiveTvStore.get(ctx).exportCsv().let { f -> fileResponse(f).also { it.addHeader("Content-Disposition", "attachment; filename=\"${f.name}\"") } }
            // The film or series library as a spreadsheet file, for sorting it out on a computer.
            path == "/vod/export" && m == Method.GET -> {
                val kind = if (param(s, "kind") == "series") com.akoya.home.vod.VodKind.SERIES else com.akoya.home.vod.VodKind.MOVIES
                val f = com.akoya.home.vod.VodStore.get(ctx).exportCsv(kind)
                if (f == null) text(Response.Status.NOT_FOUND, "Open ${kind.label} on the TV once first, so the library is downloaded")
                else fileResponse(f).also { it.addHeader("Content-Disposition", "attachment; filename=\"${f.name}\"") }
            }
            path == "/livetv/refresh" && m == Method.POST -> { LiveTvStore.get(ctx).refreshAsync(param(s, "id").orEmpty()); liveTv() }
            path == "/livetv/toggle" && m == Method.POST -> {
                val live = LiveTvStore.get(ctx)
                val id = param(s, "id").orEmpty()
                live.current.playlists.firstOrNull { it.id == id }?.let { live.setEnabled(id, !it.enabled) }
                liveTv()
            }
            path == "/livetv/remove" && m == Method.POST -> { LiveTvStore.get(ctx).remove(param(s, "id").orEmpty()); liveTv() }
            // ---------- file manager ----------
            path == "/fs/roots" -> json(Response.Status.OK, JSONObject().put("roots", JSONArray().apply {
                FileRoots.list(ctx).forEach { put(JSONObject().put("name", it.label).put("path", it.file.absolutePath)) }
            }))
            path == "/fs/list" -> {
                val dir = FileRoots.checked(param(s, "path"))
                val arr = JSONArray()
                (dir.listFiles() ?: emptyArray()).sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).forEach { f ->
                    arr.put(
                        JSONObject().put("name", f.name).put("path", f.absolutePath).put("dir", f.isDirectory)
                            .put("size", if (f.isFile) f.length() else 0).put("modified", f.lastModified()),
                    )
                }
                json(Response.Status.OK, JSONObject().put("path", dir.absolutePath).put("parent", dir.parentFile?.takeIf { FileRoots.isAllowed(it) }?.absolutePath ?: JSONObject.NULL).put("files", arr))
            }
            path == "/fs/download" -> {
                val f = FileRoots.checked(param(s, "path"))
                fileResponse(f).also { it.addHeader("Content-Disposition", "attachment; filename=\"${f.name}\"") }
            }
            path == "/fs/upload" -> {
                val dir = FileRoots.checked(param(s, "dir"))
                val target = File(dir, safeName(param(s, "name")))
                receive(s, target)
                ok(JSONObject().put("saved", target.absolutePath))
            }
            path == "/fs/delete" -> {
                val f = FileRoots.checked(param(s, "path"))
                ok(JSONObject().put("deleted", f.deleteRecursively()))
            }
            path == "/fs/mkdir" -> {
                val dir = FileRoots.checked(param(s, "dir"))
                ok(JSONObject().put("created", File(dir, safeName(param(s, "name"))).mkdirs()))
            }
            path == "/fs/rename" -> {
                val f = FileRoots.checked(param(s, "path"))
                val to = File(f.parentFile, safeName(param(s, "name")))
                ok(JSONObject().put("renamed", f.renameTo(to)))
            }
            else -> text(Response.Status.NOT_FOUND, "Unknown API")
        }
    }

    /** Playlists for the phone page. Logins and full links stay on the TV. */
    private fun liveTv(): Response {
        val live = LiveTvStore.get(ctx)
        val loading = live.loading.value
        val errors = live.errors.value
        val arr = JSONArray()
        live.current.playlists.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name).put("kind", p.kind.name)
                    .put("host", runCatching { java.net.URL(p.url).host }.getOrDefault(""))
                    .put("enabled", p.enabled).put("channels", p.channelCount).put("updated", p.updatedAt)
                    .put("loading", p.id in loading).put("error", errors[p.id] ?: JSONObject.NULL)
                    .put("guide", p.guideCount).put("guideSet", p.guideUrl.isNotBlank())
                    .put("guideLoading", p.id in live.guide.loading.value)
                    .put("guideError", live.guideErrors.value[p.id] ?: JSONObject.NULL),
            )
        }
        val presets = JSONArray()
        PLAYLIST_PRESETS.filter { pre -> live.current.playlists.none { it.url == pre.url } }
            .forEach { presets.put(JSONObject().put("id", it.id).put("name", it.name)) }
        return ok(JSONObject().put("playlists", arr).put("presets", presets).put("favorites", live.current.favorites.size).put("notice", live.notice.value ?: JSONObject.NULL))
    }

    private fun status(): Response {
        val c = store.current
        val free = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0L)
        val festival = Festivals.active(java.time.LocalDate.now(), c.festivalMode, c.seasonalArt)
        return json(
            Response.Status.OK,
            JSONObject()
                .put("brand", c.brandName)
                .put("version", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?")
                .put("screensaverCount", store.images(store.screensaverDir).size)
                .put("wallpaperCount", store.images(store.wallpapersDir).size)
                .put("freeBytes", free)
                .put("tmdb", c.tmdbKey.isNotBlank())
                .put("tmdbRegion", c.tmdbRegion)
                .put("screensaverStyle", c.screensaver.style.name)
                .put("styles", JSONArray(ScreensaverStyle.entries.map { JSONObject().put("id", it.name).put("label", it.label) }))
                .put("festivalMode", c.festivalMode)
                .put("seasonalArt", c.seasonalArt)
                .put("festivals", JSONArray(Festivals.catalog.filter { !it.isSeason }.map { JSONObject().put("id", it.id).put("name", it.name) }))
                .put("festivalNow", festival?.name ?: JSONObject.NULL),
        )
    }

    private fun saveSettings(body: JSONObject): Response {
        val result = JSONObject()
        if (body.has("tmdbKey")) {
            val key = body.optString("tmdbKey").trim()
            if (key.isNotEmpty() && !runBlocking { TmdbRepository.instance.validate(key) }) {
                return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "TMDB didn't accept that key"))
            }
            TmdbRepository.instance.clear()
            store.update { it.copy(tmdbKey = key) }
            result.put("tmdb", key.isNotEmpty())
        }
        store.update { c ->
            c.copy(
                brandName = body.optString("brandName", c.brandName).ifBlank { c.brandName },
                tmdbRegion = body.optString("tmdbRegion", c.tmdbRegion).ifBlank { c.tmdbRegion }.uppercase().take(2),
                festivalMode = body.optString("festivalMode", c.festivalMode).ifBlank { c.festivalMode },
                seasonalArt = if (body.has("seasonalArt")) body.optBoolean("seasonalArt") else c.seasonalArt,
                screensaver = enumOrNull<ScreensaverStyle>(body.optString("screensaverStyle"))
                    ?.let { st -> c.screensaver.copy(style = st) } ?: c.screensaver,
            )
        }
        if (body.has("tmdbRegion")) TmdbRepository.instance.clear()
        return ok(result.put("ok", true))
    }

    // ---------------------------------------------------------------- helpers

    private fun imageDir(folder: String?): File = when (folder) {
        "wallpapers" -> store.wallpapersDir
        else -> store.screensaverDir
    }

    private fun safeName(name: String?): String {
        val n = (name ?: "").substringAfterLast('/').substringAfterLast('\\').trim()
        require(n.isNotEmpty() && n != "." && n != "..") { "Bad file name" }
        return n.replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").take(120)
    }

    private fun contentLength(s: IHTTPSession): Long = s.headers["content-length"]?.toLongOrNull() ?: 0L

    /** Streams exactly Content-Length bytes of the request body into [target]. */
    private fun receive(s: IHTTPSession, target: File) {
        var remaining = contentLength(s)
        require(remaining > 0) { "Empty upload" }
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.part")
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            val input = s.inputStream
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        if (remaining > 0) { tmp.delete(); error("Upload interrupted") }
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "Couldn't save file" }
    }

    private fun drain(s: IHTTPSession) {
        var remaining = contentLength(s)
        val buf = ByteArray(16 * 1024)
        while (remaining > 0) {
            val n = s.inputStream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private fun readJson(s: IHTTPSession): JSONObject {
        val len = contentLength(s).toInt().coerceAtMost(64 * 1024)
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = s.inputStream.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return if (read == 0) JSONObject() else JSONObject(String(buf, 0, read, Charsets.UTF_8))
    }

    private fun fileResponse(f: File): Response {
        if (!f.isFile) return text(Response.Status.NOT_FOUND, "No such file")
        val mime = URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream"
        return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), f.length()).also {
            it.addHeader("Cache-Control", "no-store")
        }
    }

    private fun asset(path: String, mime: String): Response =
        newFixedLengthResponse(Response.Status.OK, mime, ctx.assets.open(path).bufferedReader().use { it.readText() })

    private fun text(status: Response.Status, msg: String): Response = newFixedLengthResponse(status, "text/plain", msg)
    private fun json(status: Response.Status, o: JSONObject): Response = newFixedLengthResponse(status, "application/json", o.toString())
    private fun ok(o: JSONObject) = json(Response.Status.OK, o)

    companion object {
        const val PORT = 8080
        private const val TAG = "AkoyaServer"
        @Volatile private var instance: CompanionServer? = null

        fun ensureStarted(context: Context) {
            if (instance?.isAlive == true) return
            synchronized(this) {
                if (instance?.isAlive == true) return
                val store = ConfigStore.get(context)
                if (store.current.companionPin.isBlank()) store.update { it.copy(companionPin = newPin()) }
                instance = runCatching {
                    CompanionServer(context.applicationContext).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                }.onFailure { Log.w(TAG, "Couldn't start companion server", it) }.getOrNull()
            }
        }

        val isRunning: Boolean get() = instance?.isAlive == true

        fun newPin(): String = Random.nextInt(1000, 10000).toString()

        /** The TV's address on the home network, e.g. 192.168.0.103. */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

        fun url(): String? = localIp()?.let { "http://$it:$PORT" }
    }
}
