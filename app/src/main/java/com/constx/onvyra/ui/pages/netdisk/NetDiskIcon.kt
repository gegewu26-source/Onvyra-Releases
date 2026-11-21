package com.constx.onvyra.ui.pages.netdisk

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Native rendition of the supplied cloud/link design; the original PNG stays in the source. */
@Composable
fun NetDiskIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = NetDiskCloudLinkVector,
        contentDescription = null,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurface,
    )
}

private val NetDiskCloudLinkVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "OnvyraCloudLink",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // Keep the cloud open around the chain, as in the supplied artwork.
            moveTo(5.15f, 18.35f)
            curveTo(3.12f, 17.93f, 1.7f, 16.12f, 1.7f, 13.9f)
            curveTo(1.7f, 11.16f, 3.93f, 8.93f, 6.67f, 8.93f)
            curveTo(7.04f, 5.67f, 9.29f, 3.1f, 12.5f, 3.1f)
            curveTo(15.7f, 3.1f, 18.1f, 5.53f, 18.48f, 8.69f)
            curveTo(20.58f, 8.83f, 22.3f, 10.91f, 22.3f, 13.53f)
            curveTo(22.3f, 16.28f, 20.06f, 18.5f, 17.32f, 18.5f)
            lineTo(14.7f, 18.5f)

            // The two open, interlocking links remain legible at the 24dp settings size.
            moveTo(8.85f, 16.2f)
            curveTo(7.86f, 15.22f, 6.26f, 15.22f, 5.28f, 16.2f)
            lineTo(3.73f, 17.75f)
            curveTo(2.74f, 18.73f, 2.74f, 20.33f, 3.73f, 21.32f)
            curveTo(4.71f, 22.3f, 6.31f, 22.3f, 7.3f, 21.32f)
            lineTo(8.95f, 19.67f)

            moveTo(8.8f, 14.03f)
            lineTo(10.39f, 12.44f)
            curveTo(11.38f, 11.45f, 12.98f, 11.45f, 13.96f, 12.44f)
            curveTo(14.95f, 13.42f, 14.95f, 15.02f, 13.96f, 16f)
            lineTo(12.37f, 17.59f)
            curveTo(11.39f, 18.58f, 9.79f, 18.58f, 8.8f, 17.59f)

            moveTo(13.25f, 9.13f)
            lineTo(13.25f, 9.85f)
            moveTo(15.45f, 9.93f)
            lineTo(14.94f, 10.44f)
            moveTo(16.42f, 12.02f)
            lineTo(15.7f, 12.02f)
        }
    }.build()
}
