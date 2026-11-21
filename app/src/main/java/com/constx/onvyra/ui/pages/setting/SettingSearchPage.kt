package com.constx.onvyra.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Search01
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.constx.onvyra.R
import com.constx.onvyra.Screen
import com.constx.onvyra.ui.components.nav.BackButton
import com.constx.onvyra.ui.components.ui.AutoAIIcon
import com.constx.onvyra.ui.components.ui.ItemAction
import com.constx.onvyra.ui.components.ui.ItemActionMenu
import com.constx.onvyra.ui.components.ui.Tag
import com.constx.onvyra.ui.components.ui.TagType
import com.constx.onvyra.ui.components.ui.longPressReorder
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.pages.assistant.compactGroupedRow
import com.constx.onvyra.ui.theme.CustomColors
import com.constx.onvyra.utils.plus
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import me.rerere.ui.components.FormItem
import me.rerere.ui.components.OutlinedNumberInput
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.reflect.full.primaryConstructor

@Composable
fun SettingSearchPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val nav = LocalNavController.current
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SearchServiceOptions?>(null) }

    Scaffold(
        topBar = {
            SettingsCompactTopBar(
                title = stringResource(R.string.setting_page_search_title),
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(HugeIcons.Add01, stringResource(R.string.setting_page_search_add_provider))
                    }
                },
            )
        },
        containerColor = SettingsCompactPageColor(),
    ) {
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            // The first lazy item is the section title, not a provider.
            val fromIndex = from.index - 1
            val toIndex = to.index - 1

            if (fromIndex >= 0 && toIndex >= 0 && fromIndex < settings.searchServices.size && toIndex < settings.searchServices.size) {
                val newServices = settings.searchServices.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
                vm.updateSettings {
                    it.copy(searchServices = newServices)
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = it + PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            state = lazyListState
        ) {
            item("providers_title") {
                SettingsCompactSectionTitle(stringResource(R.string.settings_compact_search_providers))
            }
            itemsIndexed(settings.searchServices, key = { _, service -> service.id }) { index, service ->
                ReorderableItem(
                    state = reorderableState,
                    key = service.id
                ) { isDragging ->
                    SearchProviderCard(
                        service = service,
                        onEdit = {
                            nav.navigate(Screen.SettingSearchDetail(service.id.toString()))
                        },
                        onDelete = {
                            deleteTarget = service
                        },
                        canDelete = settings.searchServices.size > 1,
                        modifier = Modifier
                            .animateItem()
                            .compactGroupedRow(index, settings.searchServices.size)
                            .testTag("search-provider-${service.id}")
                            .then(longPressReorder(isDragging))
                    )
                }
            }

            item("common_options") {
                SettingsCompactSectionTitle(
                    stringResource(R.string.setting_page_search_common_options),
                    Modifier.padding(top = 16.dp),
                )
                CommonOptions(
                    settings = settings,
                    onUpdate = { options ->
                        vm.updateSettings {
                            it.copy(searchCommonOptions = options)
                        }
                    }
                )
            }
        }
    }

    if (showAddDialog) {
        AddProviderDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { options ->
                showAddDialog = false
                vm.updateSettings {
                    it.copy(
                        searchServices = listOf(options) + it.searchServices
                    )
                }
                scope.launch {
                    lazyListState.animateScrollToItem(0)
                }
            }
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { target ->
                vm.updateSettings { latest ->
                    latest.copy(searchServices = latest.searchServices.filter { it.id != target.id })
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, deleteTarget?.displayName.orEmpty()))
    }
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onConfirm: (SearchServiceOptions) -> Unit
) {
    val availableTypes = remember {
        SearchServiceOptions.TYPES.filterKeys { it != SearchServiceOptions.LegacyPresetOptions::class }
    }
    var selectedType by remember {
        mutableStateOf(availableTypes.keys.first())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.setting_page_search_add_provider))
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(availableTypes.keys.toList()) { type ->
                    val name = availableTypes[type] ?: "Unknown"
                    val isSelected = selectedType == type
                    Card(
                        onClick = { selectedType = type },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AutoAIIcon(
                                name = name,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val instance = selectedType.primaryConstructor!!.callBy(mapOf())
                    onConfirm(instance)
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SearchProviderCard(
    service: SearchServiceOptions,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onEdit,
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AutoAIIcon(
                name = service.displayName,
                color = Color.Transparent,
                modifier = Modifier.size(32.dp)
            )

            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = service.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    CompactSearchAbilityTags(options = service)
                }
            }

            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        enabled = canDelete,
                        onClick = onDelete,
                    ),
                )
            )
        }
    }
}

@Composable
fun SearchAbilityTagLine(
    modifier: Modifier = Modifier,
    options: SearchServiceOptions
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Tag(
            type = TagType.DEFAULT,
        ) {
            Text(stringResource(R.string.search_ability_search))
        }
        if (SearchService.getService(options).scrapingParameters(options) != null) {
            Tag(
                type = TagType.DEFAULT,
            ) {
                Text(stringResource(R.string.search_ability_scrape))
            }
        }
    }
}

@Composable
private fun CommonOptions(
    settings: com.constx.onvyra.data.datastore.Settings,
    onUpdate: (SearchCommonOptions) -> Unit,
) {
    var commonOptions by remember(settings.searchCommonOptions) {
        mutableStateOf(settings.searchCommonOptions)
    }
    var resultSizeText by remember(commonOptions.resultSize) {
        mutableStateOf(commonOptions.resultSize.toString())
    }
    SettingsCompactGroup {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(HugeIcons.Search01, null, Modifier.size(24.dp))
            Text(
                stringResource(R.string.setting_page_search_result_size),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SettingsCompactSoftColor(),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            ) {
                BasicTextField(
                    value = resultSizeText,
                    onValueChange = { entered ->
                        if (entered.isEmpty() || entered.matches(Regex("[+-]?[0-9]*"))) {
                            resultSizeText = entered
                            entered.toIntOrNull()?.let { size ->
                                commonOptions = commonOptions.copy(resultSize = size)
                                onUpdate(commonOptions)
                            }
                        }
                    },
                    modifier = Modifier.width(60.dp).padding(horizontal = 8.dp, vertical = 10.dp)
                        .testTag("search-result-count"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun CompactSearchAbilityTags(options: SearchServiceOptions) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CompactSearchAbilityChip(stringResource(R.string.search_ability_search))
        if (SearchService.getService(options).scrapingParameters(options) != null) {
            CompactSearchAbilityChip(stringResource(R.string.search_ability_scrape))
        }
    }
}

@Composable
private fun CompactSearchAbilityChip(label: String) {
    Surface(shape = CircleShape, color = SettingsCompactSoftColor()) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
