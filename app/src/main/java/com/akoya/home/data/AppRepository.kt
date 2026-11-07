package com.akoya.home.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.LruCache
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    val isTvApp: Boolean,
    val isSystem: Boolean,
    val installedAt: Long,
)

data class AppArt(
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val tint: Color,
)

class AppRepository private constructor(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()
    private val artCache = LruCache<String, AppArt>(160)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, AppInfo>()
        fun collect(category: String, tv: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val list = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == context.packageName || found.containsKey(pkg)) continue
                val installedAt = runCatching { pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrDefault(0L)
                found[pkg] = AppInfo(
                    packageName = pkg,
                    label = ri.loadLabel(pm).toString(),
                    component = ComponentName(pkg, ai.name),
                    isTvApp = tv,
                    isSystem = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    installedAt = installedAt,
                )
            }
        }
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, tv = true)
        collect(Intent.CATEGORY_LAUNCHER, tv = false)
        _apps.value = found.values.sortedBy { it.label.lowercase() }
    }

    fun find(pkg: String?): AppInfo? = pkg?.let { p -> _apps.value.firstOrNull { it.packageName == p } }

    fun isInstalled(pkg: String): Boolean =
        runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    fun labelFor(pkg: String): String =
        find(pkg)?.label ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)

    suspend fun art(app: AppInfo): AppArt = artCache.get(app.packageName) ?: withContext(Dispatchers.IO) {
        val banner = runCatching { pm.getActivityBanner(app.component) }.getOrNull()
            ?: runCatching { pm.getApplicationBanner(app.packageName) }.getOrNull()
        val icon = runCatching { pm.getActivityIcon(app.component) }.getOrNull()
            ?: runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
        val iconBmp = icon?.let { safeBitmap(it, 192, 192) }
        AppArt(
            banner = banner?.let { safeBitmap(it, 640, 360)?.asImageBitmap() },
            icon = iconBmp?.asImageBitmap(),
            tint = iconBmp?.let(::averageColor) ?: Color(0xFF2A2F3A),
        ).also { artCache.put(app.packageName, it) }
    }

    private fun safeBitmap(d: Drawable, fallbackW: Int, fallbackH: Int): Bitmap? = runCatching {
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: fallbackW
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: fallbackH
        d.toBitmap(w.coerceAtMost(fallbackW * 2), h.coerceAtMost(fallbackH * 2), Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** Muted average colour of an icon, used as the tile background for apps without a TV banner. */
    private fun averageColor(bmp: Bitmap): Color {
        val small = Bitmap.createScaledBitmap(bmp, 12, 12, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (x in 0 until small.width) for (y in 0 until small.height) {
            val c = small.getPixel(x, y)
            val a = (c ushr 24) and 0xFF
            if (a < 128) continue
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        if (n == 0L) return Color(0xFF2A2F3A)
        val f = 0.55f // darken so white labels stay readable
        return Color((r / n * f).toInt(), (g / n * f).toInt(), (b / n * f).toInt())
    }

    // ---- Actions ----

    fun launch(app: AppInfo): Boolean {
        val intent = (if (app.isTvApp) pm.getLeanbackLaunchIntentForPackage(app.packageName) else null)
            ?: pm.getLaunchIntentForPackage(app.packageName)
            ?: Intent(Intent.ACTION_MAIN).setComponent(app.component)
        val ok = start(intent)
        if (ok) {
            ConfigStore.get(context).update { c ->
                c.copy(recent = (listOf(app.packageName) + c.recent.filter { it != app.packageName }).take(20))
            }
        }
        return ok
    }

    fun openAppInfo(pkg: String) =
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    fun uninstall(pkg: String) = start(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))

    fun openStorePage(pkg: String) =
        start(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))

    fun start(intent: Intent, quiet: Boolean = false): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        if (!quiet) Toast.makeText(context, "Not available on this TV", Toast.LENGTH_SHORT).show()
        false
    } catch (e: SecurityException) {
        if (!quiet) Toast.makeText(context, "Blocked by the system", Toast.LENGTH_SHORT).show()
        false
    }

    /** Tries each intent in turn until one opens. */
    fun startFirst(vararg intents: Intent): Boolean {
        intents.forEachIndexed { i, intent ->
            if (start(intent, quiet = i < intents.lastIndex)) return true
        }
        return false
    }

    // ---- Recent apps ----

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun recentPackages(fallback: List<String>): List<String> = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) return@withContext fallback
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return@withContext fallback
        val end = System.currentTimeMillis()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, end - 14L * 24 * 3600 * 1000, end)
        }.getOrNull().orEmpty()
        val launchable = _apps.value.map { it.packageName }.toSet()
        stats.filter { it.lastTimeUsed > 0 && it.totalTimeInForeground > 5_000 && it.packageName in launchable }
            .sortedByDescending { it.lastTimeUsed }
            .map { it.packageName }
            .distinct()
            .ifEmpty { fallback }
    }

    fun isDefaultLauncher(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == context.packageName
    }

    companion object {
        @Volatile private var instance: AppRepository? = null
        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
    }
}
