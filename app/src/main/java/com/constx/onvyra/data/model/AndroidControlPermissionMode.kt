package com.constx.onvyra.data.model

import kotlinx.serialization.Serializable

/** Conversation-wide task approval choice. The persisted name remains compatible with old phone-only settings. */
@Serializable
enum class AndroidControlPermissionMode {
    CONFIRM_CHANGES,
    AUTONOMOUS,
    FULL_ACCESS,
}
