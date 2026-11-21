package me.rerere.androidcontrol.shell

/** ActivityInfo orientation values, evaluated against this virtual display's natural axes. */
internal fun requestedDisplayRotation(
    requestedOrientation: Int,
    naturalWidth: Int,
    naturalHeight: Int,
    currentRotation: Int,
): Int? {
    require(naturalWidth > 0 && naturalHeight > 0 && currentRotation in 0..3)
    val naturalPortrait = naturalHeight >= naturalWidth
    val portrait = if (naturalPortrait) 0 else 1
    val landscape = if (naturalPortrait) 1 else 0
    fun preserveAxis(axis: Int): Int = if (currentRotation % 2 == axis % 2) currentRotation else axis
    return when (requestedOrientation) {
        0 -> landscape // SCREEN_ORIENTATION_LANDSCAPE
        1 -> portrait // SCREEN_ORIENTATION_PORTRAIT
        6, 11 -> preserveAxis(landscape) // SENSOR_LANDSCAPE / USER_LANDSCAPE
        7, 12 -> preserveAxis(portrait) // SENSOR_PORTRAIT / USER_PORTRAIT
        8 -> (landscape + 2) % 4 // REVERSE_LANDSCAPE
        9 -> (portrait + 2) % 4 // REVERSE_PORTRAIT
        else -> null // Unspecified/sensor/user/full-sensor/locked retain Android's own policy.
    }
}

/** Read only the selected task's ActivityRecord; display/task summaries are not app requests. */
internal fun requestedOrientationFromActivityDump(dump: String, taskId: Int, component: String): Int? {
    require(taskId >= 0 && component.contains('/'))
    val activityHeaders = Regex("(?m)^([ \\t]*)\\* Hist[^\\n]*ActivityRecord\\{[^\\n]+\\}").findAll(dump).toList()
    val header = activityHeaders.firstOrNull { row ->
        val text = row.value
        text.contains(component) && Regex("\\bt$taskId(?:[ \\t}]|$)").containsMatchIn(text)
    } ?: return null
    val indent = header.groupValues[1].length
    val body = dump.substring(header.range.last + 1).lineSequence().takeWhile { line ->
        line.isBlank() || line.takeWhile { it == ' ' || it == '\t' }.length > indent
    }
    val value = body.map(String::trim).firstOrNull { it.startsWith("requestedOrientation=") }
        ?.substringAfter('=')?.trim()?.removePrefix("SCREEN_ORIENTATION_") ?: return null
    value.toIntOrNull()?.let { return it.takeIf { request -> request in -1..14 } }
    return when (value) {
        "UNSPECIFIED" -> -1
        "LANDSCAPE" -> 0
        "PORTRAIT" -> 1
        "USER" -> 2
        "BEHIND" -> 3
        "SENSOR" -> 4
        "NOSENSOR" -> 5
        "SENSOR_LANDSCAPE" -> 6
        "SENSOR_PORTRAIT" -> 7
        "REVERSE_LANDSCAPE" -> 8
        "REVERSE_PORTRAIT" -> 9
        "FULL_SENSOR" -> 10
        "USER_LANDSCAPE" -> 11
        "USER_PORTRAIT" -> 12
        "FULL_USER" -> 13
        "LOCKED" -> 14
        else -> null
    }
}
