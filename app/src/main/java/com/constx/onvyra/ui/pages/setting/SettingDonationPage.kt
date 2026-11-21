package com.constx.onvyra.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.constx.onvyra.R
import com.constx.onvyra.ui.components.support.AuthorSupportContent
import com.constx.onvyra.ui.components.support.AuthorSupportImageDialog
import com.constx.onvyra.ui.components.support.SupportPayment
import com.constx.onvyra.utils.plus

/** The existing author content has one dedicated settings destination. */
@Composable
fun SettingDonationPage() {
    var payment by remember { mutableStateOf<SupportPayment?>(null) }
    payment?.let { selected -> AuthorSupportImageDialog(selected, onDismiss = { payment = null }) }
    Scaffold(topBar = { SettingsHomeTopBar(stringResource(R.string.setting_page_donate)) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().testTag("donation-page"),
            contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { AuthorSupportContent(Modifier.fillMaxWidth().testTag("donation-author-support"),
                showIntroduction = true, onQrSelected = { payment = it }) }
        }
    }
}
