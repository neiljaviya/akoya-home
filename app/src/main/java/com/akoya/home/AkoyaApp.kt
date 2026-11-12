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
