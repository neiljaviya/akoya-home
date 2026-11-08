package com.akoya.home.data

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.akoya.home.server.CompanionServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class HealthLevel(val label: String) { OK("OK"), INFO("Info"), WARN("Review"), BAD("Fix") }

data class HealthItem(
    val title: String,
    val detail: String,
    val level: HealthLevel,
    val intent: Intent? = null,
    val pkg: String? = null,
)

data class HealthGroup(val title: String, val items: List<HealthItem>)

data class HealthReport(val groups: List<HealthGroup>) {
    val bad get() = groups.sumOf { g -> g.items.count { it.level == HealthLevel.BAD } }
    val warn get() = groups.sumOf { g -> g.items.count { it.level == HealthLevel.WARN } }
}

/** Runs on the TV without adb or root: everything an ordinary app is allowed to read. */
object HealthCheck {

    /** Package-name fragments of well-known unofficial streaming / piracy apps. */
    private val RISKY = listOf("cricfy", "streamz", "thoptv", "oreotv", "redbox", "mobdro", "typhoon", "swiftstream", "livenettv", "ustvnow", "bluesun", "kodi.addon", "cinemahd", "beetv", "teatv", "cyberflix")
    private val TRUSTED_STORES = setOf("com.android.vending", "com.amazon.venezia", "com.google.android.feedback")

    private val KNOWN_PORTS = mapOf(
        5555 to "ADB remote debugging",
        8008 to "Chromecast built-in",
        8009 to "Chromecast built-in",
        8443 to "Chromecast built-in",
        9000 to "Chromecast built-in",
        10001 to "Chromecast built-in",
        6466 to "Android TV Remote service",
        6467 to "Android TV Remote pairing",
        CompanionServer.PORT to "Javiya's TV phone page",
        53317 to "LocalSend",
    )

    suspend fun run(ctx: Context): HealthReport = withContext(Dispatchers.IO) {
        HealthReport(listOf(system(ctx), access(ctx), apps(ctx), watchers(ctx), network(ctx)))
    }

    // ------------------------------------------------------------------ system
    private fun system(ctx: Context): HealthGroup {
        val items = mutableListOf<HealthItem>()
        val patch = runCatching { LocalDate.parse(Build.VERSION.SECURITY_PATCH) }.getOrNull()
        val years = patch?.let { ChronoUnit.DAYS.between(it, LocalDate.now()) / 365.0 }
        items += HealthItem(
            "Security patch ${Build.VERSION.SECURITY_PATCH}",
            when {
                years == null -> "Unknown patch level."
                years > 2 -> "About ${years.toInt()} years without security fixes. Check Settings → About → System update; if none, stick to Play Store apps."
                years > 1 -> "Over a year old. Check for a system update."
                else -> "Recent enough."
            },
            when { years == null -> HealthLevel.INFO; years > 2 -> HealthLevel.BAD; years > 1 -> HealthLevel.WARN; else -> HealthLevel.OK },
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
        )
        items += HealthItem("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}", "Android TV system version.", HealthLevel.INFO)

        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        if (stat != null) {
            val freePct = stat.availableBytes * 100 / stat.totalBytes.coerceAtLeast(1)
            items += HealthItem(
                "Storage: ${human(stat.availableBytes)} free of ${human(stat.totalBytes)}",
                if (freePct < 10) "Almost full. Apps may stutter; clear app caches or remove unused apps." else "$freePct% free.",
                if (freePct < 10) HealthLevel.WARN else HealthLevel.OK,
                Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            )
        }
        val am = ctx.getSystemService(ActivityManager::class.java)
        if (am != null) {
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            items += HealthItem(
                "Memory: ${human(mi.availMem)} free of ${human(mi.totalMem)}",
                if (mi.lowMemory) "The TV is low on memory right now." else "Fine.",
                if (mi.lowMemory) HealthLevel.WARN else HealthLevel.OK,
            )
        }
        val days = SystemClock.elapsedRealtime() / 86_400_000L
        items += HealthItem(
            "On for ${if (days == 0L) "less than a day" else "$days days"}",
            if (days >= 14) "A restart now and then clears memory leaks in older TVs." else "Since last restart.",
            if (days >= 14) HealthLevel.INFO else HealthLevel.OK,
        )
        return HealthGroup("System", items)
    }

