package me.rerere.androidcontrol.shell

/** A PID alone is not an identity: Android may reuse it before a later launch. */
internal data class OwnedProcessSnapshot(val pid: Int, val uid: Int, val name: String,
    val startTime: Long, val packages: Set<String>, val removedTaskId: Int = -1)

/** Linux /proc/[pid]/stat field 22; comm may itself contain spaces or parentheses. */
internal fun processStartTime(stat: String): Long? = stat.substringAfterLast(") ", "")
    .trim().split(Regex("\\s+")).getOrNull(19)?.toLongOrNull()?.takeIf { it > 0 }

/** Only an unchanged, unprotected old process can still be awaiting removeTask's kill. */
internal fun removedProcessStillPending(expected: OwnedProcessSnapshot, current: OwnedProcessSnapshot?,
    protectedPackages: Set<String>, protectedPids: Set<Int>): Boolean =
    current != null && expected.pid == current.pid && expected.uid == current.uid &&
        expected.name == current.name && expected.startTime == current.startTime &&
        expected.pid !in protectedPids && expected.packages.none { it in protectedPackages }
