package com.constx.onvyra.ui.pages.netdisk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.constx.onvyra.data.netdisk.NetDiskDownload
import com.constx.onvyra.data.netdisk.NetDiskFile
import com.constx.onvyra.data.netdisk.NetDiskPlatform
import com.constx.onvyra.data.netdisk.NetDiskRepository
import com.constx.onvyra.data.netdisk.NetDiskAccountRepository
import com.constx.onvyra.data.netdisk.download.NetDiskDownloadManager
import com.constx.onvyra.data.netdisk.download.NetDiskDestinationStore
import com.constx.onvyra.ui.components.ui.CardGroup
import com.constx.onvyra.ui.components.ui.cardGroupItemShape
import com.constx.onvyra.ui.context.LocalToaster
import com.constx.onvyra.ui.context.LocalNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ClipboardPaste
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.LocalTime

/** One workspace, three destinations; switching tabs never replaces its ViewModel. */
enum class NetDiskTab(val label: String) { PARSE("解析"), CLOUD("网盘"), TRANSFER("传输") }

@Composable
fun NetDiskPage(initialTab: NetDiskTab = NetDiskTab.PARSE) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val factory = remember(appContext) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NetDiskViewModel(NetDiskRepository(appContext), NetDiskDownloadManager.get(appContext)) as T
        }
    }
    val vm: NetDiskViewModel = viewModel(factory = factory)
    val state = vm.state
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val navigator = LocalNavController.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val parseScroll = rememberLazyListState()
    var history by remember { mutableStateOf(false) }
    var credentialPlatform by remember { mutableStateOf<NetDiskPlatform?>(null) }
    var forceOfficialLogin by remember { mutableStateOf(false) }
    var manualCredentialPlatform by remember { mutableStateOf<NetDiskPlatform?>(null) }
    var accountPlatform by rememberSaveable { mutableStateOf<NetDiskPlatform?>(null) }
    var webPlatform by remember { mutableStateOf<NetDiskPlatform?>(null) }
    var browseGeneration by remember { mutableLongStateOf(0L) }
    val accountScope = rememberCoroutineScope()
    fun showLogin(platform: NetDiskPlatform) {
        forceOfficialLogin = false
        credentialPlatform = platform
    }
    fun browseAccount(platform: NetDiskPlatform) {
        val ticket = ++browseGeneration
        tab = NetDiskTab.CLOUD
        accountScope.launch {
            val configured = try { withContext(Dispatchers.IO) { vm.repository.credential(platform).isNotBlank() } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            if (ticket != browseGeneration || tab != NetDiskTab.CLOUD) return@launch
            if (!configured) { accountPlatform = null; showLogin(platform) }
            else if (NetDiskAccountRepository.isNativeSupported(platform)) accountPlatform = platform
            else { accountPlatform = null; webPlatform = platform }
        }
    }
    var pendingDownload by remember { mutableStateOf<NetDiskDownload?>(null) }
    var notificationAsked by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toaster.show("通知未开启，可在传输页查看进度")
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingDownload
        pendingDownload = null
        if (granted && pending != null) vm.download(pending)
        else if (!granted) toaster.show("未获得存储权限，下载任务尚未创建")
    }
    fun requestDownload(link: NetDiskDownload) {
        if (NetDiskDestinationStore.get(appContext).current.value.treeUri == null && Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = link
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            if (Build.VERSION.SDK_INT >= 33 && !notificationAsked && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationAsked = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            vm.download(link)
        }
    }
    LaunchedEffect(vm.notice) { vm.notice?.let { toaster.show(it); vm.clearNotice() } }
    LaunchedEffect(vm.downloadNavigationId) {
        if (vm.downloadNavigationId != null) {
            vm.consumeDownloadNavigation()
            tab = NetDiskTab.TRANSFER
        }
    }
    BackHandler(enabled = credentialPlatform == null && webPlatform == null && !history &&
        (tab != NetDiskTab.PARSE || state.folders.size > 1)) {
        if (tab == NetDiskTab.PARSE && state.folders.size > 1) vm.folder(state.folders.lastIndex - 1)
        else if (tab == NetDiskTab.CLOUD && accountPlatform != null) accountPlatform = null
        else tab = NetDiskTab.PARSE
    }
    BackHandler(enabled = credentialPlatform != null && manualCredentialPlatform == null) {
        credentialPlatform = null; forceOfficialLogin = false
    }
    NetDiskWorkspaceTheme {
        val parseLanding = tab == NetDiskTab.PARSE && credentialPlatform == null
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(credentialPlatform?.let { "登录${it.label}" } ?: when (tab) {
                        NetDiskTab.PARSE -> "分享解析"
                        NetDiskTab.CLOUD -> accountPlatform?.label ?: "网盘列表"
                        NetDiskTab.TRANSFER -> "传输"
                    }, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (credentialPlatform != null) { credentialPlatform = null; forceOfficialLogin = false }
                            else if (tab == NetDiskTab.CLOUD && accountPlatform != null) accountPlatform = null
                            else navigator.popBackStack()
                        }, modifier = if (parseLanding) Modifier.padding(start = 8.dp).size(40.dp)
                            .border(0.7.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape) else Modifier) {
                            Icon(HugeIcons.ArrowLeft01, "返回")
                        }
                    },
                    actions = {
                        if (credentialPlatform == null && tab == NetDiskTab.CLOUD && accountPlatform != null) IconButton(onClick = {
                            accountPlatform?.let(::showLogin)
                        }) { Icon(HugeIcons.Settings03, "账号与登录") }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor =
                        if (parseLanding) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = { NetDiskBottomBar(tab, onSelect = {
                browseGeneration++
                credentialPlatform = null
                forceOfficialLogin = false
                if (it == NetDiskTab.CLOUD && tab == NetDiskTab.CLOUD) accountPlatform = null
                tab = it
            }) },
            containerColor = if (parseLanding) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
            modifier = Modifier.imePadding().testTag("netdisk-page"),
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                val login = credentialPlatform
                if (login != null && NetDiskLoginPolicy.hasEmbeddedLogin(login) && !forceOfficialLogin) key(login) {
                    NetDiskNativeLoginContent(login, onCredential = { value ->
                        vm.saveCredential(login, value)
                        credentialPlatform = null
                        forceOfficialLogin = false
                        browseAccount(login)
                    }, onAdvanced = { manualCredentialPlatform = login }, onOfficial = { forceOfficialLogin = true })
                } else when (tab) {
                    NetDiskTab.PARSE -> NetDiskParseContent(vm, parseScroll,
                        onHistory = { history = true }, onShowCloud = { tab = NetDiskTab.CLOUD },
                        onCredential = ::showLogin)
                    NetDiskTab.CLOUD -> {
                        val platform = accountPlatform
                        if (platform == null) NetDiskCloudsContent(vm, onSelect = ::browseAccount,
                            onManage = { browseGeneration++; showLogin(it) })
                        else NetDiskAccountContent(platform, vm.repository, vm.credentialRevision,
                            onLogin = { showLogin(platform) }, onWeb = { webPlatform = platform },
                            onDownload = ::requestDownload)
                    }
                    NetDiskTab.TRANSFER -> NetDiskTransfersContent()
                }
            }
        }
        if (history) NetDiskHistoryDialog(vm, onDismiss = { history = false })
        credentialPlatform?.takeIf { !NetDiskLoginPolicy.hasEmbeddedLogin(it) || forceOfficialLogin }?.let { platform ->
            NetDiskLoginDialog(platform, onDismiss = { credentialPlatform = null; forceOfficialLogin = false },
                onAdvanced = { manualCredentialPlatform = platform },
                onCredential = { value -> accountScope.launch {
                    try {
                        vm.saveCredential(platform, value)
                        credentialPlatform = null; forceOfficialLogin = false
                        browseAccount(platform)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { toaster.show("登录信息保存失败，请重试") }
                } })
        }
        manualCredentialPlatform?.let { platform ->
            NetDiskCredentialDialog(vm, platform, onDismiss = { manualCredentialPlatform = null },
                onSaved = { if (it) { credentialPlatform = null; forceOfficialLogin = false; browseAccount(platform) } })
        }
        webPlatform?.let { platform ->
            NetDiskLoginDialog(platform, onDismiss = { webPlatform = null }, accountMode = true,
                onCredential = { value -> accountScope.launch {
                    try { vm.saveCredential(platform, value) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { toaster.show("登录信息保存失败，请重试") }
                } })
        }
        state.download?.let { link ->
            NetDiskDownloadDialog(link, onDismiss = vm::dismissDownload, onDownload = { requestDownload(link) },
                onCopy = { clipboard.setText(AnnotatedString(link.url)); toaster.show("已复制直链") })
        }
        state.transferFile?.let { file ->
            AlertDialog(
                onDismissRequest = vm::dismissTransfer,
                title = { Text("需要先转存到本人网盘") },
                text = { Text("此文件需要转存后才能取下载链接。继续会在你的${state.session?.share?.platform?.label.orEmpty()}中新建 Onvyra 临时转存目录，保存“${file.name}”，可能占用网盘容量。文件会保留，不会自动删除；之后可到官方网盘管理。") },
                confirmButton = { TextButton(onClick = vm::confirmTransfer) { Text("转存并获取直链") } },
                dismissButton = { TextButton(onClick = vm::dismissTransfer) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun NetDiskWorkspaceTheme(content: @Composable () -> Unit) {
    val current = MaterialTheme.colorScheme
    val light = current.background.luminance() > 0.5f
    val scheme = current.copy(
        primary = if (light) Color(0xFF0783ED) else Color(0xFF8AC9FF),
        onPrimary = if (light) Color.White else Color(0xFF003457),
        primaryContainer = if (light) Color(0xFFE6F3FF) else Color(0xFF143D5C),
        onPrimaryContainer = if (light) Color(0xFF0077DD) else Color(0xFFCFE8FF),
        background = if (light) Color(0xFFFCFCFC) else current.background,
        surface = if (light) Color.White else current.surface,
        surfaceContainerLow = if (light) Color.White else current.surfaceContainerLow,
        onSurfaceVariant = if (light) Color(0xFF757575) else current.onSurfaceVariant,
        outlineVariant = if (light) Color(0xFFE1E1E1) else current.outlineVariant,
    )
    MaterialExpressiveTheme(colorScheme = scheme, typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes, content = content)
}

@Composable
private fun NetDiskBottomBar(selected: NetDiskTab, onSelect: (NetDiskTab) -> Unit) {
    Column {
        HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp)
                .testTag("netdisk-bottom-nav")) {
            NetDiskTab.entries.forEach { tab ->
                val color = if (tab == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).fillMaxHeight()
                    .selectable(selected = tab == selected, role = Role.Tab, onClick = { onSelect(tab) })
                    .testTag("netdisk-tab-${tab.name.lowercase()}").padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    Icon(when (tab) {
                        NetDiskTab.PARSE -> HugeIcons.Link01
                        NetDiskTab.CLOUD -> HugeIcons.Folder01
                        NetDiskTab.TRANSFER -> HugeIcons.Download01
                    }, null, modifier = Modifier.size(24.dp), tint = color)
                    Text(tab.label, color = color, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                        fontWeight = if (tab == selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            }
        }
    }
}

@Composable
private fun NetDiskParseContent(vm: NetDiskViewModel, scroll: LazyListState,
    onHistory: () -> Unit, onShowCloud: () -> Unit, onCredential: (NetDiskPlatform) -> Unit) {
    val state = vm.state
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("netdisk-parse-content"), state = scroll,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("intro") { NetDiskParseHeader() }
        item("input") { NetDiskParseForm(vm, onHistory) }
            if (state.loading) item("loading") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.operation, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = vm::cancel) { Text("取消") }
                    }
                }
            }
            state.error?.let { message -> item("error") {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = vm::refresh, enabled = !state.loading) { Text("重试") }
                            TextButton(enabled = !state.loading, onClick = { val platform = state.session?.share?.platform ?: vm.detectedPlatform();
                                if (platform == null) onShowCloud() else onCredential(platform) }) { Text("账号与登录") }
                        }
                    }
                }
            } }
            if (state.session == null) item("platforms") {
                NetDiskSupportedCard(vm.repository.platforms, onShowCloud)
            }
            if (state.session != null) {
                item("directory") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(state.session.share.platform.label, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(state.folders.lastOrNull()?.title ?: state.session.title,
                                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (state.folders.size > 1) IconButton(onClick = { vm.folder(state.folders.lastIndex - 1) },
                            enabled = !state.loading) { Icon(HugeIcons.ArrowUp01, "返回上一级") }
                        IconButton(onClick = vm::refresh, enabled = !state.loading) { Icon(HugeIcons.Refresh01, "刷新目录") }
                    }
                }
                if (state.folders.size > 1) item("breadcrumbs") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically) {
                        state.folders.forEachIndexed { index, folder ->
                            if (index > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { vm.folder(index) }, enabled = !state.loading) {
                                Text(if (index == 0) "分享根目录" else folder.title, maxLines = 1)
                            }
                        }
                    }
                }
                if (state.files.isEmpty() && !state.loading) item("empty") {
                    Text("此文件夹暂无可显示的文件", Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(state.files, key = { index, file -> "$index-${file.id}" }) { index, file ->
                    NetDiskFileRow(file, index, state.files.size, state.loading,
                        state.busyFileId == file.id, onClick = { vm.openDirectory(file) })
                }
                if (state.nextCursor != null) item("more") {
                    OutlinedButton(onClick = vm::more, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
                }
            }
    }
}

