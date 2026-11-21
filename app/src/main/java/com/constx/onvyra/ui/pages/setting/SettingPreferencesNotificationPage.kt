package com.constx.onvyra.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constx.onvyra.R
import com.constx.onvyra.data.datastore.DisplaySetting
import com.constx.onvyra.ui.components.ui.permission.PermissionManager
import com.constx.onvyra.ui.components.ui.permission.PermissionNotification
import com.constx.onvyra.ui.components.ui.permission.rememberPermissionState
import com.constx.onvyra.ui.pages.setting.settingsDetailSwitchItem as switchItem
import com.constx.onvyra.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesNotificationPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings { it.copy(displaySetting = setting) }
    }


    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    Scaffold(
        topBar = { SettingsHomeTopBar(stringResource(R.string.setting_page_preferences_notification)) },
        containerColor = MaterialTheme.colorScheme.background
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsDetailGroup {
                    switchItem(
                        checked = displaySetting.enableNotificationOnMessageGeneration,
                        onCheckedChange = {
                            if (it && !permissionState.allPermissionsGranted) {
                                permissionState.requestPermissions()
                            }
                            updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                        },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                    )
                    if (displaySetting.enableNotificationOnMessageGeneration) {
                        switchItem(
                            checked = displaySetting.enableLiveUpdateNotification,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(enableLiveUpdateNotification = it))
                            },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_live_update_notification_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_display_page_live_update_notification)) },
                        )
                    }
                }
            }
        }
    }
}
