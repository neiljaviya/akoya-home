package com.akoya.home.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/** Material "emphasized decelerate": quick start, long soft landing. */
val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Keeps the focused item's leading edge at a fixed fraction of the viewport (stock-launcher feel). */
@OptIn(ExperimentalFoundationApi::class)
class PivotScrollSpec(private val pivot: Float, durationMs: Int = 450) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMs, easing = Emphasized)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - containerSize * pivot
}

/** Scrolls only when the focused item gets close to an edge; otherwise focus just moves. */
@OptIn(ExperimentalFoundationApi::class)
class EdgeScrollSpec(private val margin: Float, durationMs: Int = 400) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMs, easing = Emphasized)
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
