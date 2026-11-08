package com.akoya.home.ui.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent as NativeKeyEvent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.akoya.home.data.HealthCheck
import com.akoya.home.data.WallpaperMode
import com.akoya.home.server.FileRoots
import com.akoya.home.ui.LauncherController
import com.akoya.home.ui.components.DialogOption
import com.akoya.home.ui.components.ListScrollSpec
import com.akoya.home.ui.components.OptionsDialog
import com.akoya.home.ui.components.TextInputDialog
import com.akoya.home.ui.components.TvButton
import com.akoya.home.ui.components.tvClickable
import com.akoya.home.ui.theme.AkoyaType
import com.akoya.home.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private enum class FileSort(val label: String) { NAME("Name"), DATE("Newest"), SIZE("Largest") }
private data class Clip(val file: File, val cut: Boolean)

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "3gp")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")

fun hasStorageAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(controller: LauncherController) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasStorageAccess(ctx)) }
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasStorageAccess(ctx)
    }
    var roots by remember { mutableStateOf(FileRoots.list(ctx)) }
    var dir by remember { mutableStateOf(roots.first().file) }
    var refresh by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf(FileSort.NAME) }
    var showHidden by remember { mutableStateOf(false) }
    var clip by remember { mutableStateOf<Clip?>(null) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    var viewer by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(controller.refreshTick) {
        granted = hasStorageAccess(ctx)
        roots = FileRoots.list(ctx)
    }

    val entries = remember(dir, refresh, sort, showHidden, granted) {
        (dir.listFiles()?.toList() ?: emptyList())
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(
                when (sort) {
                    FileSort.NAME -> compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })
                    FileSort.DATE -> compareBy<File>({ !it.isDirectory }, { -it.lastModified() })
                    FileSort.SIZE -> compareBy<File>({ !it.isDirectory }, { -it.length() })
                },
            )
    }
    val atRoot = roots.any { it.file.absolutePath == dir.absolutePath }
    val parent = dir.parentFile?.takeIf { !atRoot && FileRoots.isAllowed(it) }
    BackHandler(enabled = parent != null) { parent?.let { dir = it } }

    fun op(label: String, block: suspend () -> Unit) {
        busy = label
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { controller.toast("$label failed: ${it.message}") }
            busy = null
            refresh++
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .padding(start = 56.dp, end = 56.dp, top = 14.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Files", style = AkoyaType.title, color = p.text)
            Spacer(Modifier.width(16.dp))
            roots.forEach { r ->
                TvButton(
                    r.label,
                    icon = if (r.removable) Icons.Filled.Usb else Icons.Filled.Folder,
                    selected = dir.absolutePath.startsWith(r.file.absolutePath),
                    compact = true,
                    onClick = { dir = r.file },
                )
            }
        }
        Row(
            Modifier
                .padding(start = 56.dp, end = 56.dp, top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TvButton("Up", icon = Icons.Filled.ArrowUpward, compact = true, onClick = { parent?.let { dir = it } })
            TvButton("New folder", icon = Icons.Filled.CreateNewFolder, compact = true, onClick = { newFolder = true })
            clip?.let { c ->
                TvButton("Paste ${c.file.name}", icon = Icons.Filled.ContentPaste, compact = true, onClick = {
                    val target = File(dir, c.file.name)
                    op(if (c.cut) "Move" else "Copy") {
                        if (c.cut) {
                            if (!c.file.renameTo(target)) { c.file.copyRecursively(target, overwrite = false); c.file.deleteRecursively() }
                        } else c.file.copyRecursively(target, overwrite = false)
                    }
                    clip = null
                })
            }
            TvButton("Sort: ${sort.label}", icon = Icons.Filled.Sort, compact = true, onClick = { sort = FileSort.entries[(sort.ordinal + 1) % FileSort.entries.size] })
            TvButton(if (showHidden) "Hiding nothing" else "Show hidden", icon = Icons.Filled.Visibility, compact = true, selected = showHidden, onClick = { showHidden = !showHidden })
            busy?.let { Text("  $it…", style = AkoyaType.label, color = p.accent) }
        }
        Text(
            dir.absolutePath,
            style = AkoyaType.small,
            color = p.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 56.dp, end = 56.dp, top = 8.dp),
        )

        if (!granted) {
            Column(Modifier.padding(56.dp)) {
                Text("Allow storage access to browse files", style = AkoyaType.title, color = p.text)
                Text("Needed to see Downloads, Movies and USB drives. Akoya's own photo folder works without it.", style = AkoyaType.body, color = p.muted)
                Spacer(Modifier.size(12.dp))
                TvButton("Allow access", onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        controller.apps.startFirst(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        )
                    } else {
                        askPerm.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    }
                })
            }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides ListScrollSpec) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 10.dp, bottom = 120.dp),
            ) {
                if (entries.isEmpty()) {
                    item { Text(if (granted) "This folder is empty." else "", style = AkoyaType.body, color = p.muted, modifier = Modifier.padding(16.dp)) }
                }
                items(entries, key = { it.absolutePath }) { f ->
                    FileRow(
                        file = f,
                        onClick = {
                            when {
                                f.isDirectory -> dir = f
                                f.extension.lowercase() in IMAGE_EXT -> viewer = f
                                else -> openFile(controller, f, chooser = false)
                            }
                        },
                        onMenu = { menuFor = f },
                    )
                }
            }
        }
    }

    // ---------------- dialogs
    menuFor?.let { f ->
        val isImage = f.extension.lowercase() in IMAGE_EXT
        OptionsDialog(
            title = f.name,
            subtitle = if (f.isDirectory) "Folder" else "${HealthCheck.human(f.length())} · ${DateFormat.getDateTimeInstance().format(Date(f.lastModified()))}",
            onDismiss = { menuFor = null },
            options = buildList {
                if (f.isDirectory) add(DialogOption("Open", Icons.Filled.Folder) { dir = f })
                else {
                    add(DialogOption("Open", Icons.Filled.OpenInNew) { if (isImage) viewer = f else openFile(controller, f, chooser = false) })
                    add(DialogOption("Open with…", Icons.Filled.OpenInNew) { openFile(controller, f, chooser = true) })
                }
                if (f.extension.equals("apk", true)) add(DialogOption("Install", Icons.Filled.InstallMobile) { openFile(controller, f, chooser = false) })
                if (isImage) {
                    add(DialogOption("Set as Home wallpaper", Icons.Filled.Wallpaper) {
                        op("Wallpaper") {
                            f.copyTo(File(controller.store.wallpapersDir, f.name), overwrite = true)
                            controller.store.update { it.copy(wallpaperMode = WallpaperMode.IMAGE, wallpaperFile = f.name) }
                        }
                    })
                    add(DialogOption("Add to screensaver", Icons.Filled.Nightlight) {
                        op("Add to screensaver") { f.copyTo(File(controller.store.screensaverDir, f.name), overwrite = true) }
                    })
                }
                add(DialogOption("Copy", Icons.Filled.ContentCopy) { clip = Clip(f, cut = false); controller.toast("Go to a folder and choose Paste") })
                add(DialogOption("Move", Icons.Filled.ContentCut) { clip = Clip(f, cut = true); controller.toast("Go to a folder and choose Paste") })
                add(DialogOption("Rename", Icons.Filled.DriveFileRenameOutline) { renaming = f })
                add(DialogOption("Delete", Icons.Filled.Delete) { confirmDelete = f })
            },
        )
    }
    renaming?.let { f ->
        TextInputDialog(
            title = "Rename",
            initial = f.name,
            onDone = { n -> if (n.isNotBlank()) op("Rename") { check(f.renameTo(File(f.parentFile, n))) { "name taken" } } },
            onDismiss = { renaming = null },
        )
    }
    if (newFolder) {
        TextInputDialog(
            title = "New folder",
            initial = "",
            onDone = { n -> if (n.isNotBlank()) op("New folder") { check(File(dir, n).mkdirs()) { "couldn't create" } } },
            onDismiss = { newFolder = false },
        )
    }
    confirmDelete?.let { f ->
        OptionsDialog(
            title = "Delete ${f.name}?",
            subtitle = if (f.isDirectory) "The folder and everything in it will be removed." else "This can't be undone.",
            onDismiss = { confirmDelete = null },
            options = listOf(
                DialogOption("Cancel") {},
                DialogOption("Delete", Icons.Filled.Delete) { op("Delete") { check(f.deleteRecursively()) { "couldn't delete" } } },
            ),
        )
    }
    viewer?.let { f ->
        val images = entries.filter { it.isFile && it.extension.lowercase() in IMAGE_EXT }
        ImageViewer(images, images.indexOf(f).coerceAtLeast(0), onDismiss = { viewer = null })
    }
}

