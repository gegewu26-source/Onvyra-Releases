package com.constx.onvyra.ui.pages.setting

import androidx.compose.runtime.Composable
import com.constx.onvyra.data.ai.tools.selectedMediaModel
import com.constx.onvyra.data.ai.tools.canSelectMediaRole
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.ui.components.ai.ModelListSheet
import com.constx.onvyra.ui.components.ai.rememberModelListState
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.mediagen.model.MediaKind

/** Reuses the same rows and existing-provider model sheet as the other specialized models. */
@Composable
internal fun MediaModelSettingRows(settings: Settings, onUpdateSettings: ((Settings) -> Settings) -> Unit) {
    val states = MediaKind.entries.map { kind ->
        val providers = settings.providers.filter { it.enabled }.mapNotNull { provider ->
            val models = provider.models.filter { model ->
                model.type != ModelType.EMBEDDING && model.canSelectMediaRole(provider, kind)
            }
            provider.copyProvider(models = models).takeIf { models.isNotEmpty() }
        }
        rememberModelListState(
            modelId = if (kind == MediaKind.IMAGE) settings.chatImageModelId else settings.chatVideoModelId,
            providers = providers,
            type = null,
        )
    }
    MediaKind.entries.forEachIndexed { index, kind ->
        if (index > 0) ModelSettingOutlineDivider()
        val state = states[index]
        val legacyName = settings.selectedMediaModel(kind)?.second?.let { it.displayName.ifBlank { it.modelId } }
        val unsupportedSelection = if (kind == MediaKind.IMAGE && legacyName == null) settings.providers
            .flatMap { it.models }.firstOrNull { it.id == settings.chatImageModelId }
            ?.let { "${it.displayName.ifBlank { it.modelId }}（未声明生图能力）" } else null
        ModelSettingModelRow(
            icon = if (kind == MediaKind.IMAGE) HugeIcons.Image02 else HugeIcons.Video01,
            title = if (kind == MediaKind.IMAGE) "生图模型" else "视频模型",
            state = state,
            fallbackName = legacyName ?: unsupportedSelection,
        )
        ModelListSheet(
            state = state,
            onClear = {
                onUpdateSettings { latest ->
                    if (kind == MediaKind.IMAGE) latest.copy(chatImageModelId = null)
                    else latest.copy(chatVideoModelId = null)
                }
            },
        ) { model ->
            onUpdateSettings { latest ->
                if (kind == MediaKind.IMAGE) latest.copy(chatImageModelId = model.id)
                else latest.copy(chatVideoModelId = model.id)
            }
        }
    }
}
