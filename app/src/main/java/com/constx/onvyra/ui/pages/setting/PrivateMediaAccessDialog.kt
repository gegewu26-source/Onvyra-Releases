package com.constx.onvyra.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.constx.onvyra.security.PrivateMediaAuthorization
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Intentionally reachable only from the about-page gesture. Never saves input in UI state bundles. */
@Composable
internal fun PrivateMediaAccessDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    fun expiryMessage(expires: Long?): String = expires?.let {
        "已开启，有效至 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))}。"
    } ?: "功能已关闭。"

    LaunchedEffect(context) {
        val expires = withContext(Dispatchers.IO) { runCatching { PrivateMediaAuthorization.get(context).expiresAt() }.getOrNull() }
        active = expires != null
        if (active) message = expiryMessage(expires)
    }

    AlertDialog(
        onDismissRequest = { if (!busy) { code = ""; onDismiss() } },
        title = { Text("访问验证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!active) {
                    Text("输入本期 16 位口令，支持粘贴和分组输入。")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { if (it.length <= 64 && !busy) code = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("口令") },
                        enabled = !busy,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    )
                }
                if (message.isNotBlank()) Text(message)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (active || code.isNotBlank()),
                onClick = {
                    busy = true
                    val input = code
                    code = ""
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val authorization = PrivateMediaAuthorization.get(context)
                                if (active) {
                                    val persisted = authorization.lock()
                                    Triple(false, if (persisted) "功能已关闭。" else "功能已关闭，本地安全存储暂时不可用。", null)
                                } else {
                                    val validation = authorization.unlock(input)
                                    Triple(validation.successful, validation.message,
                                        if (validation.successful) authorization.expiresAt() else null)
                                }
                            }.getOrElse { Triple(false, "验证暂时不可用，请稍后重试。", null) }
                        }
                        active = result.first
                        message = if (result.first) expiryMessage(result.third) else result.second
                        busy = false
                    }
                },
            ) { Text(if (busy) "处理中" else if (active) "关闭功能" else "验证") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { code = ""; onDismiss() }) { Text(if (active) "完成" else "取消") }
        },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}
