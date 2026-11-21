package me.rerere.ui.sketch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CursorRectangleSelection01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.Type
import me.rerere.ui.R

/**
 * 画纸下方的工具栏：粗细、画笔、文字、选择、橡皮和颜色。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SketchToolbar(state: SketchState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val widthLabel = stringResource(R.string.sketch_dialog_brush_width)
        val widthValue = "%.1f".format(state.width.value)
        val widthSliderState = remember(state) {
            SliderState(
                value = state.width.value,
                trackRange = SketchDefaults.BrushWidthRange,
            )
        }
        widthSliderState.value = state.width.value
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                state = widthSliderState,
                onValueChange = { state.width = it.dp },
                modifier = Modifier
                    // Keep the original three 40dp choices and their two 4dp gaps' footprint.
                    .width(128.dp)
                    .semantics {
                        contentDescription = widthLabel
                        stateDescription = widthValue
                    },
                thumb = {
                    Box(
                        Modifier
                            .size(20.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(8.dp),
                        thumbTrackGapSize = 0.dp,
                        drawStopIndicator = null,
                    )
                },
            )
            Spacer(Modifier.weight(1f))
            SketchBrushMenu(state = state)
            FilledTonalIconToggleButton(
                checked = state.tool == SketchTool.Text,
                onCheckedChange = state::useText,
            ) {
                Icon(HugeIcons.Type, contentDescription = stringResource(R.string.sketch_dialog_text))
            }
            FilledTonalIconToggleButton(
                checked = state.tool == SketchTool.Select,
                onCheckedChange = state::useSelect,
            ) {
                Icon(
                    HugeIcons.CursorRectangleSelection01,
                    contentDescription = stringResource(R.string.sketch_dialog_select),
                )
            }
            FilledTonalIconToggleButton(
                checked = state.erasing,
                onCheckedChange = state::useEraser,
            ) {
                Icon(HugeIcons.Eraser, contentDescription = stringResource(R.string.sketch_dialog_eraser))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SketchDefaults.BrushColors.forEach { color ->
                ColorOption(
                    color = color,
                    selected = !state.erasing && !state.usingCustomColor && state.color == color,
                    onClick = { state.usePreset(color) },
                )
            }
            val customSelected = !state.erasing && state.usingCustomColor
            ColorOption(
                color = state.customColor,
                selected = customSelected,
                custom = true,
                onClick = {
                    val custom = state.customColor
                    when {
                        // 第一次点：给一个起始色，直接开始调
                        custom == null -> {
                            state.useCustom(SketchDefaults.CustomColor)
                            state.pickingColor = true
                        }
                        // 调过之后，点一下是选中它，选中时再点才是打开或收起调色面板
                        !customSelected -> state.useCustom(custom)
                        else -> state.pickingColor = !state.pickingColor
                    }
                },
            )
        }
    }
}

// [custom] 是自定义颜色的那一格：外面一圈彩虹，调过颜色之后中间显示调出来的颜色
@Composable
private fun ColorOption(
    color: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    custom: Boolean = false,
) {
    val customLabel = stringResource(R.string.sketch_dialog_custom_color)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .then(if (custom) Modifier.semantics { contentDescription = customLabel } else Modifier)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                }
            )
            .padding(6.dp)
            .then(
                if (custom) {
                    Modifier
                        .background(Brush.sweepGradient(SketchDefaults.HueColors), CircleShape)
                        .padding(4.dp)
                } else {
                    Modifier
                }
            )
            .then(if (color != null) Modifier.background(color, CircleShape) else Modifier)
            // 深色主题下黑色的色块要靠描边才看得出来
            .then(
                if (custom) {
                    Modifier
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                }
            ),
    )
}
