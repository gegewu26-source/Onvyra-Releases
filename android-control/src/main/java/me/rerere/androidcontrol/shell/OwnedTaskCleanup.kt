package me.rerere.androidcontrol.shell

internal data class OwnedTaskSnapshot(val id: Int, val displayId: Int, val packages: Set<String>)

/** Re-read immediately before removal: a task may have been handed to the main screen. */
internal fun ownedTaskCanBeRemoved(displayId: Int, launchedPackages: Set<String>,
    expected: OwnedTaskSnapshot, current: OwnedTaskSnapshot?): Boolean =
    displayId > 0 && current != null && expected.id == current.id &&
        expected.displayId == displayId && current.displayId == displayId &&
        current.packages == expected.packages && current.packages.any { it in launchedPackages }
