package com.akoya.home.server

import android.content.Context
import android.os.Environment
import java.io.File

data class FileRoot(val label: String, val file: File, val removable: Boolean)

/** Storage the file manager (TV and phone page) may touch: internal storage and USB drives. */
object FileRoots {
    fun list(ctx: Context): List<FileRoot> {
        val out = mutableListOf<FileRoot>()
        val internal = Environment.getExternalStorageDirectory()
        out += FileRoot("Internal storage", internal, false)
        File("/storage").listFiles()?.filter {
            it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead()
        }?.forEach { out += FileRoot("USB · ${it.name}", it, true) }
        ctx.getExternalFilesDir(null)?.let { out += FileRoot("Akoya photos & backups", it, false) }
        return out
    }

    fun isAllowed(f: File): Boolean {
        val p = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        return p.startsWith("/storage/") || p.startsWith("/sdcard") || p.startsWith("/mnt/media_rw/")
    }

    fun checked(path: String?): File {
        val f = File(path ?: throw IllegalArgumentException("Missing path"))
        if (!isAllowed(f)) throw SecurityException("Outside shared storage")
        return f
    }
}
