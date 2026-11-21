package com.constx.onvyra.ui.components.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.constx.onvyra.ui.components.ui.CardGroup

enum class SupportPayment(val assetPath: String, val title: String) {
    WECHAT("support/wechat.jpg", "微信赞赏"),
    ALIPAY("support/alipay.jpg", "支付宝赞赏"),
}

/** Shared by About and the launch dialog. The caller owns scrolling. */
@Composable
fun AuthorSupportContent(
    modifier: Modifier = Modifier,
    showIntroduction: Boolean = true,
    onQrSelected: (SupportPayment) -> Unit = {},
    onOpenCommunity: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val openCommunity = onOpenCommunity ?: uriHandler::openUri
    var copied by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showIntroduction) {
            Text("感谢你使用 Onvyra！ ❤️", style = MaterialTheme.typography.titleMedium)
            Text("Onvyra 是一个由个人开发和维护的项目。每一次更新与优化，都离不开用户的使用、反馈和支持。", style = MaterialTheme.typography.bodyMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("☕ 请作者喝杯咖啡", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("如果 Onvyra 对你有所帮助，欢迎赞赏支持。每一份心意，都是持续维护、修复问题和开发新功能的动力。", style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SupportPayment.entries.forEach { payment ->
                var imageLoaded by remember(payment) { mutableStateOf(false) }
                Surface(
                    modifier = Modifier.weight(1f).testTag("support-qr-${payment.name}")
                        .clickable(role = Role.Button, onClickLabel = "查看${payment.title}码") { onQrSelected(payment) },
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(color = Color.White, shape = MaterialTheme.shapes.medium) {
                            AsyncImage(
                                model = "file:///android_asset/${payment.assetPath}",
                                contentDescription = "${payment.title}码",
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.8f)
                                    .testTag(if (imageLoaded) "support-qr-loaded-${payment.name}" else "support-qr-loading-${payment.name}"),
                                contentScale = ContentScale.Fit,
                                onSuccess = { imageLoaded = true },
                            )
                        }
                        Text(payment.title, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Text("扫码即可支持，金额随心，完全自愿 ❤️", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("🌐 联系与交流", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        CardGroup {
            item(
                modifier = Modifier.testTag("support-qq-join"),
                onClick = {
                    runCatching { openCommunity(AuthorSupportCommunity.QQ_INVITE_URL) }.onFailure {
                        Toast.makeText(context, "暂时无法打开 QQ 加群入口，可复制群号搜索", Toast.LENGTH_SHORT).show()
                    }
                },
                headlineContent = { Text("QQ 交流群 · 点击加入") },
                supportingContent = { Text("群号：${AuthorSupportCommunity.QQ_GROUP}") },
                trailingContent = {
                    TextButton(
                        modifier = Modifier.testTag("support-qq-copy"),
                        onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("Onvyra QQ 交流群", AuthorSupportCommunity.QQ_GROUP))
                            copied = true
                        },
                    ) {
                        Text(if (copied) "已复制" else "复制群号", style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
            item(
                modifier = Modifier.testTag("support-telegram"),
                onClick = {
                    runCatching { openCommunity(AuthorSupportCommunity.TELEGRAM_URL) }.onFailure {
                        Toast.makeText(context, "暂时无法打开链接", Toast.LENGTH_SHORT).show()
                    }
                },
                headlineContent = { Text("Telegram 社区 · 点击加入") },
                supportingContent = { Text(AuthorSupportCommunity.TELEGRAM_URL) },
            )
        }
        if (showIntroduction) {
            Text("感谢支持", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("感谢每一位使用、反馈、分享和赞赏 Onvyra 的朋友。因为有你们，项目才能不断进步。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun AuthorSupportDialog(
    onDismiss: () -> Unit,
    onPresented: () -> Unit = {},
    onOpenCommunity: ((String) -> Unit)? = null,
) {
    var selected by rememberSaveable { mutableStateOf<SupportPayment?>(null) }
    val maximumHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            onPresented()
        }
        Surface(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.94f).heightIn(max = maximumHeight).testTag("author-support-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("🎉 欢迎使用 Onvyra", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("感谢你的使用与支持", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AuthorSupportContent(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("author-support-body"),
                    showIntroduction = false,
                    onQrSelected = { selected = it },
                    onOpenCommunity = onOpenCommunity,
                )
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("support-dismiss")) {
                    Text("开始使用")
                }
            }
        }
    }
    selected?.let { payment -> AuthorSupportImageDialog(payment, onDismiss = { selected = null }) }
}

@Composable
fun AuthorSupportImageDialog(payment: SupportPayment, onDismiss: () -> Unit) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    var imageLoaded by remember(payment) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(0.96f).heightIn(max = screenHeight * 0.92f).testTag("support-image-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${payment.title}码", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("support-image-close")) { Text("关闭") }
                }
                Surface(Modifier.fillMaxWidth(), color = Color.White, shape = MaterialTheme.shapes.large) {
                    AsyncImage(
                        model = "file:///android_asset/${payment.assetPath}",
                        contentDescription = "${payment.title}码原图",
                        modifier = Modifier.fillMaxWidth().height(screenHeight * 0.72f)
                            .testTag(if (imageLoaded) "support-image-loaded-${payment.name}" else "support-image-loading-${payment.name}"),
                        contentScale = ContentScale.Fit,
                        onSuccess = { imageLoaded = true },
                    )
                }
            }
        }
    }
}

@Composable
fun StartupAuthorSupportPrompt(eligible: Boolean, launchKey: Int, ready: Boolean) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) { AuthorSupportLaunchStore(context.applicationContext) }
    var lastCheckedKey by rememberSaveable { mutableIntStateOf(-1) }
    var recordedKey by rememberSaveable { mutableIntStateOf(-1) }
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(eligible, ready, launchKey) {
        if (!eligible) {
            visible = false
            return@LaunchedEffect
        }
        if (!ready || lastCheckedKey == launchKey) return@LaunchedEffect
        lastCheckedKey = launchKey
        visible = withContext(Dispatchers.IO) { store.isDue(System.currentTimeMillis()) }
    }
    if (visible && eligible && ready) {
        AuthorSupportDialog(
            onDismiss = { visible = false },
            onPresented = {
                if (recordedKey != launchKey) {
                    store.recordShown(System.currentTimeMillis())
                    recordedKey = launchKey
                }
            },
        )
    }
}
