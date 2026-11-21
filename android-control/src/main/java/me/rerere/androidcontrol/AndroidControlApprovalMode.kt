package me.rerere.androidcontrol

/** Selected by the user in the host UI; never accepted from a model tool argument. */
enum class AndroidControlApprovalMode { CONFIRM_CHANGES, AUTONOMOUS, FULL_ACCESS }

internal fun requiresControlApproval(mode: AndroidControlApprovalMode, mutation: Boolean, risk: Boolean): Boolean =
    mutation && when (mode) {
        AndroidControlApprovalMode.CONFIRM_CHANGES -> true
        AndroidControlApprovalMode.AUTONOMOUS -> risk
        AndroidControlApprovalMode.FULL_ACCESS -> false
    }
