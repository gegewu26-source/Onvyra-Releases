package com.constx.onvyra.ui.pages.setting

/** Recognizes the hidden gesture only; authorization is checked separately. */
internal class PrivateAccessTapSequence(
    private val maxTapIntervalMillis: Long = 10_000L,
) {
    enum class Target { LOGO, VERSION }

    private var matched = 0
    private var lastTapMillis: Long? = null

    init {
        require(maxTapIntervalMillis > 0)
    }

    /** Uses a monotonic clock, so changing the device's date cannot extend a gesture. */
    fun tap(target: Target, elapsedMillis: Long): Boolean {
        val previousTap = lastTapMillis
        if (previousTap != null &&
            (elapsedMillis < previousTap || elapsedMillis - previousTap > maxTapIntervalMillis)
        ) {
            reset()
        }

        if (target != expectedTargets[matched]) {
            reset()
            return false
        }

        lastTapMillis = elapsedMillis
        matched++
        if (matched != expectedTargets.size) return false

        reset()
        return true
    }

    fun reset() {
        matched = 0
        lastTapMillis = null
    }

    private companion object {
        val expectedTargets = listOf(
            Target.LOGO, Target.LOGO, Target.LOGO,
            Target.VERSION, Target.VERSION, Target.VERSION,
            Target.LOGO, Target.LOGO, Target.LOGO,
        )
    }
}
