package com.akoya.home.livetv

import kotlin.math.abs
import kotlin.math.roundToInt

/** Auto frame rate: working out a video's frame rate and which display rate suits it. */
object FrameRate {
    private val STANDARD = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)

    /** Snaps a measured rate (e.g. 25.02) to the broadcast rate it must be, or 0 if it's none of them. */
    fun snap(measured: Float): Float {
        val best = STANDARD.minByOrNull { abs(it - measured) } ?: return 0f
        return if (abs(best - measured) / best < 0.02f) best else 0f
    }

    /** Frame rate from the gaps between frame timestamps (microseconds). */
    fun fromTimestamps(timesUs: LongArray, count: Int): Float {
        if (count < 8) return 0f
        val gaps = ArrayList<Long>(count)
        for (i in 1 until count) {
            val d = timesUs[i] - timesUs[i - 1]
            if (d > 0) gaps += d
        }
        if (gaps.size < 6) return 0f
        gaps.sort()
        val median = gaps[gaps.size / 2]
        return snap(1_000_000f / median)
    }

    /**
     * The display refresh rate to switch to for [fps], or null to stay put.
     * A rate that is a whole multiple of the video's rate plays without judder (25 on 50, 30 on 60).
     * Stays on [current] when that already fits, so the screen only blanks when it has to.
     */
    fun pick(fps: Float, available: List<Float>, current: Float): Float? {
        if (fps <= 0f || available.isEmpty()) return null
        fun error(rate: Float): Float {
            val ratio = rate / fps
            val whole = ratio.roundToInt()
            return if (whole < 1) Float.MAX_VALUE else abs(ratio - whole) / whole
        }
        if (error(current) < 0.0005f) return null
        val fitting = available.filter { error(it) < 0.002f }
        if (fitting.isEmpty()) return null
        if (fitting.any { abs(it - current) < 0.01f }) {
            // Current is close (60 for a 59.94 source): only move for an exact match.
            val exact = fitting.filter { error(it) < 0.0005f }.maxOrNull() ?: return null
            return if (abs(exact - current) < 0.01f) null else exact
        }
        val best = fitting.minOf { error(it) }
        return fitting.filter { error(it) - best < 0.0005f }.maxOrNull()
    }
}