@Composable
private fun FileRow(file: File, onClick: () -> Unit, onMenu: () -> Unit) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val ext = file.extension.lowercase()
    val icon: ImageVector = when {
        file.isDirectory -> Icons.Filled.Folder
        ext in IMAGE_EXT -> Icons.Filled.Image
        ext in VIDEO_EXT -> Icons.Filled.Movie
        ext in AUDIO_EXT -> Icons.Filled.MusicNote
        ext == "apk" -> Icons.Filled.InstallMobile
        else -> Icons.Filled.Description
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) p.text.copy(alpha = 0.12f) else Color.Transparent)
            .tvClickable(onClick, onMenu) { focused = it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (file.isDirectory) p.accent else p.muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(file.name, style = AkoyaType.body, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            if (file.isDirectory) "" else HealthCheck.human(file.length()),
            style = AkoyaType.small,
            color = p.muted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(file.lastModified())), style = AkoyaType.small, color = p.muted)
        if (focused) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.Info, contentDescription = "Hold OK for options", tint = p.muted, modifier = Modifier.size(16.dp))
        }
    }
}

private fun openFile(controller: LauncherController, f: File, chooser: Boolean) {
    val ctx = controller.context
    val ext = f.extension.lowercase()
    val mime = if (ext == "apk") "application/vnd.android.package-archive"
    else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    val uri = runCatching { FileProvider.getUriForFile(ctx, "com.akoya.home.files", f) }.getOrElse { Uri.fromFile(f) }
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    controller.apps.start(if (chooser) Intent.createChooser(view, f.name) else view)
}

@Composable
private fun ImageViewer(images: List<File>, start: Int, onDismiss: () -> Unit) {
    var index by remember { mutableIntStateOf(start) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(fr)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { index = (index + 1) % images.size; true }
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> { index = (index - 1 + images.size) % images.size; true }
                        NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> { onDismiss(); true }
                        else -> false
                    }
                }
                .focusable(),
        ) {
            if (images.isNotEmpty()) {
                AsyncImage(model = images[index], contentDescription = images[index].name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Text(
                    "${images[index].name}   ·   ${index + 1} / ${images.size}   ·   ◀ ▶ to browse",
                    style = AkoyaType.small,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                )
            }
        }
    }
}