    // ------------------------------------------------------------------ debugging & installs
    private fun access(ctx: Context): HealthGroup {
        val cr = ctx.contentResolver
        val items = mutableListOf<HealthItem>()
        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        val devOn = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val adbPort = listeningPorts().any { it.port == 5555 && !it.loopback }
        items += HealthItem(
            "USB / network debugging " + if (adb) "on" else "off",
            when {
                adb && adbPort -> "adb is open on port 5555: anyone on your Wi-Fi can install apps and control the TV. Turn it off when you're done."
                adb -> "Debugging is on. Turn it off when you're not using it."
                else -> "Off."
            },
            when { adb && adbPort -> HealthLevel.BAD; adb -> HealthLevel.WARN; else -> HealthLevel.OK },
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        )
        if (devOn && !adb) items += HealthItem("Developer options on", "Harmless on its own.", HealthLevel.INFO, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        val verifier = Settings.Global.getString(cr, "package_verifier_enable")
        items += HealthItem(
            "Play Protect app checks " + if (verifier == "0") "off" else "on",
            if (verifier == "0") "New apps aren't scanned. Turn it back on in Play Store → Profile → Play Protect." else "Apps are scanned when installed.",
            if (verifier == "0") HealthLevel.WARN else HealthLevel.OK,
            Intent("com.google.android.gms.settings.VERIFY_APPS_SETTINGS"),
        )
        @Suppress("DEPRECATION")
        val unknown = Settings.Secure.getInt(cr, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        val installers = installedApps(ctx).filter { p ->
            p.requestedPermissions?.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES) == true && !isSystem(p) && p.packageName != ctx.packageName
        }.map { label(ctx, it) }
        items += HealthItem(
            "Apps that can install other apps",
            if (installers.isEmpty()) "None besides the Play Store." else installers.joinToString(", ") + if (unknown) ". Unknown sources is allowed." else ".",
            if (installers.isEmpty()) HealthLevel.OK else HealthLevel.INFO,
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        return HealthGroup("Debugging & installs", items)
    }

    // ------------------------------------------------------------------ apps
    private fun apps(ctx: Context): HealthGroup {
        val pm = ctx.packageManager
        val items = mutableListOf<HealthItem>()
        val user = installedApps(ctx).filter { !isSystem(it) && it.packageName != ctx.packageName }
        user.forEach { p ->
            @Suppress("DEPRECATION")
            val installer = runCatching { pm.getInstallerPackageName(p.packageName) }.getOrNull()
            val risky = RISKY.any { p.packageName.contains(it, ignoreCase = true) }
            val sideloaded = installer == null || installer !in TRUSTED_STORES
            if (risky || sideloaded) {
                items += HealthItem(
                    label(ctx, p),
                    when {
                        risky -> "Unofficial streaming app (${p.packageName}). These often carry aggressive ads, trackers or worse. Uninstall unless you trust it."
                        installer == null -> "Installed over adb or from a file (${p.packageName}). Fine if you put it there."
                        else -> "Installed by ${installer.substringAfterLast('.')} instead of the Play Store (${p.packageName})."
                    },
                    if (risky) HealthLevel.BAD else HealthLevel.WARN,
                    pkg = p.packageName,
                )
            }
        }
        if (items.isEmpty()) items += HealthItem("All apps came from the Play Store", "${user.size} apps installed by you.", HealthLevel.OK)
        else items += HealthItem("Other ${user.size - items.size} apps", "Installed from the Play Store.", HealthLevel.OK)

        val sensitive = mapOf(
            Manifest.permission.RECORD_AUDIO to "Microphone",
            Manifest.permission.CAMERA to "Camera",
            Manifest.permission.ACCESS_FINE_LOCATION to "Location",
            Manifest.permission.READ_CONTACTS to "Contacts",
            Manifest.permission.READ_EXTERNAL_STORAGE to "Files",
        )
        sensitive.forEach { (perm, name) ->
            val holders = user.filter { granted(it, perm) }.map { label(ctx, it) }
            if (holders.isNotEmpty()) items += HealthItem("$name access", holders.joinToString(", "), HealthLevel.INFO)
        }
        return HealthGroup("Apps", items)
    }

    // ------------------------------------------------------------------ apps that can watch
    private fun watchers(ctx: Context): HealthGroup {
        val cr = ctx.contentResolver
        val items = mutableListOf<HealthItem>()
        val acc = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }
        val foreignAcc = acc.filterNot { it.startsWith(ctx.packageName) }
        items += HealthItem(
            "Screen readers (accessibility)",
            if (acc.isEmpty()) "None can read the screen." else acc.joinToString("\n") { pkgLabel(ctx, it) },
            if (foreignAcc.isEmpty()) HealthLevel.OK else HealthLevel.WARN,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        val nl = Settings.Secure.getString(cr, "enabled_notification_listeners").orEmpty().split(':').filter { it.isNotBlank() }
        val foreignNl = nl.filterNot { it.startsWith("com.google.") || it.startsWith("com.android.") }
        items += HealthItem(
            "Notification readers",
            if (nl.isEmpty()) "None." else nl.joinToString("\n") { pkgLabel(ctx, it) },
            if (foreignNl.isEmpty()) HealthLevel.OK else HealthLevel.WARN,
        )
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val admins = dpm?.activeAdmins.orEmpty()
        items += HealthItem(
            "Device admins",
            if (admins.isEmpty()) "None can lock or wipe the TV." else admins.joinToString("\n") { pkgLabel(ctx, it.flattenToString()) },
            if (admins.isEmpty()) HealthLevel.OK else HealthLevel.WARN,
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        return HealthGroup("Apps that can watch", items)
    }

    // ------------------------------------------------------------------ network
    data class Listening(val port: Int, val loopback: Boolean)

    fun listeningPorts(): List<Listening> {
        val out = mutableListOf<Listening>()
        for (file in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            runCatching {
                File(file).readLines().drop(1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size > 3 && parts[3] == "0A") {
                        val (addr, portHex) = parts[1].split(':')
                        val loop = addr == "0100007F" || addr.endsWith("0100007F") || addr == "00000000000000000000000001000000"
                        out += Listening(portHex.toInt(16), loop)
                    }
                }
            }
        }
        return out.distinctBy { it.port to it.loopback }.sortedBy { it.port }
    }

    private fun network(ctx: Context): HealthGroup {
        val items = mutableListOf<HealthItem>()
        items += HealthItem("Address on your network", CompanionServer.localIp() ?: "Not connected", HealthLevel.INFO)
        val open = listeningPorts().filter { !it.loopback }
        if (open.isEmpty()) {
            items += HealthItem("Open ports", "Not readable on this Android version, or none open.", HealthLevel.INFO)
        } else {
            open.forEach { l ->
                val name = KNOWN_PORTS[l.port]
                items += HealthItem(
                    "Port ${l.port}",
                    name ?: "Unknown service. Usually part of the TV maker's software or a casting/remote app.",
                    when { l.port == 5555 -> HealthLevel.BAD; name == null -> HealthLevel.INFO; else -> HealthLevel.OK },
                )
            }
        }
        return HealthGroup("Network", items)
    }

    // ------------------------------------------------------------------ helpers
    @Suppress("DEPRECATION")
    private fun installedApps(ctx: Context): List<PackageInfo> =
        runCatching { ctx.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS) }.getOrDefault(emptyList())

    private fun isSystem(p: PackageInfo) = ((p.applicationInfo?.flags ?: 0) and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    private fun granted(p: PackageInfo, perm: String): Boolean {
        val perms = p.requestedPermissions ?: return false
        val flags = p.requestedPermissionsFlags ?: return false
        val i = perms.indexOf(perm)
        return i >= 0 && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
    }

    private fun label(ctx: Context, p: PackageInfo): String =
        p.applicationInfo?.loadLabel(ctx.packageManager)?.toString() ?: p.packageName

    private fun pkgLabel(ctx: Context, component: String): String {
        val pkg = ComponentName.unflattenFromString(component)?.packageName ?: component.substringBefore('/')
        return runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg) + " ($pkg)"
    }

    fun human(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
        else -> "${b / 1024} KB"
    }
}
