package com.constx.onvyra.ui.components.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Zap
import com.constx.onvyra.R
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.skills.SkillManager
import com.constx.onvyra.data.skills.SkillMetadata
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.ChatExtensionSelection
import com.constx.onvyra.ui.components.ai.ExtensionEmptyState
import com.constx.onvyra.ui.components.ai.LorebooksContent
import com.constx.onvyra.ui.components.ai.ModeInjectionsContent
import com.constx.onvyra.ui.components.ai.QuickMessagesContent
import com.constx.onvyra.ui.components.ai.SkillsContent
import org.koin.compose.koinInject

internal enum class ExtensionTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    QUICK_MESSAGES(HugeIcons.Zap, R.string.extension_selector_tab_quick_messages),
    MODE_INJECTIONS(HugeIcons.MagicWand01, R.string.extension_selector_tab_mode_injections),
    LOREBOOKS(HugeIcons.Book01, R.string.extension_selector_tab_lorebooks),
    SKILLS(HugeIcons.Puzzle, R.string.extension_selector_tab_skills);

    override val label: String
        @Composable get() = stringResource(labelRes)
}


@Composable
fun ExtensionSelector(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (ChatExtensionSelection, (Assistant) -> Assistant) -> Unit,
    onNavigateToQuickMessages: () -> Unit = {},
    onNavigateToPrompts: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
) {
    val skillManager: SkillManager = koinInject()
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }

    LaunchedEffect(Unit) {
        // 打开扩展面板时清理运行时被删除的技能（残留的 enabledSkills 引用），
        // prune 顺带返回现存技能列表，避免重复读盘
        skills = skillManager.pruneOrphanedEnabledSkills()
    }

    val pagerState = rememberPagerState { ExtensionTab.entries.size }

    Column(
        modifier = modifier
    ) {
        PagerTabs(
            pagerState = pagerState,
            tabs = ExtensionTab.entries,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            val tab = ExtensionTab.entries[page]
            when (tab) {
                ExtensionTab.QUICK_MESSAGES -> {
                    if (settings.quickMessages.isNotEmpty()) {
                        QuickMessagesContent(
                            quickMessages = settings.quickMessages,
                            selectedIds = assistant.quickMessageIds,
                            onToggle = { id, checked ->
                                onUpdate(ChatExtensionSelection.QUICK_MESSAGES) { latest ->
                                    latest.copy(quickMessageIds = if (checked) {
                                        latest.quickMessageIds + id
                                    } else {
                                        latest.quickMessageIds - id
                                    })
                                }
                            },
                            onManage = onNavigateToQuickMessages,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_quick_messages_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToQuickMessages,
                            icon = tab.icon,
                        )
                    }
                }

                ExtensionTab.MODE_INJECTIONS -> {
                    if (settings.modeInjections.any { it.enabled }) {
                        ModeInjectionsContent(
                            modeInjections = settings.modeInjections,
                            selectedIds = assistant.modeInjectionIds,
                            onToggle = { id, checked ->
                                onUpdate(ChatExtensionSelection.MODE_INJECTIONS) { latest ->
                                    latest.copy(modeInjectionIds = if (checked) {
                                        latest.modeInjectionIds + id
                                    } else {
                                        latest.modeInjectionIds - id
                                    })
                                }
                            },
                            onManage = onNavigateToPrompts,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_mode_injections_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                            icon = tab.icon,
                        )
                    }
                }

                ExtensionTab.LOREBOOKS -> {
                    if (settings.lorebooks.isNotEmpty()) {
                        LorebooksContent(
                            lorebooks = settings.lorebooks,
                            selectedIds = assistant.lorebookIds,
                            onToggle = { id, checked ->
                                onUpdate(ChatExtensionSelection.LOREBOOKS) { latest ->
                                    latest.copy(lorebookIds = if (checked) {
                                        latest.lorebookIds + id
                                    } else {
                                        latest.lorebookIds - id
                                    })
                                }
                            },
                            onManage = onNavigateToPrompts,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_lorebooks_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                            icon = tab.icon,
                        )
                    }
                }

                ExtensionTab.SKILLS -> {
                    if (skills.isNotEmpty()) {
                        SkillsContent(
                            skills = skills,
                            enabledSkills = assistant.enabledSkills,
                            onToggle = { name, checked ->
                                onUpdate(ChatExtensionSelection.SKILLS) { latest ->
                                    latest.copy(enabledSkills = if (checked) {
                                        latest.enabledSkills + name
                                    } else {
                                        latest.enabledSkills - name
                                    })
                                }
                            },
                            onManage = onNavigateToSkills,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_skills_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_skills),
                            onAction = onNavigateToSkills,
                            icon = tab.icon,
                        )
                    }
                }
            }
        }
    }
}
