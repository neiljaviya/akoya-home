package com.akoya.home.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class SectionType(val label: String, val description: String) {
    HERO("Clock & greeting", "Large clock, date and greeting"),
    QUICK_APPS("Quick apps", "Your favourite apps, in your order"),
    LIVE_TV("Live TV", "Favourite channels and a shortcut to live TV"),
    CONTINUE_WATCHING("Continue watching", "Resume shows and movies from your apps"),
    APP_CONTENT("App spotlight", "Covers and picks from one app, e.g. Netflix"),
    RECENT_APPS("Recent apps", "Apps you opened lately"),
    ALL_APPS("All apps row", "Every installed app in one scrolling row"),
    INPUTS("Inputs", "HDMI and other sources"),
    SHORTCUTS("Shortcuts", "Settings, Wi-Fi, Bluetooth, screensaver"),
}

enum class CardSize(val label: String, val scale: Float) {
    SMALL("Small", 0.8f),
    MEDIUM("Medium", 1f),
    LARGE("Large", 1.3f),
}

enum class ScreensaverStyle(val label: String) {
    SIGNAGE("Signage rotation"),
    DAY_PROGRESS("Day progress clock"),
    ZEN("Live life zen"),
    FESTIVE("Festival & season art"),
    AMBIENT("Drifting mist"),
    CLOCK("Floating clock"),
    PHOTOS("Photo slideshow"),
}

enum class WallpaperMode(val label: String) {
    PATTERN("Signage mist"),
    THEME("Plain"),
    IMAGE("Custom image"),
    SOLID("Solid colour"),
}

data class SectionConfig(
    val id: String = UUID.randomUUID().toString(),
    val type: SectionType,
    val title: String = type.label,
    val visible: Boolean = true,
    val packageName: String? = null,
    val size: CardSize = CardSize.MEDIUM,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("title", title)
        .put("visible", visible)
        .put("packageName", packageName ?: JSONObject.NULL)
        .put("size", size.name)

    companion object {
        fun fromJson(o: JSONObject): SectionConfig? {
            val type = enumOrNull<SectionType>(o.optString("type")) ?: return null
            return SectionConfig(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                type = type,
                title = o.optString("title").ifBlank { type.label },
                visible = o.optBoolean("visible", true),
                packageName = if (o.isNull("packageName")) null else o.optString("packageName").ifBlank { null },
                size = enumOrNull<CardSize>(o.optString("size")) ?: CardSize.MEDIUM,
            )
        }
    }
}

data class ScreensaverConfig(
    val style: ScreensaverStyle = ScreensaverStyle.SIGNAGE,
    val idleMinutes: Int = 10,
    val photoSeconds: Int = 12,
    val showClock: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("style", style.name)
        .put("idleMinutes", idleMinutes)
        .put("photoSeconds", photoSeconds)
        .put("showClock", showClock)

    companion object {
        fun fromJson(o: JSONObject?): ScreensaverConfig {
            if (o == null) return ScreensaverConfig()
            return ScreensaverConfig(
                style = enumOrNull<ScreensaverStyle>(o.optString("style")) ?: ScreensaverStyle.SIGNAGE,
                idleMinutes = o.optInt("idleMinutes", 10),
                photoSeconds = o.optInt("photoSeconds", 12),
                showClock = o.optBoolean("showClock", true),
            )
        }
    }
}

