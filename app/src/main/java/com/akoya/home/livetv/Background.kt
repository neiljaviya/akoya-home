package com.akoya.home.livetv

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where the heavy, unhurried work runs: playlist downloads, guide parsing, health checks.
 * The threads are marked "background" so Android always serves the picture and the remote first.
 */
object Background {
    private val count = AtomicInteger()

    val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(4) { work ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            work.run()
        }, "akoya-bg-" + count.incrementAndGet()).apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
