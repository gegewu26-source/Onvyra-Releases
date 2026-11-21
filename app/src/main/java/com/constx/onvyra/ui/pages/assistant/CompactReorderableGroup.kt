package com.constx.onvyra.ui.pages.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.constx.onvyra.ui.pages.setting.SettingsCompactCardColor

/** Joins independently lazy/reorderable rows without making the whole list one large item. */
@Composable
internal fun Modifier.compactGroupedRow(index: Int, count: Int): Modifier {
    val first = index == 0
    val last = index == count - 1
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val shape = RoundedCornerShape(
        topStart = if (first) 24.dp else 0.dp,
        topEnd = if (first) 24.dp else 0.dp,
        bottomStart = if (last) 24.dp else 0.dp,
        bottomEnd = if (last) 24.dp else 0.dp,
    )
    return clip(shape).background(SettingsCompactCardColor()).drawWithCache {
        val stroke = 1.dp.toPx()
        val half = stroke / 2f
        val r = 24.dp.toPx().coerceAtMost(size.height / 2f)
        val w = size.width
        val h = size.height
        val edge = Path().apply {
            moveTo(half, if (first) r else 0f)
            lineTo(half, if (last) h - r else h)
            moveTo(w - half, if (first) r else 0f)
            lineTo(w - half, if (last) h - r else h)
            if (first) {
                moveTo(half, r)
                quadraticBezierTo(half, half, r, half)
                lineTo(w - r, half)
                quadraticBezierTo(w - half, half, w - half, r)
            }
            if (last) {
                moveTo(half, h - r)
                quadraticBezierTo(half, h - half, r, h - half)
                lineTo(w - r, h - half)
                quadraticBezierTo(w - half, h - half, w - half, h - r)
            }
        }
        onDrawBehind {
            drawPath(edge, outline, style = Stroke(stroke))
            if (!last) drawLine(outline, Offset(0f, h - half), Offset(w, h - half), 0.5.dp.toPx())
        }
    }
}
