package com.constx.onvyra.ui.components.ui

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.constx.onvyra.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Keeps the supplied artwork's colours, proportions, and transparent background. */
@Composable
fun MascotIcon(
    modifier: Modifier = Modifier,
    animated: Boolean = false,
    playful: Boolean = false,
) {
    val iconModifier = modifier.size(32.dp)
    if (!animated) {
        Image(
            painter = painterResource(R.drawable.mascot_art),
            contentDescription = null,
            modifier = iconModifier,
            contentScale = ContentScale.Fit,
        )
        return
    }

    AnimatedMascotIcon(modifier = iconModifier, playful = playful)
}

@Composable
private fun AnimatedMascotIcon(modifier: Modifier, playful: Boolean) {
    val resources = LocalContext.current.resources
    val art = produceState<AnimatedMascotArt?>(AnimatedMascotArtCache.current, resources) {
        value = AnimatedMascotArtCache.load(resources)
    }.value
    if (art == null) {
        Spacer(modifier)
        return
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val artwork = art.artwork
    val eyeArtwork = art.eyes
    val elapsedSeconds = remember { mutableFloatStateOf(0f) }
    val eyeClips = remember(artwork) {
        MascotEyes.map { eye ->
            Path().apply {
                addOval(
                    Rect(
                        left = (eye.x - eye.radiusX * 0.92f) * artwork.width,
                        top = (eye.y - eye.radiusY * 0.92f) * artwork.height,
                        right = (eye.x + eye.radiusX * 0.92f) * artwork.width,
                        bottom = (eye.y + eye.radiusY * 0.92f) * artwork.height,
                    )
                )
            }
        }
    }

    LaunchedEffect(lifecycle, playful) {
        // Cancels the frame loop on pause and when this loading item leaves composition.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var firstFrame = 0L
            var previousDraw = 0L
            while (isActive && ValueAnimator.areAnimatorsEnabled()) {
                withInfiniteAnimationFrameNanos { now ->
                    if (firstFrame == 0L) firstFrame = now
                    // 30 drawings per second are enough for the small loading icon.
                    if (now - previousDraw >= 32_000_000L) {
                        val period = if (playful) 16_000_000_000L else 19_000_000_000L
                        elapsedSeconds.floatValue = ((now - firstFrame) % period) / 1_000_000_000f
                        previousDraw = now
                    }
                }
            }
        }
    }

    Canvas(modifier) {
        // Read animation state only in drawing, keeping the chat composition unchanged.
        val seconds = elapsedSeconds.floatValue
        val orbit = seconds * (2f * PI.toFloat() / if (playful) 4f else 3.8f)
        val pose = if (playful) mascotDancePose(seconds) else MascotDancePose(rotation = sin(orbit) * 3f)
        // The chat stage stays fixed; only this 28 dp sprite moves inside it.
        val bodySize = if (playful) min(28.dp.toPx(), min(size.width, size.height)) else min(size.width, size.height)
        val scale = min(bodySize / artwork.width, bodySize / artwork.height)
        val width = artwork.width.toFloat()
        val height = artwork.height.toFloat()
        val travel = ((size.width - bodySize * 1.5f) / 2f).coerceAtLeast(0f)
        val center = if (playful) {
            Offset(
                x = size.width / 2f + pose.travel * travel,
                y = size.height - bodySize / 2f - 3.dp.toPx() - pose.hop * 6.dp.toPx(),
            )
        } else {
            Offset(size.width / 2f, size.height / 2f)
        }

        clipRect {
            withTransform({
                translate(center.x, center.y)
                rotate(pose.rotation, pivot = Offset.Zero)
                scale(scale * (1f + pose.squash), scale * (1f - pose.squash), pivot = Offset.Zero)
                translate(-width / 2f, -height / 2f)
            }) {
                drawImage(artwork)

                MascotEyes.forEachIndexed { index, eye ->
                    // A shared gaze plus a small inward bias gives the original mischievous look.
                    val inward = if (index == 0) 0.010f else -0.010f
                    val pupil = Offset(
                        x = (eye.x + inward + sin(orbit) * 0.024f) * width,
                        y = (eye.y + sin(orbit * 2f) * 0.012f) * height,
                    )
                    val radiusX = width * 0.0165f
                    val radiusY = height * 0.0195f
                    clipPath(eyeClips[index]) {
                        // Only replace the eye interior; the user's fur, outline, and scarf stay exact.
                        drawImage(eyeArtwork)
                        drawOval(
                            color = Color(0xFF110D14),
                            topLeft = pupil - Offset(radiusX, radiusY),
                            size = Size(radiusX * 2f, radiusY * 2f),
                        )
                        drawCircle(
                            color = Color(0xFFFFFEF3),
                            radius = width * 0.0045f,
                            center = pupil + Offset(-radiusX * 0.47f, -radiusY * 0.43f),
                        )
                    }
                }
            }
        }
    }
}

