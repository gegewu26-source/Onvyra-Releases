package com.constx.onvyra.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ui.toast.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import com.constx.onvyra.R
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.remote.RemoteFileStore
import com.constx.onvyra.ui.components.ai.ExtensionEmptyState
import com.constx.onvyra.ui.components.ui.AutoAIIcon
import com.constx.onvyra.ui.components.ui.ItemAction
import com.constx.onvyra.ui.components.ui.ItemActionMenu
import com.constx.onvyra.ui.components.ui.PagerTab
import com.constx.onvyra.ui.components.ui.longPressReorder
import com.constx.onvyra.ui.components.ui.s3ConnectionItems
import com.constx.onvyra.ui.context.LocalToaster
import com.constx.onvyra.ui.pages.setting.components.MediaGenerationProviderConfigure
import com.constx.onvyra.ui.pages.setting.components.ProviderEditSheet
import com.constx.onvyra.ui.pages.setting.components.ProviderTypeSheet
import com.constx.onvyra.ui.pages.setting.components.label
import com.constx.onvyra.ui.pages.setting.components.typeName
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class MediaTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    PROVIDERS(HugeIcons.Image02, R.string.setting_media_page_providers),
    STORAGE(HugeIcons.Database02, R.string.setting_media_page_asset_storage);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun SettingMediaPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingProvider by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }
    val pagerState = rememberPagerState { MediaTab.entries.size }

    Scaffold(
        topBar = {
            SettingsHomeTopBar(
                title = stringResource(R.string.setting_page_media),
                actions = {
                    if (MediaTab.entries[pagerState.targetPage] == MediaTab.PROVIDERS) {
                        AddMediaProviderButton {
                            vm.updateSettings { latest ->
                                latest.copy(
                                    mediaGenerationProviders = listOf(it) + latest.mediaGenerationProviders
                                )
                            }
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            CompactMediaSpeechTabs(
                pagerState = pagerState,
                tabs = MediaTab.entries,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (MediaTab.entries[page]) {
                    MediaTab.PROVIDERS -> MediaProviderList(
                        settings = settings,
                        onUpdateSettings = vm::updateSettings,
                        onEdit = { editingProvider = it },
                    )

                    MediaTab.STORAGE -> MediaUploadS3Tab(
                        settings = settings,
                        onUpdateSettings = vm::updateSettings,
                    )
                }
            }
        }
    }

    editingProvider?.let { provider ->
        MediaProviderSheet(
            title = stringResource(R.string.setting_media_page_edit_provider),
            initial = provider,
            confirmText = stringResource(R.string.chat_page_save),
            onConfirm = { edited ->
                vm.updateSettings { latest ->
                    latest.copy(
                        mediaGenerationProviders = latest.mediaGenerationProviders.map {
                            if (it.id == provider.id) edited else it
                        }
                    )
                }
            },
            onDismiss = { editingProvider = null }
        )
    }
}

@Composable
private fun MediaProviderList(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onEdit: (MediaGenerationProviderSetting) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newProviders = settings.mediaGenerationProviders.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        onUpdateSettings { it.copy(mediaGenerationProviders = newProviders) }
    }
    var deleteTarget by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.compact_media_image_services),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settings.mediaGenerationProviders.isEmpty()) {
            ExtensionEmptyState(
                message = stringResource(R.string.setting_media_page_empty),
                icon = HugeIcons.Image02,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                state = lazyListState
            ) {
                items(settings.mediaGenerationProviders, key = { it.id }) { provider ->
                    ReorderableItem(
                        state = reorderableState,
                        key = provider.id
                    ) { isDragging ->
                        MediaProviderItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(longPressReorder(isDragging)),
                            provider = provider,
                            onEdit = { onEdit(provider) },
                            onDelete = { deleteTarget = provider }
                        )
                    }
                }
            }
        }

    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { target ->
                onUpdateSettings { latest ->
                    latest.copy(
                        mediaGenerationProviders = latest.mediaGenerationProviders.filter { it.id != target.id }
                    )
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, deleteTarget?.name.orEmpty()))
    }
}