data class LauncherConfig(
    val themeId: String = "obsidian",
    val brandName: String = "Javiya's TV",
    val wallpaperMode: WallpaperMode = WallpaperMode.PATTERN,
    val wallpaperFile: String? = null,
    val wallpaperDim: Float = 0.45f,
    val use24h: Boolean = false,
    val showSeconds: Boolean = false,
    val showGreeting: Boolean = true,
    val greetingName: String = "",
    val animations: Boolean = true,
    val focusZoom: Float = 1.08f,
    val showAppLabels: Boolean = true,
    val sections: List<SectionConfig> = defaultSections(),
    val favorites: List<String> = emptyList(),
    val favoritesInitialised: Boolean = false,
    val hidden: Set<String> = emptySet(),
    val recent: List<String> = emptyList(),
    val screensaver: ScreensaverConfig = ScreensaverConfig(),
    /** "auto" = follow the calendar, "off", or a festival id to preview it. */
    val festivalMode: String = "auto",
    val seasonalArt: Boolean = true,
    val tmdbKey: String = "",
    val tmdbRegion: String = "IN",
    val companionPin: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("themeId", themeId)
        .put("brandName", brandName)
        .put("wallpaperMode", wallpaperMode.name)
        .put("wallpaperFile", wallpaperFile ?: JSONObject.NULL)
        .put("wallpaperDim", wallpaperDim.toDouble())
        .put("use24h", use24h)
        .put("showSeconds", showSeconds)
        .put("showGreeting", showGreeting)
        .put("greetingName", greetingName)
        .put("animations", animations)
        .put("focusZoom", focusZoom.toDouble())
        .put("showAppLabels", showAppLabels)
        .put("sections", JSONArray().also { arr -> sections.forEach { arr.put(it.toJson()) } })
        .put("favorites", JSONArray(favorites))
        .put("favoritesInitialised", favoritesInitialised)
        .put("hidden", JSONArray(hidden.toList()))
        .put("recent", JSONArray(recent))
        .put("screensaver", screensaver.toJson())
        .put("festivalMode", festivalMode)
        .put("seasonalArt", seasonalArt)
        .put("tmdbKey", tmdbKey)
        .put("tmdbRegion", tmdbRegion)
        .put("companionPin", companionPin)

    companion object {
        fun fromJson(o: JSONObject): LauncherConfig {
            val d = LauncherConfig()
            val sections = o.optJSONArray("sections")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(SectionConfig::fromJson) }
            }
            return LauncherConfig(
                themeId = o.optString("themeId", d.themeId).ifBlank { d.themeId },
                brandName = o.optString("brandName", d.brandName),
                wallpaperMode = enumOrNull<WallpaperMode>(o.optString("wallpaperMode")) ?: d.wallpaperMode,
                wallpaperFile = if (o.isNull("wallpaperFile")) null else o.optString("wallpaperFile").ifBlank { null },
                wallpaperDim = o.optDouble("wallpaperDim", d.wallpaperDim.toDouble()).toFloat(),
                use24h = o.optBoolean("use24h", d.use24h),
                showSeconds = o.optBoolean("showSeconds", d.showSeconds),
                showGreeting = o.optBoolean("showGreeting", d.showGreeting),
                greetingName = o.optString("greetingName", ""),
                animations = o.optBoolean("animations", d.animations),
                focusZoom = o.optDouble("focusZoom", d.focusZoom.toDouble()).toFloat(),
                showAppLabels = o.optBoolean("showAppLabels", d.showAppLabels),
                sections = if (sections.isNullOrEmpty()) d.sections else sections,
                favorites = o.optJSONArray("favorites").toStringList(),
                favoritesInitialised = o.optBoolean("favoritesInitialised", false),
                hidden = o.optJSONArray("hidden").toStringList().toSet(),
                recent = o.optJSONArray("recent").toStringList(),
                screensaver = ScreensaverConfig.fromJson(o.optJSONObject("screensaver")),
                festivalMode = o.optString("festivalMode", "auto").ifBlank { "auto" },
                seasonalArt = o.optBoolean("seasonalArt", true),
                tmdbKey = o.optString("tmdbKey", ""),
                tmdbRegion = o.optString("tmdbRegion", "IN").ifBlank { "IN" },
                companionPin = o.optString("companionPin", ""),
            )
        }
    }
}

fun defaultSections(): List<SectionConfig> = listOf(
    SectionConfig(type = SectionType.HERO),
    SectionConfig(type = SectionType.QUICK_APPS),
    SectionConfig(type = SectionType.CONTINUE_WATCHING),
    SectionConfig(type = SectionType.APP_CONTENT, title = "Netflix", packageName = "com.netflix.ninja", size = CardSize.LARGE),
    SectionConfig(type = SectionType.APP_CONTENT, title = "YouTube", packageName = "com.google.android.youtube.tv"),
    SectionConfig(type = SectionType.RECENT_APPS),
    SectionConfig(type = SectionType.INPUTS),
    SectionConfig(type = SectionType.SHORTCUTS, size = CardSize.SMALL),
)

/** Packages tried, in order, to seed the Quick apps row on first run. */
val DEFAULT_FAVORITES = listOf(
    "com.netflix.ninja",
    "com.google.android.youtube.tv",
    "com.amazon.amazonvideo.livingroom",
    "in.startv.hotstar.dplus.tv",
    "in.startv.hotstar",
    "com.graymatrix.did",
    "com.sonyliv",
    "com.google.android.youtube.tvmusic",
    "com.android.vending",
)

inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    enumValues<T>().firstOrNull { it.name == name }

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).ifBlank { null } }
}

class ConfigStore private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("akoya_home", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<LauncherConfig> = _config.asStateFlow()

    val current: LauncherConfig get() = _config.value

    private fun load(): LauncherConfig {
        val raw = prefs.getString(KEY, null) ?: return LauncherConfig()
        return runCatching { LauncherConfig.fromJson(JSONObject(raw)) }.getOrDefault(LauncherConfig())
    }

    fun update(transform: (LauncherConfig) -> LauncherConfig) {
        val next = _config.updateAndGet(transform)
        prefs.edit().putString(KEY, next.toJson().toString()).apply()
    }

    fun resetToDefaults() = update { LauncherConfig() }

    // ---- Folders the user can drop files into with: adb push <file> <folder> ----
    private fun dir(name: String): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, name).apply { mkdirs() }

    val wallpapersDir: File get() = dir("wallpapers")
    val screensaverDir: File get() = dir("screensaver")
    val brandingDir: File get() = dir("branding")
    private val backupFile: File get() = File(dir("backup"), "akoya-home-config.json")

    fun images(folder: File): List<File> =
        folder.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXT }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    fun logoFile(): File? = images(brandingDir).firstOrNull { it.nameWithoutExtension.equals("logo", true) }

    fun exportBackup(): Result<File> = runCatching {
        backupFile.writeText(current.toJson().toString(2))
        backupFile
    }

    fun importBackup(): Result<Unit> = runCatching {
        val parsed = LauncherConfig.fromJson(JSONObject(backupFile.readText()))
        update { parsed }
    }

    companion object {
        private const val KEY = "config"
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

        @Volatile private var instance: ConfigStore? = null
        fun get(context: Context): ConfigStore =
            instance ?: synchronized(this) {
                instance ?: ConfigStore(context.applicationContext).also { instance = it }
            }
    }
}
