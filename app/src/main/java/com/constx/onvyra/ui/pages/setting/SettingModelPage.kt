package com.constx.onvyra.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ReasoningCapabilities
import me.rerere.ai.provider.reasoningCapabilities
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowShrink
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.ScanText
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.Zap
import com.constx.onvyra.R
import com.constx.onvyra.data.ai.compression.formatAutoCompressionThresholdInput
import com.constx.onvyra.data.ai.compression.parseAutoCompressionThresholdInput
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.datastore.findProvider
import com.constx.onvyra.ui.components.ai.ModelListSheet
import com.constx.onvyra.ui.components.ai.ModelListState
import com.constx.onvyra.ui.components.ai.ReasoningPicker
import com.constx.onvyra.ui.components.ai.rememberModelListState
import com.constx.onvyra.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

private enum class ModelSettingTab(@param:StringRes val labelRes: Int) {
    MODEL(R.string.setting_model_page_tab_model),
    PROMPT(R.string.setting_model_page_tab_prompt);
}

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { ModelSettingTab.entries.size }
    val navController = LocalNavController.current
    val pageColor = MaterialTheme.colorScheme.background

    Scaffold(
        containerColor = pageColor,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.setting_model_page_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(48.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                CircleShape,
                            ),
                    ) {
                        Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = pageColor,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            ModelSettingTabs(pagerState)

            val contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                when (ModelSettingTab.entries[page]) {
                    ModelSettingTab.MODEL -> ModelSettingsPage(settings, vm, contentPadding)
                    ModelSettingTab.PROMPT -> PromptSettingsPage(settings, vm, contentPadding)
                }
            }
        }
    }
}

@Composable
private fun ModelSettingTabs(pagerState: PagerState) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModelSettingTab.entries.forEachIndexed { index, tab ->
            val isSelected = pagerState.targetPage == index
            Surface(
                onClick = {
                    if (!isSelected) scope.launch { pagerState.animateScrollToPage(index) }
                },
                modifier = Modifier
                    .widthIn(min = 92.dp)
                    .heightIn(min = 40.dp)
                    .semantics { role = Role.Tab; selected = isSelected },
                shape = CircleShape,
                color = if (isSelected) MaterialTheme.colorScheme.background
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f),
                contentColor = if (isSelected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                border = if (isSelected) BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                ) else null,
            ) {
                Text(
                    stringResource(tab.labelRes),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    val chatModel = rememberModelListState(
        modelId = settings.chatModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val fastModel = rememberModelListState(
        modelId = settings.fastModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val translateModel = rememberModelListState(
        modelId = settings.translateModeId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val ocrModel = rememberModelListState(
        modelId = settings.ocrModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val compressModel = rememberModelListState(
        modelId = settings.compressModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item("chatAndReasoning") {
            ModelSettingOutlineGroup(title = "聊天与推理") {
                ModelSettingModelRow(HugeIcons.BubbleChat, R.string.setting_model_page_default_chat_model, chatModel)
                ModelSettingOutlineDivider()
                ModelSettingModelRow(HugeIcons.Zap, R.string.setting_model_page_fast_model, fastModel)
                ModelSettingOutlineDivider()
                ModelSettingReasoningRow(
                    capabilities = fastModel.currentModel?.let {
                        reasoningCapabilities(it, it.findProvider(settings.providers))
                    } ?: ReasoningCapabilities(ReasoningLevel.entries, isKnown = false),
                    reasoningLevel = settings.fastModelReasoningLevel,
                    onUpdateReasoningLevel = {
                        vm.updateSettings { latest -> latest.copy(fastModelReasoningLevel = it) }
                    },
                )
                ModelSettingOutlineDivider()
                ModelSettingSwitchRow(
                    icon = HugeIcons.File01,
                    title = stringResource(R.string.setting_model_page_enable_auto_title),
                    checked = settings.enableAutoTitle,
                    onCheckedChange = {
                        vm.updateSettings { latest -> latest.copy(enableAutoTitle = it) }
                    },
                )
                ModelSettingOutlineDivider()
                ModelSettingSwitchRow(
                    icon = HugeIcons.Idea01,
                    title = stringResource(R.string.setting_model_page_enable_suggestion),
                    checked = settings.enableSuggestion,
                    onCheckedChange = {
                        vm.updateSettings { latest -> latest.copy(enableSuggestion = it) }
                    },
                )
            }
        }
        item("specializedModels") {
            ModelSettingOutlineGroup(title = "专用模型") {
                ModelSettingModelRow(HugeIcons.Translate, R.string.setting_model_page_translate_model, translateModel)
                ModelSettingOutlineDivider()
                ModelSettingModelRow(HugeIcons.ScanText, R.string.setting_model_page_ocr_model, ocrModel)
                ModelSettingOutlineDivider()
                ModelSettingModelRow(HugeIcons.ArrowShrink, R.string.setting_model_page_compress_model, compressModel)
                ModelSettingOutlineDivider()
                MediaModelSettingRows(settings = settings, onUpdateSettings = vm::updateSettings)
            }
        }
        item("contextSettings") {
            ModelSettingOutlineGroup(title = "上下文") {
                ModelSettingSwitchRow(
                    icon = HugeIcons.BubbleChat,
                    title = stringResource(R.string.setting_model_page_auto_compress_context),
                    checked = settings.autoCompressContext,
                    onCheckedChange = {
                        vm.updateSettings { latest -> latest.copy(autoCompressContext = it) }
                    },
                )
                ModelSettingOutlineDivider()
                AutoCompressionThresholdSetting(
                    enabled = settings.autoCompressContext,
                    tokenThreshold = settings.autoCompressContextTokenThreshold,
                    onThresholdChange = { threshold ->
                        vm.updateSettings { latest ->
                            latest.copy(autoCompressContextTokenThreshold = threshold)
                        }
                    },
                )
            }
        }
    }

    ModelListSheet(state = chatModel) {
        vm.updateSettings { latest -> latest.copy(chatModelId = it.id) }
    }
    ModelListSheet(state = fastModel) {
        vm.updateSettings { latest -> latest.copy(fastModelId = it.id) }
    }
    ModelListSheet(state = translateModel) {
        vm.updateSettings { latest -> latest.copy(translateModeId = it.id) }
    }
    ModelListSheet(state = ocrModel) {
        vm.updateSettings { latest -> latest.copy(ocrModelId = it.id) }
    }
    ModelListSheet(state = compressModel) {
        vm.updateSettings { latest -> latest.copy(compressModelId = it.id) }
    }
}

@Composable
private fun AutoCompressionThresholdSetting(
    enabled: Boolean,
    tokenThreshold: Int,
    onThresholdChange: (Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val status = if (tokenThreshold <= 0) {
        stringResource(R.string.setting_model_page_auto_compress_threshold_auto)
    } else {
        stringResource(
            R.string.setting_model_page_auto_compress_threshold_value,
            formatAutoCompressionThresholdInput(tokenThreshold),
        )
    }
    Column(Modifier.alpha(if (enabled) 1f else 0.38f)) {
        ModelSettingOutlineRow(
            icon = HugeIcons.ArrowShrink,
            title = stringResource(R.string.setting_model_page_auto_compress_threshold),
            status = status,
            onClick = if (enabled) ({ showDialog = true }) else null,
        )
    }
    if (showDialog) {
        AutoCompressionThresholdDialog(
            currentThreshold = tokenThreshold,
            onDismiss = { showDialog = false },
            onConfirm = { threshold ->
                onThresholdChange(threshold)
                showDialog = false
            },
        )
    }
}

@Composable
private fun AutoCompressionThresholdDialog(
    currentThreshold: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var input by remember(currentThreshold) {
        mutableStateOf(formatAutoCompressionThresholdInput(currentThreshold))
    }
    val parsedThreshold = parseAutoCompressionThresholdInput(input)
    val isError = parsedThreshold == null
    fun confirm() {
        parsedThreshold?.let(onConfirm)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.setting_model_page_auto_compress_threshold)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.setting_model_page_auto_compress_threshold_description))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.setting_model_page_auto_compress_threshold_label)) },
                    placeholder = { Text(stringResource(R.string.setting_model_page_auto_compress_threshold_example)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (isError) R.string.setting_model_page_auto_compress_threshold_error
                                else R.string.setting_model_page_auto_compress_threshold_hint,
                            )
                        )
                    },
                    isError = isError,
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = !isError) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ModelSettingModelRow(icon: ImageVector, @StringRes title: Int, state: ModelListState) {
    ModelSettingModelRow(icon, stringResource(title), state)
}

