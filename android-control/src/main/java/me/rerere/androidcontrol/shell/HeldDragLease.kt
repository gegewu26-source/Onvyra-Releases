package me.rerere.androidcontrol.shell

import me.rerere.androidcontrol.ui.PreviewDisplayGeometry

internal const val MAX_HELD_DRAG_MS = 60_000L
internal const val MAX_HELD_DRAG_MOVE_MS = 1_000

/** A held finger belongs to one owned-display generation and one logical viewport. */
internal data class HeldDragLease(
    val token: Long,
    val displayId: Int,
    val geometry: PreviewDisplayGeometry,
    val downTime: Long,
) {
    init { require(token > 0 && displayId > 0 && downTime >= 0) }

    fun matches(requestDisplayId: Int, requestToken: Long): Boolean =
        requestDisplayId == displayId && requestToken == token

    fun mayContinue(currentGeometry: PreviewDisplayGeometry?, now: Long, x: Int, y: Int): Boolean =
        currentGeometry == geometry && now >= downTime && now - downTime < MAX_HELD_DRAG_MS &&
            geometry.contains(x, y)
}
