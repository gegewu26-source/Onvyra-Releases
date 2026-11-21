package com.constx.onvyra.utils

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.constx.onvyra.R
import com.constx.onvyra.RouteActivity
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.ui.activity.ConversationUnlockActivity

/** A device credential must exist before a conversation can be locked. */
fun conversationDeviceLockAvailable(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

/** Only the private authentication activity can produce a successful result. */
fun conversationUnlockIntent(context: Context, title: String): Intent =
    Intent(context, ConversationUnlockActivity::class.java)
        .putExtra(ConversationUnlockActivity.EXTRA_TITLE, title.take(120))

/** Returns whether the launcher accepted a pin request, not whether the user confirmed it. */
fun requestConversationShortcut(context: Context, conversation: Conversation): Boolean {
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
    if (!manager.isRequestPinShortcutSupported) return false
    val label = if (conversation.isLocked) "已锁定的聊天" else conversation.title.ifBlank { "新聊天" }
    val intent = Intent(context, RouteActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("conversationId", conversation.id.toString())
    }
    val shortcut = ShortcutInfo.Builder(context, "conversation-${conversation.id}")
        .setShortLabel(label.take(40))
        .setLongLabel(label.take(120))
        .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(intent)
        .build()
    return manager.requestPinShortcut(shortcut, null)
}
