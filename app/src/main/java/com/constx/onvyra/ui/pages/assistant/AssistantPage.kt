package com.constx.onvyra.ui.pages.assistant

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constx.onvyra.R
import com.constx.onvyra.Screen
import com.constx.onvyra.data.datastore.DEFAULT_ASSISTANTS_IDS
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.Avatar
import com.constx.onvyra.data.model.MemoryFile
import com.constx.onvyra.ui.components.nav.BackButton
import com.constx.onvyra.ui.components.ui.ItemAction
import com.constx.onvyra.ui.components.ui.ItemActionMenu
import com.constx.onvyra.ui.components.ui.Tag
import com.constx.onvyra.ui.components.ui.TagType
import com.constx.onvyra.ui.components.ui.UIAvatar
import com.constx.onvyra.ui.components.ui.longPressReorder
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.hooks.EditState
import com.constx.onvyra.ui.hooks.EditStateContent
import com.constx.onvyra.ui.hooks.heroAnimation
import com.constx.onvyra.ui.hooks.useEditState
import com.constx.onvyra.ui.pages.assistant.detail.AssistantImporter
import com.constx.onvyra.ui.pages.setting.SettingsCompactTopBar
import com.constx.onvyra.ui.pages.setting.SettingsCompactSectionTitle
import com.constx.onvyra.ui.pages.setting.SettingsCompactCardColor
import com.constx.onvyra.ui.pages.setting.SettingsCompactPageColor
import com.constx.onvyra.ui.theme.CustomColors
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

@Composable
fun AssistantPage(vm: AssistantVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val createState = useEditState<Assistant> {
        vm.addAssistant(it)
    }
    val navController = LocalNavController.current

    // 搜索关键词状态
    var searchQuery by remember { mutableStateOf("") }
    // 标签过滤状态
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    // 待删除的助手
    var deleteTarget by remember { mutableStateOf<Assistant?>(null) }
    // 待复制的助手(仅在有独立记忆时需要询问是否一并复制)
    var cloneTarget by remember { mutableStateOf<Assistant?>(null) }
    var cloneWithMemories by remember { mutableStateOf(false) }

    // 根据搜索关键词和选中的标签过滤助手
    val filteredAssistants = remember(settings.assistants, selectedTagIds, searchQuery) {
        settings.assistants.filter { assistant ->
            val matchesSearch = searchQuery.isBlank() ||
                assistant.name.contains(searchQuery, ignoreCase = true)
            val matchesTags = selectedTagIds.isEmpty() ||
                assistant.tags.any { tagId -> tagId in selectedTagIds }
            matchesSearch && matchesTags
        }
    }

    Scaffold(
        topBar = {
            SettingsCompactTopBar(
                title = stringResource(R.string.assistant_page_title),
                actions = {
                    IconButton(onClick = { createState.open(Assistant()) }) {
                        Icon(HugeIcons.Add01, stringResource(R.string.assistant_page_add))
                    }
                },
            )
        },
        containerColor = SettingsCompactPageColor(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(top = 8.dp)
                .consumeWindowInsets(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val lazyListState = rememberLazyListState()
            val isFiltering = selectedTagIds.isNotEmpty() || searchQuery.isNotBlank()
            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                if (!isFiltering) {
                    val newAssistants = settings.assistants.toMutableList().apply {
                        add(to.index, removeAt(from.index))
                    }
                    vm.updateSettings { it.copy(assistants = newAssistants) }
                }
            }

            SettingsCompactSectionTitle(
                stringResource(R.string.settings_compact_my_assistants),
                Modifier.padding(horizontal = 20.dp),
            )

            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .testTag("assistant-search-input"),
                placeholder = { Text(stringResource(R.string.assistant_page_search_placeholder)) },
                leadingIcon = {
                    Icon(HugeIcons.Search01, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SettingsCompactCardColor(),
                    unfocusedContainerColor = SettingsCompactCardColor(),
                    focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                ),
            )

            // 标签过滤器
            AssistantTagsFilterRow(
                settings = settings,
                vm = vm,
                selectedTagIds = selectedTagIds,
                onUpdateSelectedTagIds = { ids ->
                    selectedTagIds = ids
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                state = lazyListState,
            ) {
                itemsIndexed(filteredAssistants, key = { _, assistant -> assistant.id }) { index, assistant ->
                    ReorderableItem(
                        state = reorderableState,
                        key = assistant.id,
                    ) { isDragging ->
                        val memories by vm.getMemories(assistant).collectAsStateWithLifecycle(
                            initialValue = emptyList(),
                        )
                        AssistantItem(
                            assistant = assistant,
                            settings = settings,
                            memories = memories,
                            onEdit = {
                                navController.navigate(Screen.AssistantDetail(id = assistant.id.toString()))
                            },
                            onCopy = {
                                // 全局记忆是共享的, 复制后的助手本来就能读到, 无需询问
                                if (!assistant.useGlobalMemory && memories.isNotEmpty()) {
                                    cloneWithMemories = false
                                    cloneTarget = assistant
                                } else {
                                    vm.copyAssistant(assistant)
                                }
                            },
                            onDelete = {
                                deleteTarget = assistant
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .compactGroupedRow(index, filteredAssistants.size)
                                .testTag("assistant-row-${assistant.id}")
                                .then(longPressReorder(isDragging, enabled = !isFiltering))
                        )
                    }
                }
            }
        }
    }

    AssistantCreationSheet(createState)

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.assistant_page_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.removeAssistant(it) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.assistant_page_delete_dialog_text))
    }

    RikkaConfirmDialog(
        show = cloneTarget != null,
        title = stringResource(R.string.assistant_page_clone),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            cloneTarget?.let { vm.copyAssistant(it, copyMemories = cloneWithMemories) }
            cloneTarget = null
        },
        onDismiss = { cloneTarget = null },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = cloneWithMemories,
                    role = Role.Checkbox,
                    onValueChange = { cloneWithMemories = it },
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = cloneWithMemories, onCheckedChange = null)
            Text(stringResource(R.string.assistant_page_clone_with_memories))
        }
    }
}

