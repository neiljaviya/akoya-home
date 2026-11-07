package com.akoya.home.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContentItem(
    val id: Long,
    val title: String,
    val subtitle: String?,
    val imageUri: String?,
    val intentUri: String?,
    val packageName: String?,
    val progress: Float?,
)

data class ContentResult(
    val items: List<ContentItem>,
    /** True when the TV refused access (permission not granted). */
    val needsPermission: Boolean,
)

data class InputSource(
    val id: String,
    val label: String,
    val icon: ImageBitmap?,
    val isHdmi: Boolean,
)

const val EPG_PERMISSION = "com.android.providers.tv.permission.READ_EPG_DATA"

/**
 * Reads "Continue watching" and app recommendation rows from the system TV provider.
 * On Google TV the provider only shares other apps' rows with launchers that hold
 * READ_EPG_DATA (grant via adb). Some apps publish only to Google's own launcher, in
 * which case the spotlight falls back to the app's artwork.
 */
class ContentRepository private constructor(private val context: Context) {

    fun hasEpgPermission(): Boolean =
        context.checkSelfPermission(EPG_PERMISSION) == PackageManager.PERMISSION_GRANTED

    suspend fun watchNext(): ContentResult = withContext(Dispatchers.IO) {
        query(TvContract.WatchNextPrograms.CONTENT_URI, packageFilter = null, limit = 30) { c ->
            val pos = c.long("last_playback_position_millis")
            val dur = c.long("duration_millis")
            if (pos != null && dur != null && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null
        }
    }

    suspend fun appPrograms(pkg: String): ContentResult = withContext(Dispatchers.IO) {
        query(TvContract.PreviewPrograms.CONTENT_URI, packageFilter = pkg, limit = 40) { null }
    }

    private fun query(
        uri: Uri,
        packageFilter: String?,
        limit: Int,
        progress: (Cursor) -> Float?,
    ): ContentResult {
        val items = mutableListOf<ContentItem>()
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                while (c.moveToNext() && items.size < limit) {
                    val pkg = c.string("package_name")
                    if (packageFilter != null && pkg != packageFilter) continue
                    if (c.long("browsable") == 0L) continue
                    val title = c.string("title") ?: continue
                    items += ContentItem(
                        id = c.long("_id") ?: items.size.toLong(),
                        title = title,
                        subtitle = c.string("episode_title") ?: c.string("short_description"),
                        imageUri = c.string("poster_art_uri") ?: c.string("thumbnail_uri"),
                        intentUri = c.string("intent_uri"),
                        packageName = pkg,
                        progress = progress(c),
                    )
                }
            }
        } catch (e: SecurityException) {
            return ContentResult(emptyList(), needsPermission = true)
        } catch (e: Exception) {
            return ContentResult(emptyList(), needsPermission = !hasEpgPermission())
        }
        return ContentResult(items, needsPermission = items.isEmpty() && !hasEpgPermission())
    }

    fun intentFor(item: ContentItem): Intent? {
        val raw = item.intentUri ?: return item.packageName?.let {
            context.packageManager.getLeanbackLaunchIntentForPackage(it)
                ?: context.packageManager.getLaunchIntentForPackage(it)
        }
        return runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull()
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(raw))
    }

    suspend fun inputs(): List<InputSource> = withContext(Dispatchers.IO) {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return@withContext emptyList()
        runCatching { tim.tvInputList }.getOrDefault(emptyList())
            .filter { it.isPassthroughInput && !it.isHidden(context) }
            .map { info ->
                InputSource(
                    id = info.id,
                    label = info.loadCustomLabel(context)?.toString()?.ifBlank { null }
                        ?: info.loadLabel(context).toString(),
                    icon = runCatching { info.loadIcon(context)?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull(),
                    isHdmi = info.type == TvInputInfo.TYPE_HDMI,
                )
            }
            .sortedBy { it.label }
    }

    fun inputIntent(source: InputSource): Intent =
        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(source.id))

    private fun Cursor.string(col: String): String? {
        val i = getColumnIndex(col)
        return if (i >= 0 && !isNull(i)) getString(i) else null
    }

    private fun Cursor.long(col: String): Long? {
        val i = getColumnIndex(col)
        return if (i >= 0 && !isNull(i)) getLong(i) else null
    }

    companion object {
        @Volatile private var instance: ContentRepository? = null
        fun get(context: Context): ContentRepository =
            instance ?: synchronized(this) {
                instance ?: ContentRepository(context.applicationContext).also { instance = it }
            }
    }
}
