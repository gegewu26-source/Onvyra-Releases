package com.constx.onvyra.service

import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.Folder
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/** The drawer's folder is a destination for new drafts, never a move command for saved chats. */
internal class DraftFolderSelection {
    private var assistantId: Uuid? = null
    private var folderId: Uuid? = null

    @Synchronized
    fun select(assistantId: Uuid, folderId: Uuid?) {
        this.assistantId = assistantId
        this.folderId = folderId
    }

    @Synchronized
    fun selectedFor(assistantId: Uuid): Uuid? = folderId.takeIf { this.assistantId == assistantId }

    @Synchronized
    fun clear(folderId: Uuid) {
        if (this.folderId == folderId) this.folderId = null
    }
}

internal fun validDraftFolder(assistantId: Uuid, requestedId: Uuid?, folder: Folder?): Uuid? =
    requestedId?.takeIf { folder != null && folder.id == it && folder.assistantId == assistantId }

internal fun Conversation.canFollowSelectedFolder(
    persisted: Boolean,
    temporary: Boolean,
    presetMessages: List<UIMessage>,
): Boolean = !persisted && !temporary && newConversation && title.isBlank() &&
    currentMessages == presetMessages
