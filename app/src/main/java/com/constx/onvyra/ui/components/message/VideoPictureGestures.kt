package com.constx.onvyra.ui.components.message

import android.view.ViewConfiguration as AndroidViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class PendingVideoTap(val upTimeMillis: Long, val position: Offset, val job: Job)

/** One recognizer owns player gestures, including all fingers after a pinch. */
@Composable
internal fun Modifier.videoPictureGestures(
    canTransform: Boolean,
    onTap: () -> Unit,
    onTransform: (zoom: Float, panX: Float, panY: Float, focusX: Float, focusY: Float) -> Unit,
    onTransformInteraction: () -> Unit,
    canSeek: Boolean = false,
    onDoubleTap: () -> Unit = {},
    onSeekStart: () -> Boolean = { false },
    onSeekProgress: (Float) -> Unit = {},
    onSeekEnd: (Boolean) -> Unit = {},
    onHoldStart: () -> Boolean = { false },
    onHoldEnd: () -> Unit = {},
    canAdjustVertical: Boolean = false,
    onVerticalStart: (Boolean) -> Boolean = { false },
    onVerticalProgress: (Float) -> Unit = {},
    onVerticalEnd: () -> Unit = {},
): Modifier {
    val context = LocalContext.current
    val doubleTapSlop = remember(context) {
        AndroidViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    }
    val currentDoubleTapSlop by rememberUpdatedState(doubleTapSlop)
    val currentCanTransform by rememberUpdatedState(canTransform)
    val currentCanSeek by rememberUpdatedState(canSeek)
    val currentCanAdjustVertical by rememberUpdatedState(canAdjustVertical)
    val currentTap by rememberUpdatedState(onTap)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentTransform by rememberUpdatedState(onTransform)
    val currentInteraction by rememberUpdatedState(onTransformInteraction)
    val currentSeekStart by rememberUpdatedState(onSeekStart)
    val currentSeekProgress by rememberUpdatedState(onSeekProgress)
    val currentSeekEnd by rememberUpdatedState(onSeekEnd)
    val currentHoldStart by rememberUpdatedState(onHoldStart)
    val currentHoldEnd by rememberUpdatedState(onHoldEnd)
    val currentVerticalStart by rememberUpdatedState(onVerticalStart)
    val currentVerticalProgress by rememberUpdatedState(onVerticalProgress)
    val currentVerticalEnd by rememberUpdatedState(onVerticalEnd)
    // Callback and readiness changes must not cancel an active player gesture.
    return pointerInput(Unit) {
        coroutineScope {
            val tapScope = this
            val pendingTapJobs = mutableSetOf<Job>()
            var pendingTap: PendingVideoTap? = null
            var seeking = false
            var holding = false
            var adjustingVertical = false

            fun cancelPendingTaps() {
                pendingTapJobs.toList().forEach { it.cancel() }
                pendingTapJobs.clear()
                pendingTap = null
            }

            fun finishSeek(commit: Boolean) {
                if (seeking) {
                    seeking = false
                    currentSeekEnd(commit)
                }
            }

            fun finishHold() {
                if (holding) {
                    holding = false
                    currentHoldEnd()
                }
            }

            fun finishVertical() {
                if (adjustingVertical) {
                    adjustingVertical = false
                    currentVerticalEnd()
                }
            }

            fun scheduleTap(upTimeMillis: Long, position: Offset) {
                lateinit var job: Job
                job = tapScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        delay(viewConfiguration.doubleTapTimeoutMillis)
                        currentTap()
                    } finally {
                        pendingTapJobs.remove(job)
                        if (pendingTap?.job === job) pendingTap = null
                    }
                }
                pendingTapJobs.add(job)
                pendingTap = PendingVideoTap(upTimeMillis, position, job)
                job.start()
            }

            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val firstTap = pendingTap
                    var doubleTap = firstTap != null && firstTap.job.isActive &&
                        down.uptimeMillis - firstTap.upTimeMillis in
                        viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                        (down.position - firstTap.position).getDistance() <= currentDoubleTapSlop
                    if (doubleTap) {
                        firstTap?.job?.cancel()
                        pendingTap = null
                    }
                    var multiplePointers = false
                    var moved = false
                    var holdAttempted = false
                    var consumedElsewhere = down.isConsumed
                    var lastEventTime = down.uptimeMillis
                    var endedNormally = false
                    try {
                        do {
                            val canHold = !moved && !multiplePointers && !holdAttempted && !consumedElsewhere
                            val event = if (canHold) {
                                val remaining = viewConfiguration.longPressTimeoutMillis -
                                    (lastEventTime - down.uptimeMillis)
                                withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                holdAttempted = true
                                doubleTap = false
                                cancelPendingTaps()
                                holding = currentHoldStart()
                                if (holding) down.consume()
                                continue
                            }
                            lastEventTime = event.changes.maxOf { it.uptimeMillis }
                            if (event.changes.count { it.pressed || it.previousPressed } >= 2) {
                                if (!multiplePointers) {
                                    multiplePointers = true
                                    doubleTap = false
                                    cancelPendingTaps()
                                    finishSeek(commit = false)
                                    finishHold()
                                    finishVertical()
                                }
                            }
                            if (multiplePointers) {
                                if (currentCanTransform && event.changes.count { it.pressed && it.previousPressed } >= 2) {
                                    val focus = event.calculateCentroid(useCurrent = false)
                                    val pan = event.calculatePan()
                                    currentTransform(event.calculateZoom(), pan.x, pan.y, focus.x, focus.y)
                                    currentInteraction()
                                }
                                // The last remaining finger belongs to this pinch until it lifts.
                                event.changes.forEach { it.consume() }
                            } else {
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change != null) {
                                    val displacement = change.position - down.position
                                    if (!seeking && !holding && !adjustingVertical && change.isConsumed) {
                                        consumedElsewhere = true
                                        doubleTap = false
                                        cancelPendingTaps()
                                    }
                                    if (!moved && displacement.getDistance() > viewConfiguration.touchSlop) {
                                        moved = true
                                        doubleTap = false
                                        cancelPendingTaps()
                                        // Lock one direction; diagonal movement stays with the parent scroll.
                                        if (!holding && !holdAttempted && !consumedElsewhere && currentCanSeek &&
                                            abs(displacement.x) >= abs(displacement.y) * 1.25f) {
                                            seeking = currentSeekStart()
                                        } else if (!holding && !holdAttempted && !consumedElsewhere && currentCanAdjustVertical &&
                                            abs(displacement.y) >= abs(displacement.x) * 1.25f) {
                                            adjustingVertical = currentVerticalStart(down.position.x < size.width / 2f)
                                        }
                                    }
                                    when {
                                        holding -> {
                                            change.consume()
                                            if (change.changedToUpIgnoreConsumed()) finishHold()
                                        }
                                        seeking -> {
                                            // Compose delivers ACTION_CANCEL as a consumed synthetic UP.
                                            // Remember that flag before consuming our own normal UP.
                                            val cancelledRelease = change.changedToUpIgnoreConsumed() && change.isConsumed
                                            if (!cancelledRelease) currentSeekProgress(displacement.x / size.width.coerceAtLeast(1))
                                            change.consume()
                                            if (change.changedToUpIgnoreConsumed()) finishSeek(commit = !cancelledRelease)
                                        }
                                        adjustingVertical -> {
                                            currentVerticalProgress(-displacement.y / size.height.coerceAtLeast(1))
                                            change.consume()
                                            if (change.changedToUpIgnoreConsumed()) finishVertical()
                                        }
                                        change.changedToUpIgnoreConsumed() && !moved && !holdAttempted &&
                                            !consumedElsewhere &&
                                            change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis -> {
                                            if (doubleTap) currentDoubleTap()
                                            else scheduleTap(change.uptimeMillis, change.position)
                                            change.consume()
                                        }
                                    }
                                    // Unclaimed one-finger movement stays available to chat scrolling.
                                }
                            }
                        } while (event == null || event.changes.any { it.pressed })
                        endedNormally = true
                    } finally {
                        if (!endedNormally) cancelPendingTaps()
                        finishSeek(commit = false)
                        finishHold()
                        finishVertical()
                    }
                }
            } finally {
                cancelPendingTaps()
                finishSeek(commit = false)
                finishHold()
                finishVertical()
            }
        }
    }
}
