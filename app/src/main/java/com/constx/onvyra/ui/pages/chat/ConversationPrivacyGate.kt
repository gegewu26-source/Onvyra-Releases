package com.constx.onvyra.ui.pages.chat

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.utils.getActivity
import com.constx.onvyra.utils.conversationUnlockIntent

/** Authentication lives only in this foreground composition, never in saved state or a deep link. */
@Composable
internal fun ConversationPrivacyGate(conversation: Conversation, onLeave: () -> Unit): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = context.getActivity()
    var unlocked by remember(conversation.id, conversation.isLocked) { mutableStateOf(false) }
    var authenticationPending by remember(conversation.id) { mutableStateOf(false) }
    var error by remember(conversation.id) { mutableStateOf<String?>(null) }
    val authentication = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        authenticationPending = false
        unlocked = result.resultCode == Activity.RESULT_OK
        error = if (unlocked) null else "未完成验证，聊天仍已锁定"
    }
    DisposableEffect(lifecycle, conversation.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !authenticationPending) unlocked = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(activity, conversation.isLocked) {
        val window = activity?.window
        val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (conversation.isLocked) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (conversation.isLocked && !wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    if (!conversation.isLocked || unlocked) return false
    Surface(Modifier.fillMaxSize().testTag("conversation-locked"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("聊天已锁定", style = MaterialTheme.typography.headlineSmall)
            Text("验证身份后查看此聊天", Modifier.padding(top = 12.dp, bottom = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(enabled = !authenticationPending, onClick = {
                try {
                    authenticationPending = true
                    authentication.launch(conversationUnlockIntent(context, "解锁聊天"))
                } catch (_: Exception) {
                    authenticationPending = false
                    error = "无法启动系统验证，请先设置系统锁屏"
                }
            }, modifier = Modifier.testTag("unlock-conversation")) { Text("解锁聊天") }
            error?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onLeave) { Text("新建聊天") }
        }
    }
    return true
}
