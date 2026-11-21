package com.constx.onvyra.ui.pages.novel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun NovelReaderSettings.readerBackgroundColor(): Long =
    customBackgroundColor?.let(ReaderColorMath::opaque) ?: theme.backgroundColorHex

internal fun NovelReaderSettings.readerTextColor(): Long = customBackgroundColor?.let {
    ReaderColorMath.textColor(it)
} ?: theme.textColorHex

internal fun NovelReaderSettings.isReaderDark(): Boolean =
    ReaderColorMath.luminance(readerBackgroundColor()) < .179

/** Small value objects keep pointer moves independent of Android's Color APIs. */
internal object ReaderColorMath {
    data class Hsv(val hue: Float, val saturation: Float, val value: Float)

    fun opaque(color: Long): Long = (color and 0xFFFFFFL) or 0xFF000000L

    fun fromColor(color: Long): Hsv {
        val red = ((color shr 16) and 255).toFloat() / 255f
        val green = ((color shr 8) and 255).toFloat() / 255f
        val blue = (color and 255).toFloat() / 255f
        val high = maxOf(red, green, blue)
        val delta = high - minOf(red, green, blue)
        val hue = when {
            delta == 0f -> 0f
            high == red -> 60f * ((green - blue) / delta % 6f)
            high == green -> 60f * ((blue - red) / delta + 2f)
            else -> 60f * ((red - green) / delta + 4f)
        }
        return Hsv((hue + 360f) % 360f, if (high == 0f) 0f else delta / high, high)
    }

    fun toColor(hsv: Hsv): Long {
        val hue = if (hsv.hue.isFinite()) ((hsv.hue % 360f) + 360f) % 360f else 0f
        val saturation = if (hsv.saturation.isFinite()) hsv.saturation.coerceIn(0f, 1f) else 0f
        val value = if (hsv.value.isFinite()) hsv.value.coerceIn(0f, 1f) else 0f
        val chroma = value * saturation
        val x = chroma * (1f - abs((hue / 60f) % 2f - 1f))
        val offset = value - chroma
        val channels = when {
            hue < 60 -> floatArrayOf(chroma, x, 0f)
            hue < 120 -> floatArrayOf(x, chroma, 0f)
            hue < 180 -> floatArrayOf(0f, chroma, x)
            hue < 240 -> floatArrayOf(0f, x, chroma)
            hue < 300 -> floatArrayOf(x, 0f, chroma)
            else -> floatArrayOf(chroma, 0f, x)
        }
        fun channel(index: Int) = ((channels[index] + offset) * 255).roundToInt().coerceIn(0, 255).toLong()
        return 0xFF000000L or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    fun fromPoint(x: Float, y: Float, width: Float, height: Float, value: Float): Hsv {
        val radius = min(width, height) / 2f
        if (!radius.isFinite() || radius <= 0f) return Hsv(0f, 0f, value.coerceIn(0f, 1f))
        val dx = x - width / 2f
        val dy = y - height / 2f
        val distance = hypot(dx, dy)
        val hue = ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
        return Hsv(hue, (distance / radius).coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    }

    fun luminance(color: Long): Double {
        fun linear(shift: Int): Double {
            val value = ((color shr shift) and 255).toDouble() / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
    }

    fun textColor(background: Long): Long = if (luminance(background) < .179) 0xFFFFFFFF else 0xFF000000
    fun hex(color: Long): String = "#%06X".format(color and 0xFFFFFFL)
}

private val ReaderWheelColors = listOf(Color.Red, Color.Yellow, Color.Green,
    Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

@Composable
internal fun CustomColorWheelButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(modifier.clickable(onClick = onClick).semantics {
        contentDescription = "自定义背景颜色"
        stateDescription = if (selected) "已选中" else "未选中"
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(30.dp).clip(CircleShape)) {
            drawCircle(Brush.sweepGradient(ReaderWheelColors))
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center = center, radius = size.minDimension / 2))
            drawCircle(ink.copy(alpha = if (selected) .85f else .2f),
                radius = size.minDimension / 2 - 1.dp.toPx(),
                style = Stroke(if (selected) 2.dp.toPx() else .7f.dp.toPx()))
        }
        Text("自定义", fontSize = 9.sp, color = ink.copy(alpha = .7f), modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
internal fun ReaderColorPickerDialog(color: Long, onColorChange: (Long) -> Unit, onDismiss: () -> Unit) {
    var hsv by remember { mutableStateOf(ReaderColorMath.fromColor(color)) }
    val currentHsv by rememberUpdatedState(hsv)
    val changeCallback by rememberUpdatedState(onColorChange)
    fun choose(value: ReaderColorMath.Hsv) {
        hsv = value
        changeCallback(ReaderColorMath.toColor(value))
    }
    val selected = ReaderColorMath.toColor(hsv)
    val selectedColor = Color(selected)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("自定义背景") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("在圆盘上滑动选色", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp))
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f).testTag("reader-color-wheel")
                    .semantics { contentDescription = "背景颜色圆盘"; stateDescription = ReaderColorMath.hex(selected) }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fun pick(position: Offset) {
                                choose(ReaderColorMath.fromPoint(position.x, position.y, size.width.toFloat(),
                                    size.height.toFloat(), currentHsv.value))
                            }
                            pick(down.position)
                            down.consume()
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) { change.consume(); break }
                                pick(change.position)
                                change.consume()
                            }
                        }
                    }) {
                    val radius = size.minDimension / 2
                    drawCircle(Brush.sweepGradient(ReaderWheelColors, center))
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radius))
                    drawCircle(Color.Black.copy(alpha = 1f - hsv.value))
                    val angle = hsv.hue * PI.toFloat() / 180f
                    val marker = center + Offset(cos(angle), sin(angle)) * (hsv.saturation * (radius - 5.dp.toPx()))
                    drawCircle(Color.Black.copy(alpha = .7f), radius = 6.dp.toPx(), center = marker, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = marker, style = Stroke(1.5.dp.toPx()))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("明暗", fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                    Slider(value = hsv.value, onValueChange = { choose(hsv.copy(value = it)) }, modifier = Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp))
                    .background(selectedColor), contentAlignment = Alignment.Center) {
                    Text("预览文字  ${ReaderColorMath.hex(selected)}", color = Color(ReaderColorMath.textColor(selected)), fontSize = 13.sp)
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