@Composable
private fun NetDiskParseHeader() {
    val date = LocalDate.now()
    val greeting = when (LocalTime.now().hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        else -> "晚上好"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(date.dayOfMonth.toString(), fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 46.sp)
                Text("/ ${date.monthValue}月", Modifier.padding(bottom = 5.dp),
                    fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(greeting, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium)
        }
        VerticalDivider(Modifier.height(30.dp), thickness = 0.7.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("Onvyra", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("netdisk-brand"))
            Text("多云盘管理工具", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NetDiskParseForm(vm: NetDiskViewModel, onHistory: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val directoryStore = remember(context.applicationContext) { NetDiskDestinationStore.get(context.applicationContext) }
    val destination by directoryStore.current.collectAsState()
    var directoryMenu by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { directoryStore.select(uri) }
                toaster.show("已记住下载目录")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { toaster.show((error as? com.constx.onvyra.data.netdisk.NetDiskException)?.message ?: "目录选择失败，请重试") }
        }
    }
    Surface(shape = RoundedCornerShape(25.dp), color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.7.dp, border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Surface(shape = RoundedCornerShape(50), border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.height(38.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(HugeIcons.Link01, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Text("链接解析", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                }
                FilledTonalButton(onClick = onHistory, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    modifier = Modifier.height(38.dp).testTag("netdisk-history")) {
                    Icon(HugeIcons.Clock01, null, Modifier.size(20.dp))
                    Text("历史", Modifier.padding(start = 7.dp), fontWeight = FontWeight.SemiBold)
                }
            }
            NetDiskParseField(value = vm.input, onValueChange = { vm.input = it },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 76.dp).testTag("netdisk-share-input"),
                placeholder = "粘贴分享链接或包含链接的文本", minLines = 2, maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
            HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.7.dp)
            Text("提取码（自动识别，可手动修改）", Modifier.padding(start = 8.dp, top = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NetDiskParseField(value = vm.password, onValueChange = { vm.password = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("netdisk-share-code"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
            vm.detectedPlatform()?.let { platform ->
                Text(platform.label, Modifier.padding(start = 16.dp, top = 4.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    val text = clipboard.getText()?.text
                    if (text.isNullOrBlank()) toaster.show("剪贴板没有可粘贴的内容") else vm.input = text
                }, modifier = Modifier.weight(0.95f).height(42.dp), shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                    Icon(HugeIcons.ClipboardPaste, "粘贴链接", Modifier.size(21.dp))
                    Text("粘贴", Modifier.padding(start = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Box {
                    FilledTonalIconButton(onClick = { directoryMenu = true },
                        modifier = Modifier.width(62.dp).height(42.dp).testTag("netdisk-directory"),
                        shape = RoundedCornerShape(50),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f),
                            contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Icon(HugeIcons.Folder01, "选择下载目录", Modifier.size(24.dp))
                    }
                    DropdownMenu(expanded = directoryMenu, onDismissRequest = { directoryMenu = false }) {
                        DropdownMenuItem(text = { Text("选择／更换下载目录") }, onClick = {
                            directoryMenu = false; folderPicker.launch(destination.treeUri?.let(Uri::parse))
                        })
                        DropdownMenuItem(text = { Text("使用默认下载目录") }, onClick = {
                            directoryMenu = false
                            scope.launch {
                                try { withContext(Dispatchers.IO) { directoryStore.reset() }; toaster.show("已恢复默认下载目录") }
                                catch (_: Exception) { toaster.show("目录设置保存失败，请重试") }
                            }
                        })
                    }
                }
                Button(onClick = vm::parse, enabled = !vm.state.loading && vm.input.isNotBlank(),
                    modifier = Modifier.weight(1.3f).height(42.dp).testTag("netdisk-parse"), shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)),
                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(if (vm.state.loading) "解析中…" else "开始解析", fontSize = 14.sp, maxLines = 1, fontWeight = FontWeight.Bold)
                }
            }
            Text("下载目录：${destination.label}", Modifier.fillMaxWidth().padding(top = 10.dp).testTag("netdisk-directory-label"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NetDiskParseField(value: String, onValueChange: (String) -> Unit, modifier: Modifier,
    placeholder: String = "", singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = 1,
    keyboardOptions: KeyboardOptions) {
    BasicTextField(value = value, onValueChange = onValueChange,
        modifier = modifier.border(0.7.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), singleLine = singleLine,
        minLines = minLines, maxLines = maxLines, keyboardOptions = keyboardOptions,
        decorationBox = { field ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                field()
            }
        })
}

@Composable
private fun NetDiskSupportedCard(platforms: List<NetDiskPlatform>, onShowCloud: () -> Unit) {
    Surface(onClick = onShowCloud, modifier = Modifier.testTag("netdisk-accounts"),
        shape = RoundedCornerShape(25.dp), color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp, border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NetDiskIcon(Modifier.padding(top = 1.dp).size(32.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("支持类型", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text(platforms.joinToString("、") { it.label } + "；支持分享链接、带提取码的分享文案。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp)
            }
        }
    }
}

@Composable
private fun NetDiskHistoryDialog(vm: NetDiskViewModel, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("解析历史") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("仅保留本次使用时输入的链接，退出后不保存。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (vm.history.isEmpty()) Text("暂无解析记录", Modifier.padding(vertical = 16.dp))
            else LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(vm.history) { _, entry ->
                    Surface(onClick = { vm.useHistory(entry); onDismiss() }, shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.platform.label, style = MaterialTheme.typography.titleSmall)
                            Text(entry.input, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { if (vm.history.isNotEmpty()) TextButton(onClick = vm::clearHistory) { Text("清空记录") } })
}

@Composable
private fun NetDiskFileRow(file: NetDiskFile, index: Int, count: Int, loading: Boolean,
    busy: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = !loading,
        modifier = Modifier.fillMaxWidth().testTag(if (file.isDirectory) "netdisk-folder" else "netdisk-file"),
        shape = cardGroupItemShape(index, count), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (file.isDirectory) HugeIcons.Folder01 else HugeIcons.File01, null,
                modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (file.isDirectory) "文件夹" else formatNetDiskSize(file.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(if (file.isDirectory) HugeIcons.ArrowRight01 else HugeIcons.Link01, null,
                modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NetDiskCredentialDialog(vm: NetDiskViewModel, platform: NetDiskPlatform, onDismiss: () -> Unit,
    onSaved: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var value by remember(platform) { mutableStateOf("") }
    var loading by remember(platform) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var webLogin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(platform) {
        try { value = withContext(Dispatchers.IO) { vm.repository.credential(platform) } }
        catch (_: Exception) { error = "无法读取本地凭据，请重新配置" }
        finally { loading = false }
    }
    fun save(text: String) {
        saving = true
        scope.launch {
            try { vm.saveCredential(platform, text.trim()); value = ""; onSaved(text.isNotBlank()); onDismiss() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = "保存失败，请重试" }
            finally { saving = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) { value = ""; onDismiss() } },
        title = { Text(platform.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("可以网页登录，也可以手动填写本人账号的 ${platform.credentialLabel}。导入的凭据加密保存在本机。",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { webLogin = true }, enabled = !loading && !saving,
                    modifier = Modifier.fillMaxWidth()) { Text("网页登录") }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedTextField(value = value, onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().testTag("netdisk-credential-input"),
                    label = { Text(platform.credentialLabel) }, minLines = 3, maxLines = 5,
                    enabled = !loading && !saving, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
                TextButton(onClick = { clipboard.getText()?.text?.let { value = it } }, enabled = !loading && !saving) {
                    Icon(HugeIcons.ClipboardPaste, null, modifier = Modifier.size(18.dp))
                    Text("粘贴凭据", Modifier.padding(start = 6.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { save(value) }, enabled = !loading && !saving) { Text("保存") } },
        dismissButton = {
            Row {
                if (value.isNotBlank()) TextButton(onClick = { save("") }, enabled = !loading && !saving) { Text("清除") }
                TextButton(onClick = { value = ""; onDismiss() }, enabled = !saving) { Text("取消") }
            }
        })
    if (webLogin) NetDiskLoginDialog(platform, onDismiss = { webLogin = false }, onCredential = {
        value = it
        webLogin = false
        save(it)
    })
}

@Composable
internal fun NetDiskDownloadDialog(link: NetDiskDownload, onDismiss: () -> Unit,
    onDownload: () -> Unit, onCopy: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("下载链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(link.fileName, style = MaterialTheme.typography.titleSmall)
                Text(link.url, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                link.note?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (link.headers.keys.any { it.equals("Cookie", true) || it.equals("Authorization", true) }) {
                    Text("此链接下载时需要登录信息，使用下方“高速下载”可自动携带。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Icon(HugeIcons.Copy01, null, Modifier.size(18.dp)); Text("复制直链", Modifier.padding(start = 8.dp))
                }
            }
        }, confirmButton = { FilledTonalButton(onClick = onDownload) { Text("高速下载") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

private fun formatNetDiskSize(size: Long): String {
    if (size <= 0L) return "大小未知"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var amount = size.toDouble()
    var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
    return "${DecimalFormat("0.##").format(amount)} ${units[unit]}"
}
