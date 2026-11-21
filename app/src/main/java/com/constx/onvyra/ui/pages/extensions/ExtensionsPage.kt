package com.constx.onvyra.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Zap
import com.constx.onvyra.R
import com.constx.onvyra.Screen
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.pages.setting.SettingsHomeDivider
import com.constx.onvyra.ui.pages.setting.SettingsHomeGroup
import com.constx.onvyra.ui.pages.setting.SettingsHomeRow
import com.constx.onvyra.ui.pages.setting.SettingsHomeTopBar
import com.constx.onvyra.utils.plus

@Composable
fun ExtensionsPage() {
    val navController = LocalNavController.current
    Scaffold(
        topBar = { SettingsHomeTopBar(stringResource(R.string.extensions_page_title)) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("extensions") {
                SettingsHomeGroup(stringResource(R.string.extensions_page_section_extensions)) {
                    SettingsHomeRow(
                        icon = HugeIcons.Zap,
                        title = stringResource(R.string.assistant_page_quick_messages),
                        onClick = { navController.navigate(Screen.QuickMessages) },
                    )
                    SettingsHomeDivider()
                    SettingsHomeRow(
                        icon = HugeIcons.Book03,
                        title = stringResource(R.string.extensions_page_prompts),
                        // The existing prompt page retains both mode injection and lorebook tabs.
                        onClick = { navController.navigate(Screen.Prompts) },
                    )
                    SettingsHomeDivider()
                    SettingsHomeRow(
                        icon = HugeIcons.Puzzle,
                        title = stringResource(R.string.extensions_page_agent_skills),
                        onClick = { navController.navigate(Screen.Skills) },
                    )
                    SettingsHomeDivider()
                    SettingsHomeRow(
                        icon = HugeIcons.Folder01,
                        title = stringResource(R.string.extensions_page_workspace),
                        onClick = { navController.navigate(Screen.Workspaces) },
                    )
                }
            }
        }
    }
}
