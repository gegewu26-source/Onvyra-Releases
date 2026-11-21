package com.constx.onvyra.ui.pages.setting

import com.constx.onvyra.data.datastore.findModelById
import com.constx.onvyra.data.datastore.findProvider
import me.rerere.ai.provider.reasoningCapabilities
import me.rerere.ai.provider.ReasoningCapabilities
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowShrink
import me.rerere.hugeicons.stroke.Heading
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.ScanText
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Video01
import com.constx.onvyra.R
import com.constx.onvyra.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import com.constx.onvyra.data.ai.prompts.DEFAULT_IMAGE_GENERATION_PROMPT
import com.constx.onvyra.data.ai.prompts.DEFAULT_OCR_PROMPT
import com.constx.onvyra.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import com.constx.onvyra.data.ai.prompts.DEFAULT_TITLE_PROMPT
import com.constx.onvyra.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import com.constx.onvyra.data.ai.prompts.DEFAULT_VIDEO_GENERATION_PROMPT
import com.constx.onvyra.data.datastore.Settings

@Composable
internal fun PromptSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    // 提示词编辑仍使用原有弹层，当前内容预览作为状态保留。
    var editing by remember { mutableStateOf<PromptSlot?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            // 只有翻译能单独设思考强度，所以自成一组
            ModelSettingOutlineGroup(title = stringResource(R.string.setting_model_page_prompt_translation)) {
                PromptSettingRow(
                    slot = PromptSlot.TRANSLATION,
                    settings = settings,
                    onClick = { editing = it },
                )
                ModelSettingOutlineDivider()
                ModelSettingReasoningRow(
                    capabilities = settings.findModelById(settings.translateModeId)?.let {
                        reasoningCapabilities(it, it.findProvider(settings.providers))
                    } ?: ReasoningCapabilities(ReasoningLevel.entries, isKnown = false),
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                    onUpdateReasoningLevel = {
                        vm.updateSettings { latest -> latest.copy(translateThinkingBudget = it.budgetTokens) }
                    },
                )
            }
        }
        item {
            ModelSettingOutlineGroup(title = stringResource(R.string.setting_model_page_prompt)) {
                PromptSlot.entries.filter {
                    it != PromptSlot.TRANSLATION && it != PromptSlot.IMAGE && it != PromptSlot.VIDEO
                }.forEachIndexed { index, slot ->
                    if (index > 0) ModelSettingOutlineDivider()
                    PromptSettingRow(
                        slot = slot,
                        settings = settings,
                        onClick = { editing = it },
                    )
                }
            }
        }
        item {
            ModelSettingOutlineGroup(title = stringResource(R.string.setting_model_page_media_prompts)) {
                PromptSettingRow(slot = PromptSlot.IMAGE, settings = settings, onClick = { editing = it })
                ModelSettingOutlineDivider()
                PromptSettingRow(slot = PromptSlot.VIDEO, settings = settings, onClick = { editing = it })
            }
        }
    }

    editing?.let { slot ->
        PromptEditSheet(
            title = stringResource(slot.title),
            description = stringResource(slot.variables),
            value = slot.get(settings),
            onValueChange = { value -> vm.updateSettings { latest -> slot.set(latest, value) } },
            onReset = { vm.updateSettings { latest -> slot.set(latest, slot.default) } },
            onDismiss = { editing = null },
        )
    }
}

// 可以自定义提示词的几项功能
private enum class PromptSlot(
    val icon: ImageVector,
    @param:StringRes val title: Int,
    // 提示词里可用的变量说明
    @param:StringRes val variables: Int,
    val default: String,
    val get: (Settings) -> String,
    val set: (Settings, String) -> Settings,
) {
    TRANSLATION(
        icon = HugeIcons.Translate,
        title = R.string.setting_model_page_prompt_translation,
        variables = R.string.setting_model_page_translate_prompt_vars,
        default = DEFAULT_TRANSLATION_PROMPT,
        get = { it.translatePrompt },
        set = { settings, value -> settings.copy(translatePrompt = value) },
    ),
    TITLE(
        icon = HugeIcons.Heading,
        title = R.string.setting_model_page_prompt_title,
        variables = R.string.setting_model_page_suggestion_prompt_vars,
        default = DEFAULT_TITLE_PROMPT,
        get = { it.titlePrompt },
        set = { settings, value -> settings.copy(titlePrompt = value) },
    ),
    SUGGESTION(
        icon = HugeIcons.Idea01,
        title = R.string.setting_model_page_prompt_suggestion,
        variables = R.string.setting_model_page_suggestion_prompt_vars,
        default = DEFAULT_SUGGESTION_PROMPT,
        get = { it.suggestionPrompt },
        set = { settings, value -> settings.copy(suggestionPrompt = value) },
    ),
    OCR(
        icon = HugeIcons.ScanText,
        title = R.string.setting_model_page_prompt_ocr,
        variables = R.string.setting_model_page_ocr_prompt_vars,
        default = DEFAULT_OCR_PROMPT,
        get = { it.ocrPrompt },
        set = { settings, value -> settings.copy(ocrPrompt = value) },
    ),
    COMPRESS(
        icon = HugeIcons.ArrowShrink,
        title = R.string.setting_model_page_prompt_compress,
        variables = R.string.setting_model_page_compress_prompt_vars,
        default = DEFAULT_COMPRESS_PROMPT,
        get = { it.compressPrompt },
        set = { settings, value -> settings.copy(compressPrompt = value) },
    ),
    IMAGE(
        icon = HugeIcons.Image02,
        title = R.string.setting_model_page_prompt_image_generation,
        variables = R.string.setting_model_page_image_generation_prompt_description,
        default = DEFAULT_IMAGE_GENERATION_PROMPT,
        get = { it.imageGenerationPrompt },
        set = { settings, value -> settings.copy(imageGenerationPrompt = value) },
    ),
    VIDEO(
        icon = HugeIcons.Video01,
        title = R.string.setting_model_page_prompt_video_generation,
        variables = R.string.setting_model_page_video_generation_prompt_description,
        default = DEFAULT_VIDEO_GENERATION_PROMPT,
        get = { it.videoGenerationPrompt },
        set = { settings, value -> settings.copy(videoGenerationPrompt = value) },
    ),
}

private val Whitespace = Regex("\\s+")

// 一项提示词：标题下面预览当前内容，点击后编辑
@Composable
private fun PromptSettingRow(
    slot: PromptSlot,
    settings: Settings,
    onClick: (PromptSlot) -> Unit,
) {
    val prompt = slot.get(settings)
    ModelSettingOutlineRow(
        icon = slot.icon,
        title = stringResource(slot.title),
        status = remember(prompt) { prompt.trim().replace(Whitespace, " ") },
        onClick = { onClick(slot) },
    )
}

@Composable
private fun PromptEditSheet(
    title: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 直接全展开，半展开时键盘会把输入框整个盖住
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                // 键盘弹出后高度不够时让输入框让位，下面的按钮不被挤出去
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                maxLines = 15,
            )
            TextButton(
                onClick = onReset,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.setting_model_page_reset_to_default))
            }
        }
    }
}
