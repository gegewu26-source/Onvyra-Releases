package com.constx.onvyra.utils

import android.os.SystemClock

/** Bounded UI phase names only; never accepts titles, URLs, messages or credentials. */
internal object UiCrashBreadcrumbs {
    enum class Phase {
        READER_SOURCE_PICKER_OPEN, READER_SOURCE_PICKER_CLOSE,
        READER_SOURCE_SWITCH_BEGIN, READER_SOURCE_SWITCH_COMMIT, READER_SOURCE_SWITCH_FAILED,
        VIDEO_SOURCE_PICKER_OPEN, VIDEO_SOURCE_PICKER_CLOSE,
        VIDEO_SOURCE_SWITCH_BEGIN, VIDEO_SOURCE_SWITCH_COMMIT, VIDEO_SOURCE_SWITCH_FAILED,
    }
    private val entries = ArrayDeque<Pair<Long, Phase>>()

    @Synchronized fun mark(phase: Phase) {
        if (entries.size == 24) entries.removeFirst()
        entries.addLast(SystemClock.elapsedRealtime() to phase)
    }

    @Synchronized fun snapshot(): String = entries.joinToString("\n") { (time, phase) -> "$time $phase" }
}