@Composable
private fun MediaProviderItem(
    provider: MediaGenerationProviderSetting,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val name = provider.name.ifEmpty { provider.typeName }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)
                .padding(start = 12.dp, top = 14.dp, end = 4.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = name,
                modifier = Modifier.size(38.dp),
                color = MaterialTheme.colorScheme.background,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = provider.typeName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MediaKind.entries.forEach { kind ->
                        val count = provider.models.count { it.kind == kind }
                        if (count > 0) {
                            CompactMediaSpeechBadge(text = "${kind.label} $count")
                        }
                    }
                }
            }
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = onDelete,
                    ),
                )
            )
        }
    }
}

// 新建时先选类型，再进配置面板
@Composable
private fun AddMediaProviderButton(onAdd: (MediaGenerationProviderSetting) -> Unit) {
    var showTypeSheet by remember { mutableStateOf(false) }
    var newProvider by remember { mutableStateOf<MediaGenerationProviderSetting?>(null) }
    val factories = remember {
        listOf<() -> MediaGenerationProviderSetting>(
            { MediaGenerationProviderSetting.OpenAI() },
            { MediaGenerationProviderSetting.Aliyun() },
            { MediaGenerationProviderSetting.Volcengine() },
            { MediaGenerationProviderSetting.MiniMax() },
            { MediaGenerationProviderSetting.OpenRouter() },
        )
    }

    IconButton(
        onClick = { showTypeSheet = true }
    ) {
        Icon(HugeIcons.Add01, stringResource(R.string.setting_media_page_add_provider))
    }

    if (showTypeSheet) {
        ProviderTypeSheet(
            title = stringResource(R.string.setting_media_page_add_provider),
            types = factories,
            typeName = { create -> remember(create) { create() }.typeName },
            onSelect = { create ->
                showTypeSheet = false
                newProvider = create()
            },
            onDismiss = { showTypeSheet = false },
        )
    }

    newProvider?.let { provider ->
        MediaProviderSheet(
            title = stringResource(R.string.setting_media_page_add_provider),
            initial = provider,
            confirmText = stringResource(R.string.setting_tts_page_add),
            onConfirm = onAdd,
            onDismiss = { newProvider = null }
        )
    }
}

@Composable
private fun MediaProviderSheet(
    title: String,
    initial: MediaGenerationProviderSetting,
    confirmText: String,
    onConfirm: (MediaGenerationProviderSetting) -> Unit,
    onDismiss: () -> Unit
) {
    ProviderEditSheet(
        title = title,
        initial = initial,
        confirmText = confirmText,
        onConfirm = { provider ->
            // 没填模型 ID 的行不保存
            onConfirm(provider.copyProvider(models = provider.models.filter { it.modelId.isNotBlank() }))
        },
        onDismiss = onDismiss,
    ) { provider, onValueChange, modifier ->
        MediaGenerationProviderConfigure(
            setting = provider,
            onValueChange = onValueChange,
            modifier = modifier
        )
    }
}

@Composable
private fun MediaUploadS3Tab(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    modifier: Modifier = Modifier,
    remoteFileStore: RemoteFileStore = koinInject(),
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }

    // 输入框以本地状态为准：设置要经过存储再回流，直接绑定的话打字快了会被旧值覆盖
    var config by remember { mutableStateOf(settings.uploadS3Config) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_media_page_s3_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        SettingsDetailGroup {
            s3ConnectionItems(
                config = config,
                onUpdate = {
                    config = it
                    onUpdateSettings { latest -> latest.copy(uploadS3Config = it) }
                }
            )
        }

        SettingsDetailGroup {
            item(
                onClick = if (!testing) {
                    {
                        scope.launch {
                            testing = true
                            remoteFileStore.testConnection()
                                .onSuccess {
                                    toaster.show(
                                        context.getString(R.string.backup_page_connection_success),
                                        type = ToastType.Success
                                    )
                                }
                                .onFailure {
                                    toaster.show(
                                        context.getString(R.string.backup_page_connection_failed, it.message ?: ""),
                                        type = ToastType.Error
                                    )
                                }
                            testing = false
                        }
                    }
                } else null,
                headlineContent = { Text(stringResource(R.string.backup_page_test_connection)) },
                leadingContent = {
                    if (testing) {
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else {
                        SettingsDetailIcon(HugeIcons.Connect)
                    }
                },
            )
        }
    }
}
