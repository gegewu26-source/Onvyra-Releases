package com.constx.onvyra.ui.components.message

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Translate
import com.constx.onvyra.R
import java.util.Locale

/** A short press reuses the shared preference; only this button has a one-second long press. */
@Composable
internal fun MessageTranslationAction(
    lastLanguageTag: String?,
    onRememberLanguage: (String) -> Unit,
    onTranslate: (Locale) -> Unit,
    onClearTranslation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showLanguageSelection by remember { mutableStateOf(false) }
    val language = remember(lastLanguageTag) {
        lastLanguageTag?.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag)
            ?.takeIf { it.language.isNotBlank() && it.language != "und" }
    }
    val viewConfiguration = LocalViewConfiguration.current
    val translationViewConfiguration = remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            override val longPressTimeoutMillis: Long = 1_000L
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shapes = IconButtonDefaults.shapes()

    CompositionLocalProvider(LocalViewConfiguration provides translationViewConfiguration) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(IconButtonDefaults.extraSmallContainerSize())
                .clip(if (pressed) shapes.pressedShape else shapes.shape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onLongClickLabel = stringResource(R.string.translation_language_selection_title),
                    onLongClick = { showLanguageSelection = true },
                    onClick = {
                        if (language == null) showLanguageSelection = true else onTranslate(language)
                    },
                ),
        ) {
            Icon(
                imageVector = HugeIcons.Translate,
                contentDescription = stringResource(R.string.translate),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    if (showLanguageSelection) {
        LanguageSelectionDialog(
            onLanguageSelected = {
                showLanguageSelection = false
                onRememberLanguage(it.toLanguageTag())
                onTranslate(it)
            },
            onClearTranslation = {
                showLanguageSelection = false
                onClearTranslation()
            },
            onDismissRequest = { showLanguageSelection = false },
        )
    }
}