@Composable
internal fun ModelSettingModelRow(icon: ImageVector, title: String, state: ModelListState, fallbackName: String? = null) {
    val model = state.currentModel
    ModelSettingOutlineRow(
        icon = icon,
        title = title,
        status = model?.let { it.displayName.ifBlank { it.modelId } }
            ?: fallbackName ?: stringResource(R.string.model_list_select_model),
        onClick = { state.open() },
        trailingContent = { Icon(HugeIcons.ArrowDown01, null, modifier = Modifier.size(20.dp)) },
    )
}

// Shared only by the model and prompt tabs; other settings retain their own components.
@Composable
internal fun ModelSettingOutlineGroup(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        ) { Column(content = content) }
    }
}

@Composable
internal fun ModelSettingOutlineRow(
    icon: ImageVector,
    title: String,
    status: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = if (status == null) 52.dp else 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingContent != null) {
            trailingContent()
        } else {
            Icon(
                HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ModelSettingOutlineDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}

@Composable
private fun ModelSettingSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ModelSettingOutlineRow(
        icon = icon,
        title = title,
        onClick = { onCheckedChange(!checked) },
        trailingContent = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.scale(0.82f),
                )
            }
        },
    )
}

@Composable
internal fun ModelSettingReasoningRow(
    capabilities: ReasoningCapabilities,
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val effectiveLevel = capabilities.effectiveLevel(reasoningLevel)
    ModelSettingOutlineRow(
        icon = HugeIcons.AiBrain01,
        title = stringResource(R.string.assistant_page_thinking_budget),
        onClick = { showPicker = true },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = modelSettingReasoningLabel(effectiveLevel, capabilities),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
    if (showPicker) {
        ReasoningPicker(
            reasoningLevel = reasoningLevel,
            capabilities = capabilities,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningLevel = onUpdateReasoningLevel,
        )
    }
}

@Composable
private fun modelSettingReasoningLabel(level: ReasoningLevel, capabilities: ReasoningCapabilities): String {
    if (capabilities.isToggleOnly && level != ReasoningLevel.OFF && level != ReasoningLevel.AUTO) {
        return stringResource(R.string.reasoning_on)
    }
    return stringResource(
        when (level) {
            ReasoningLevel.OFF -> R.string.reasoning_off
            ReasoningLevel.AUTO -> R.string.reasoning_auto
            ReasoningLevel.LOW -> R.string.reasoning_light
            ReasoningLevel.MEDIUM -> R.string.reasoning_medium
            ReasoningLevel.HIGH -> R.string.reasoning_heavy
            ReasoningLevel.XHIGH -> R.string.reasoning_xhigh
            ReasoningLevel.MAX -> R.string.reasoning_max
        }
    )
}
