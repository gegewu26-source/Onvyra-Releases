package com.constx.onvyra.ui.pages.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small outline icons for the drawer's shared navigation bar. */
internal object DrawerFooterIcons {
    val Assistant: ImageVector by lazy {
        outlineIcon("DrawerAssistant") {
            moveTo(8f, 7f)
            lineTo(16f, 7f)
            curveTo(19f, 7f, 21f, 9f, 21f, 12f)
            lineTo(21f, 16f)
            curveTo(21f, 19f, 19f, 21f, 16f, 21f)
            lineTo(8f, 21f)
            curveTo(5f, 21f, 3f, 19f, 3f, 16f)
            lineTo(3f, 12f)
            curveTo(3f, 9f, 5f, 7f, 8f, 7f)
            close()
            moveTo(8f, 12.5f)
            lineTo(8f, 15.5f)
            moveTo(15f, 12.5f)
            lineTo(15f, 15.5f)
            moveTo(10f, 7f)
            lineTo(10f, 4.5f)
            moveTo(8.8f, 3.2f)
            curveTo(8.8f, 1.6f, 11.2f, 1.6f, 11.2f, 3.2f)
            curveTo(11.2f, 4.8f, 8.8f, 4.8f, 8.8f, 3.2f)
            close()
            moveTo(3f, 11f)
            curveTo(0f, 11f, 0f, 17f, 3f, 17f)
            moveTo(21f, 11f)
            curveTo(24f, 11f, 24f, 17f, 21f, 17f)
            moveTo(19f, 1f)
            lineTo(19.8f, 3f)
            lineTo(22f, 4f)
            lineTo(19.8f, 4.8f)
            lineTo(19f, 7f)
            lineTo(18.2f, 4.8f)
            lineTo(16f, 4f)
            lineTo(18.2f, 3f)
            close()
        }
    }

    val Favorite: ImageVector by lazy {
        outlineIcon("DrawerFavorite") {
            moveTo(6f, 2f)
            lineTo(18f, 2f)
            curveTo(20f, 2f, 21f, 3f, 21f, 5f)
            lineTo(21f, 21f)
            curveTo(21f, 22f, 20f, 22f, 19f, 21.5f)
            lineTo(12f, 18f)
            lineTo(5f, 21.5f)
            curveTo(4f, 22f, 3f, 22f, 3f, 21f)
            lineTo(3f, 5f)
            curveTo(3f, 3f, 4f, 2f, 6f, 2f)
            close()
            moveTo(12f, 6f)
            lineTo(13.6f, 9.3f)
            lineTo(17.2f, 9.8f)
            lineTo(14.6f, 12.3f)
            lineTo(15.2f, 15.9f)
            lineTo(12f, 14.2f)
            lineTo(8.8f, 15.9f)
            lineTo(9.4f, 12.3f)
            lineTo(6.8f, 9.8f)
            lineTo(10.4f, 9.3f)
            close()
        }
    }

    val Stats: ImageVector by lazy {
        outlineIcon("DrawerStats") {
            moveTo(3f, 12f)
            curveTo(10f, 11f, 16f, 7f, 20f, 2f)
            moveTo(16f, 2f)
            lineTo(20f, 2f)
            lineTo(20f, 6f)
            moveTo(3f, 17f)
            lineTo(7f, 17f)
            lineTo(7f, 22f)
            lineTo(3f, 22f)
            close()
            moveTo(10f, 14f)
            lineTo(14f, 14f)
            lineTo(14f, 22f)
            lineTo(10f, 22f)
            close()
            moveTo(17f, 10f)
            lineTo(21f, 10f)
            lineTo(21f, 22f)
            lineTo(17f, 22f)
            close()
        }
    }

    val Book: ImageVector by lazy {
        outlineIcon("DrawerBook") {
            moveTo(12f, 5f)
            curveTo(9f, 2f, 4f, 2f, 2f, 4f)
            lineTo(2f, 19f)
            curveTo(5f, 17f, 9f, 18f, 12f, 21f)
            curveTo(15f, 18f, 19f, 17f, 22f, 19f)
            lineTo(22f, 4f)
            curveTo(20f, 2f, 15f, 2f, 12f, 5f)
            close()
            moveTo(12f, 5f)
            lineTo(12f, 21f)
            moveTo(5.5f, 8f)
            curveTo(7f, 7.7f, 8f, 8f, 9f, 8.5f)
            moveTo(5.5f, 12f)
            curveTo(7f, 11.7f, 8f, 12f, 9f, 12.5f)
            moveTo(15f, 8.5f)
            curveTo(16f, 8f, 17f, 7.7f, 18.5f, 8f)
            moveTo(15f, 12.5f)
            curveTo(16f, 12f, 17f, 11.7f, 18.5f, 12f)
        }
    }
}

private fun outlineIcon(name: String, draw: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        )
    }.build()
