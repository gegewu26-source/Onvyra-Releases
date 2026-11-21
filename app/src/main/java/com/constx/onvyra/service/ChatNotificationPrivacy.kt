package com.constx.onvyra.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.constx.onvyra.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import com.constx.onvyra.R
import com.constx.onvyra.utils.cancelNotification
import com.constx.onvyra.utils.sendNotification
import kotlin.uuid.Uuid

internal enum class ChatNotificationVisibility { NORMAL, LOCKED, TEMPORARY, HIDDEN }

/** The private branch is lazy: reasoning, tool arguments and text are never inspected. */
internal fun chatNotificationPresentation(
    visibility: ChatNotificationVisibility,
    appName: String,
    senderName: String,
    completed: Boolean,
    normal: () -> Triple<String, String, String>,
): Pair<String, Triple<String, String, String>>? = when (visibility) {
    ChatNotificationVisibility.HIDDEN -> null
    ChatNotificationVisibility.LOCKED -> appName to Triple("已锁定", "内容已隐藏", if (completed) "锁定聊天的回复已完成" else "锁定聊天正在进行")
    ChatNotificationVisibility.TEMPORARY -> appName to Triple("临时聊天", "内容已隐藏", if (completed) "临时聊天已结束" else "临时聊天正在进行")
    ChatNotificationVisibility.NORMAL -> senderName to normal()
}

/** Serializes privacy changes with notify(), including an already queued generation event. */
internal object ChatNotificationPrivacy {
    private val lockedOverrides = mutableMapOf<Uuid, Boolean>()

    @Synchronized fun <T> withVisibility(
        id: Uuid,
        storedLocked: Boolean,
        action: (ChatNotificationVisibility) -> T,
    ): T = action(when {
        temporaryChatRegistry.isClosed(id) -> ChatNotificationVisibility.HIDDEN
        temporaryChatRegistry.blocksStorage(id) -> ChatNotificationVisibility.TEMPORARY
        lockedOverrides[id] ?: storedLocked -> ChatNotificationVisibility.LOCKED
        else -> ChatNotificationVisibility.NORMAL
    })

    @Synchronized fun setLocked(context: Context, id: Uuid, locked: Boolean) {
        lockedOverrides[id] = locked
        if (!locked) return
        // Completion notifications use one shared ID. Remove its content immediately.
        context.cancelNotification(1)
        val existing = runCatching { context.getSystemService(NotificationManager::class.java)
            .activeNotifications.firstOrNull { it.id == ChatGenerationForegroundService.NOTIFICATION_ID } }.getOrNull()
        if (existing != null) {
            // A foreground notification cannot always be cancelled; overwrite its body instead.
            context.sendNotification(CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID, ChatGenerationForegroundService.NOTIFICATION_ID) {
                title = context.getString(R.string.app_name)
                content = "锁定聊天正在进行"
                subText = "内容已隐藏"
                ongoing = existing.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
                onlyAlertOnce = true
                category = NotificationCompat.CATEGORY_PROGRESS
            }
        }
    }
}