private data class MascotDancePose(
    val travel: Float = 0f,
    val hop: Float = 0f,
    val rotation: Float = 0f,
    val squash: Float = 0f,
)

private fun mascotDancePose(seconds: Float): MascotDancePose {
    val part = seconds / 4f
    val phase = (part - part.toInt()) * 2f * PI.toFloat()
    // Each short routine returns to the same resting pose before the next one starts.
    return when (part.toInt()) {
        0 -> MascotDancePose(
            travel = sin(phase * 2f) * 0.12f,
            hop = abs(sin(phase * 2f)) * 0.3f,
            rotation = sin(phase * 2f) * 10f,
            squash = sin(phase * 4f) * 0.035f,
        )
        1 -> MascotDancePose(
            travel = sin(phase) * 0.38f,
            hop = sin(phase * 2f).let { it * it } * 0.85f,
            rotation = sin(phase) * 8f,
            squash = sin(phase * 4f) * 0.06f,
        )
        2 -> MascotDancePose(
            travel = sin(phase),
            hop = abs(sin(phase * 6f)) * 0.65f,
            rotation = cos(phase) * sin(phase / 2f) * 8f,
            squash = sin(phase * 12f) * 0.035f,
        )
        else -> MascotDancePose(
            travel = sin(phase * 2f) * 0.18f,
            hop = abs(sin(phase * 2f)),
            rotation = sin(phase * 3f) * 14f,
            squash = sin(phase * 4f) * 0.05f,
        )
    }
}

private data class AnimatedMascotArt(val artwork: ImageBitmap, val eyes: ImageBitmap)

private object AnimatedMascotArtCache {
    @Volatile
    var current: AnimatedMascotArt? = null
        private set

    private val mutex = Mutex()

    suspend fun load(resources: Resources): AnimatedMascotArt = current ?: mutex.withLock {
        current ?: withContext(Dispatchers.Default) {
            // 314 px comfortably covers the 18–32 dp loading icons, without UI-thread decoding.
            fun decode(id: Int): ImageBitmap {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 4
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                return checkNotNull(BitmapFactory.decodeResource(resources, id, options)).asImageBitmap()
            }

            val artwork = decode(R.drawable.mascot_art)
            val eyes = decode(R.drawable.mascot_eye_base)
            check(artwork.width == eyes.width && artwork.height == eyes.height)
            AnimatedMascotArt(artwork, eyes)
        }.also { current = it }
    }
}

private data class MascotEye(val x: Float, val y: Float, val radiusX: Float, val radiusY: Float)

// Normalized positions keep the pupils aligned as the icon changes display size.
private val MascotEyes = listOf(
    MascotEye(x = 0.436603f, y = 0.482456f, radiusX = 0.061005f, radiusY = 0.067783f),
    MascotEye(x = 0.566587f, y = 0.482855f, radiusX = 0.061802f, radiusY = 0.068979f),
)
