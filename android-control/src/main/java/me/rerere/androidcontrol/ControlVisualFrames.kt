package me.rerere.androidcontrol

/** Per owned session, never shared with another display or conversation. */
internal class ControlVisualFrames {
    private val namespace = java.util.UUID.randomUUID().toString()
    data class Frame(val id: String, val fingerprint: String, val windowId: Int?, val bounds: List<Int>,
        val revision: Long, val capturedAt: Long,
        val imageWidth: Int = bounds[2] - bounds[0], val imageHeight: Int = bounds[3] - bounds[1])
    data class Observation(val frame: Frame, val unchanged: Boolean, val actionUnchanged: Boolean, val blocked: Boolean)
    var frame: Frame? = null
        private set
    var blocked = false
        private set
    private var awaitingEffect = false
    private var noProgress = 0
    private var serial = 0L

    fun markAction() { if (frame != null) awaitingEffect = true }

    fun cached(windowId: Int?, bounds: List<Int>, revision: Long, now: Long): Frame? = frame?.takeIf {
        !awaitingEffect && it.windowId == windowId && it.bounds == bounds && it.revision == revision &&
            now - it.capturedAt in 0..1500
    }

    fun observe(fingerprint: String, windowId: Int?, bounds: List<Int>, revision: Long, now: Long,
        imageWidth: Int = bounds[2] - bounds[0], imageHeight: Int = bounds[3] - bounds[1]): Observation {
        require(bounds.size == 4 && bounds[2] > bounds[0] && bounds[3] > bounds[1])
        require(imageWidth > 0 && imageHeight > 0)
        val old = frame
        val unchanged = old != null && old.fingerprint == fingerprint && old.windowId == windowId && old.bounds == bounds &&
            old.imageWidth == imageWidth && old.imageHeight == imageHeight
        val actionUnchanged = awaitingEffect && unchanged
        if (actionUnchanged) noProgress++ else if (!unchanged) { noProgress = 0; blocked = false }
        if (noProgress >= 2) blocked = true
        awaitingEffect = false
        val observed = Frame(if (unchanged && old!!.revision == revision) old.id else "$namespace-frame-${++serial}",
            fingerprint, windowId, bounds.toList(), revision, now, imageWidth, imageHeight)
        frame = observed
        return Observation(observed, unchanged, actionUnchanged, blocked)
    }

    fun imagePoint(frameId: String?, x: Int, y: Int, windowId: Int?, bounds: List<Int>, revision: Long): Pair<Int, Int> {
        val current = checkNotNull(frame) { "screenshot_frame_required" }
        check(frameId == current.id && current.windowId == windowId && current.bounds == bounds && current.revision == revision) {
            "stale_screenshot_frame_refresh_required"
        }
        require(x in 0 until current.imageWidth && y in 0 until current.imageHeight) { "image_coordinates_out_of_bounds" }
        // Integer ratio avoids overflow and maps each uploaded pixel into the
        // same window's logical bounds, including landscape and cropped windows.
        val mappedX = x.toLong() * (bounds[2] - bounds[0]) / current.imageWidth
        val mappedY = y.toLong() * (bounds[3] - bounds[1]) / current.imageHeight
        return (mappedX.toInt() + bounds[0]) to (mappedY.toInt() + bounds[1])
    }
}
