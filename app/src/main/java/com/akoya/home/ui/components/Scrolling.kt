package com.akoya.home.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/** Material "emphasized decelerate": quick start, long soft landing. */
val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Whether the remote is being pressed quickly or held. Long, soft scroll animations look good
 * for a single press but pile up when you hold a direction, so lists switch to a short,
 * linear glide (or no animation at all) while this is true.
 */
object ScrollPace {
    private var last = 0L
    @Volatile var fast = false
        private set

    val quick: AnimationSpec<Float> = tween(90, easing = LinearEasing)

    /** Call for every key event the activity sees. */
    fun onKey(event: android.view.KeyEvent) {
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
            else -> return
        }
        val now = event.eventTime
        fast = event.repeatCount > 0 || now - last < 220
        last = now
    }
}

/** Keeps the focused item's leading edge at a fixed fraction of the viewport (stock-launcher feel). */
@OptIn(ExperimentalFoundationApi::class)
class PivotScrollSpec(private val pivot: Float, durationMs: Int = 450) : BringIntoViewSpec {
    private val calm: AnimationSpec<Float> = tween(durationMs, easing = Emphasized)
    override val scrollAnimationSpec: AnimationSpec<Float> get() = if (ScrollPace.fast) ScrollPace.quick else calm
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - containerSize * pivot
}

/** Scrolls only when the focused item gets close to an edge; otherwise focus just moves. */
@OptIn(ExperimentalFoundationApi::class)
class EdgeScrollSpec(private val margin: Float, durationMs: Int = 400) : BringIntoViewSpec {
    private val calm: AnimationSpec<Float> = tween(durationMs, easing = Emphasized)
    override val scrollAnimationSpec: AnimationSpec<Float> get() = if (ScrollPace.fast) ScrollPace.quick else calm
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val m = containerSize * margin
        val trailing = offset + size
        return when {
            size >= containerSize - 2 * m -> offset - m
            offset < m -> offset - m
            trailing > containerSize - m -> trailing - (containerSize - m)
            else -> 0f
        }
    }
}

val RowScrollSpec = EdgeScrollSpec(0.08f)
val ListScrollSpec = EdgeScrollSpec(0.16f)
val HomeScrollSpec = PivotScrollSpec(0.11f)

/** Disables automatic bring-into-view scrolling for a container that scrolls itself. */
@OptIn(ExperimentalFoundationApi::class)
object NoScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}