@Composable
private fun AssistantTagsFilterRow(
    settings: Settings,
    vm: AssistantVM,
    selectedTagIds: Set<Uuid>,
    onUpdateSelectedTagIds: (Set<Uuid>) -> Unit
) {
    if (settings.assistantTags.isNotEmpty()) {
        val tagsListState = rememberLazyListState()
        val tagsReorderableState = rememberReorderableLazyListState(tagsListState) { from, to ->
            val newTags = settings.assistantTags.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            vm.updateSettings { it.copy(assistantTags = newTags) }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
            state = tagsListState
        ) {
            items(items = settings.assistantTags, key = { tag -> tag.id }) { tag ->
                ReorderableItem(
                    state = tagsReorderableState, key = tag.id
                ) { isDragging ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            onClick = {
                                onUpdateSelectedTagIds(
                                    if (tag.id in selectedTagIds) {
                                        selectedTagIds - tag.id
                                    } else {
                                        selectedTagIds + tag.id
                                    }
                                )
                            },
                            label = {
                                Text(tag.name)
                            },
                            selected = tag.id in selectedTagIds,
                            leadingIcon = if (tag.id in selectedTagIds) {
                                {
                                    Icon(
                                        imageVector = HugeIcons.Tick01,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else null,
                            shape = CircleShape,
                            modifier = longPressReorder(isDragging)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantCreationSheet(
    state: EditState<Assistant>,
) {
    state.EditStateContent { assistant, update ->
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
            dragHandle = {},
            sheetGesturesEnabled = false
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = assistant.name,
                    onValueChange = {
                        update(
                            assistant.copy(
                                name = it
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.assistant_page_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                AssistantImporter(
                    onUpdate = {
                        update(it)
                        state.confirm()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = {
                            state.dismiss()
                        }) {
                        Text(stringResource(R.string.assistant_page_cancel))
                    }
                    Button(
                        onClick = {
                            state.confirm()
                        }) {
                        Text(stringResource(R.string.assistant_page_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    settings: Settings,
    modifier: Modifier = Modifier,
    memories: List<MemoryFile>,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onEdit,
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (assistant.avatar is Avatar.Dummy) {
                Icon(
                    HugeIcons.AiMagic,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                UIAvatar(
                    name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    value = assistant.avatar,
                    modifier = Modifier.size(32.dp).heroAnimation("assistant_${assistant.id}"),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {

                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (assistant.enableMemory || assistant.tags.isNotEmpty()) Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (assistant.enableMemory) {
                        Tag(type = TagType.SUCCESS) {
                            Text(stringResource(R.string.assistant_page_memory_count, memories.size))
                        }
                    }

                    if (assistant.tags.isNotEmpty()) {
                        assistant.tags.take(2).fastForEach { tagId ->
                            val tag = settings.assistantTags.find { it.id == tagId }
                                ?: return@fastForEach
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text(
                                    text = tag.name,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        if (assistant.tags.size > 2) {
                            Text(
                                text = "+${assistant.tags.size - 2}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.assistant_page_clone),
                        icon = HugeIcons.Copy01,
                        onClick = onCopy,
                    ),
                    ItemAction(
                        text = stringResource(R.string.assistant_page_delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        enabled = assistant.id !in DEFAULT_ASSISTANTS_IDS,
                        onClick = onDelete,
                    ),
                )
            )
        }
    }
}
