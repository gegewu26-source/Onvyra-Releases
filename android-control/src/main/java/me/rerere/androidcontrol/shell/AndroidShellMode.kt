package me.rerere.androidcontrol.shell

/** How the currently authenticated helper was started, not its operational UID. */
enum class AndroidShellMode { ROOT, WIRELESS, USB }

/** A remembered selection cannot label a different or disconnected process as started. */
internal fun activeShellMode(identity: IntArray?, startedPid: Int, startedMode: String?): AndroidShellMode? {
    if (!HelperHandoff.currentImplementation(identity)) return null
    if (identity!![1] != startedPid) return AndroidShellMode.USB
    return AndroidShellMode.entries.firstOrNull { it.name == startedMode } ?: AndroidShellMode.USB
}
