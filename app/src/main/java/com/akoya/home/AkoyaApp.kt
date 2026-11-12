package com.akoya.home

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** App-wide set-up. Today that is just how pictures (logos, posters, wallpapers) are loaded. */
class AkoyaApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        crashFile = java.io.File(filesDir, "last-crash.txt")
        version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        // Keep what went wrong, so it can be read from the phone page instead of needing a cable.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write("Crash on thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        @Volatile var crashFile: java.io.File? = null
            private set
        private var version = ""

        /** Records a problem that was caught and survived, in the same place as crashes. */
        fun note(where: String, error: Throwable) = write("Caught: $where", error)

        private fun write(title: String, error: Throwable) {
            runCatching {
                val f = crashFile ?: return
                val trace = java.io.StringWriter().also { error.printStackTrace(java.io.PrintWriter(it)) }.toString()
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                f.writeText("$title\n$stamp  ·  version $version  ·  ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n\n${trace.take(12_000)}")
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // No fade-in: on a slow TV dozens of fading tiles cost more than they add.
        .crossfade(false)
        // Posters and photos have no transparency, so they can take half the memory.
        .allowRgb565(true)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.18).build() }
        // Logo and poster hosts often say "don't cache"; keep them on the TV anyway so lists
        // don't download the same pictures every time they open.
        .respectCacheHeaders(false)
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
        // A few at a time: decoding many pictures at once is what makes scrolling stutter.
        .fetcherDispatcher(Dispatchers.IO.limitedParallelism(4))
        .decoderDispatcher(Dispatchers.Default.limitedParallelism(2))
        .build()
}
