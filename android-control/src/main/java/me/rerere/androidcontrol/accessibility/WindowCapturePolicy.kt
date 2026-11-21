package me.rerere.androidcontrol.accessibility

/** A window screenshot can be scaled by a vendor compositor. Preview never exports its pixel coordinates. */
internal data class CaptureWindowGeometry(val id: Int, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

internal fun captureWindowIsCurrent(before: CaptureWindowGeometry, after: CaptureWindowGeometry?,
    bitmapWidth: Int, bitmapHeight: Int, requireExactCoordinates: Boolean): Boolean =
    before == after && before.width > 0 && before.height > 0 && bitmapWidth > 0 && bitmapHeight > 0 &&
        (!requireExactCoordinates || (bitmapWidth == before.width && bitmapHeight == before.height))

/** Values are framework TakeScreenshotCallback error codes; no failure grants native output. */
internal fun previewCaptureFailure(error: Int?, detail: String? = null): String = detail ?: when (error) {
    2 -> "accessibility_unavailable"
    3 -> "screenshot_rate_limited"
    4 -> "display_unavailable"
    5 -> "window_not_ready"
    6 -> "secure_window"
    else -> "screenshot_unavailable"
}

internal fun previewCaptureMessage(failure: String): String = when (failure) {
    "secure_window" -> "系统未提供受保护窗口的画面，正在重试…"
    "accessibility_unavailable" -> "无障碍服务正在恢复连接…"
    "screenshot_rate_limited" -> "系统截图繁忙，正在重试…"
    "display_unavailable" -> "后台屏幕尚未就绪，正在重试…"
    "window_not_ready", "window_changed" -> "正在等待应用窗口稳定…"
    "window_geometry_mismatch" -> "正在等待窗口尺寸同步…"
    "screenshot_timeout" -> "首帧生成较慢，正在重试…"
    else -> "暂未取得画面，正在重试…"
}

/** One recovery episode is bounded; stable/protected windows use the normal guard cadence. */
internal fun previewRetryIntervalMs(authorized: Boolean, failure: String?, episodeElapsedMs: Long): Long = when {
    authorized -> 1_000L
    failure == "secure_window" -> 1_000L
    episodeElapsedMs < 10_000L -> 350L
    else -> 1_000L
}

/** A later page refresh gets its own recovery budget only after a successful protected capture. */
internal class PreviewCaptureRetryPolicy {
    private var failureEpisodeStartedAt: Long? = null

    fun onCaptureResult(succeeded: Boolean, nowMs: Long) {
        if (succeeded) failureEpisodeStartedAt = null
        else if (failureEpisodeStartedAt == null) failureEpisodeStartedAt = nowMs
    }

    fun intervalMs(authorized: Boolean, failure: String?, nowMs: Long): Long {
        if (authorized) return previewRetryIntervalMs(true, failure, 0L)
        // Include skipped initial captures/expired guards in the same bounded waiting episode.
        val started = failureEpisodeStartedAt ?: nowMs.also { failureEpisodeStartedAt = it }
        return previewRetryIntervalMs(false, failure, (nowMs - started).coerceAtLeast(0L))
    }
}

/** A rejected Surface bind needs both a cooldown and a newer successful protected screenshot. */
internal class PreviewNativeRetryPolicy {
    private var successfulCaptures = 0L
    private var rejectedAtCapture = -1L
    private var failures = 0
    private var retryAtMs = 0L

    fun onProtectedCaptureSucceeded() { successfulCaptures++ }

    fun retryReady(nowMs: Long): Boolean =
        nowMs >= retryAtMs && successfulCaptures > rejectedAtCapture

    fun canAttempt(nowMs: Long, authorized: Boolean, validSurface: Boolean): Boolean =
        authorized && validSurface && successfulCaptures > 0L && retryReady(nowMs)

    fun onAttachResult(accepted: Boolean, nowMs: Long) {
        if (accepted) {
            failures = 0
            retryAtMs = 0L
            rejectedAtCapture = -1L
        } else {
            failures = (failures + 1).coerceAtMost(4)
            rejectedAtCapture = successfulCaptures
            retryAtMs = nowMs + (500L shl (failures - 1))
        }
    }
}
