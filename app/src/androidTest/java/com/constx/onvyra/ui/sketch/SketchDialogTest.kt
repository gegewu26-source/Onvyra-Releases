package com.constx.onvyra.ui.sketch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ui.R
import me.rerere.ui.sketch.SketchDialog
import me.rerere.ui.sketch.SketchResult
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matcher
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.test.espresso.matcher.RootMatchers.isDialog as isAndroidDialog

@RunWith(AndroidJUnit4::class)
class SketchDialogTest {
    @get:Rule
    val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun firstStrokeIsVisibleBeforeFingerUpWithoutSelectingBrushWidthOnEveryOpening() {
        val exported = setUpHost()
        val undo = compose.onNodeWithContentDescription(context.getString(R.string.sketch_dialog_undo))

        repeat(3) { opening ->
            exported.set(null)
            compose.onNodeWithText("Open sketch").performClick()
            val dialog = compose.onNode(isDialog()).assertIsDisplayed()
            undo.assertIsNotEnabled()
            val bounds = dialog.fetchSemanticsNode().boundsInWindow
            val halfLength = min(bounds.width, bounds.height) * 0.15f
            val center = Offset(bounds.width / 2f, bounds.height / 2f)
            val start = center - Offset(halfLength, halfLength / 2)
            val end = center + Offset(halfLength, halfLength / 2)
            val windowOrigin = bounds.topLeft
            val radius = (halfLength * 0.2f).coerceAtLeast(6f)
            val blankPoint = windowOrigin + center + Offset(0f, halfLength * 1.5f)

            // Validate the image source first: a black emulator screenshot must never count as ink.
            // No toolbar control is touched between opening the blank sheet and the first DOWN.
            captureDialogBitmap().let { before ->
                try {
                    assertTrue(
                        "Opening ${opening + 1} did not expose a white blank canvas",
                        before.whiteFractionAlong(start + windowOrigin, end + windowOrigin, radius) > 0.98f,
                    )
                } finally {
                    before.recycle()
                }
            }

            dialog.performTouchInput {
                down(start)
                moveTo(end, delayMillis = 240L)
            }
            try {
                compose.waitForIdle()
                // The finger is still down here. Undo/export alone can only prove completed ink;
                // inspect the actual dialog drawing to prove the first in-progress stroke is visible.
                captureDialogBitmap().let { during ->
                    try {
                        assertTrue(
                            "Opening ${opening + 1} hid the first stroke until finger-up or a toolbar click",
                            during.darkPixelsAlong(start + windowOrigin, end + windowOrigin, radius) >= 20,
                        )
                        assertTrue(
                            "Opening ${opening + 1} captured an invalid or obscured canvas",
                            during.whiteFractionAlong(blankPoint, blankPoint, radius) > 0.98f,
                        )
                    } finally {
                        during.recycle()
                    }
                }
            } finally {
                dialog.performTouchInput { up() }
            }
            compose.waitUntil(timeoutMillis = 10_000L) {
                isEnabled().matches(undo.fetchSemanticsNode())
            }
            confirmAndAssertExportHasInk(exported, "First wet stroke on opening ${opening + 1}")
        }
    }

    @Test
    fun firstStrokeCanBeUndoneRedoneAndExportedOnEveryOpening() {
        val exported = setUpHost()
        val undo = compose.onNodeWithContentDescription(context.getString(R.string.sketch_dialog_undo))
        val redo = compose.onNodeWithContentDescription(context.getString(R.string.sketch_dialog_redo))
        val clear = compose.onNodeWithContentDescription(context.getString(R.string.sketch_dialog_clear))
        val confirm = compose.onNodeWithContentDescription(context.getString(android.R.string.ok))

        repeat(3) { opening ->
            exported.set(null)
            val openedAt = SystemClock.elapsedRealtimeNanos()
            compose.onNodeWithText("Open sketch").performClick()
            compose.onNode(isDialog()).assertIsDisplayed()
            // A newly opened dialog must start with a blank sheet, including after a previous export.
            undo.assertIsNotEnabled()
            redo.assertIsNotEnabled()
            clear.assertIsNotEnabled()
            confirm.assertIsNotEnabled()

            // Send the first finger stroke as soon as the dialog is displayed. There is deliberately
            // no startup sleep: a stroke dropped while the authoring view initializes must fail.
            drawStroke()
            val firstStrokeCommittedMs = (SystemClock.elapsedRealtimeNanos() - openedAt) / 1_000_000
            Log.i("SketchDialogTest", "opening=${opening + 1} firstStrokeCommittedMs=$firstStrokeCommittedMs")
            confirm.assertIsEnabled()
            clear.assertIsEnabled()
            redo.assertIsNotEnabled()

            undo.performClick()
            undo.assertIsNotEnabled()
            redo.assertIsEnabled()
            clear.assertIsNotEnabled()
            confirm.assertIsNotEnabled()

            redo.performClick()
            undo.assertIsEnabled()
            redo.assertIsNotEnabled()
            clear.assertIsEnabled()
            confirm.assertIsEnabled()

            confirmAndAssertExportHasInk(exported, "Opening ${opening + 1}")
        }
    }

    @Test
    fun cancelledStrokeDisappearsAndNextStrokeStillDraws() {
        val exported = setUpHost()
        compose.onNodeWithText("Open sketch").performClick()
        val dialog = compose.onNode(isDialog()).assertIsDisplayed()
        val bounds = dialog.fetchSemanticsNode().boundsInWindow
        val halfLength = min(bounds.width, bounds.height) * 0.15f
        val center = Offset(bounds.width / 2f, bounds.height / 2f)
        val start = center - Offset(halfLength, halfLength / 2)
        val end = center + Offset(halfLength, halfLength / 2)
        val radius = (halfLength * 0.2f).coerceAtLeast(6f)
        val windowStart = start + bounds.topLeft
        val windowEnd = end + bounds.topLeft
        val undo = compose.onNodeWithContentDescription(context.getString(R.string.sketch_dialog_undo))
        val confirm = compose.onNodeWithContentDescription(context.getString(android.R.string.ok))

        dialog.performTouchInput {
            down(start)
            moveTo(end, delayMillis = 240L)
        }
        try {
            captureDialogBitmap().let { during ->
                try {
                    assertTrue("The stroke never became visible before cancellation",
                        during.darkPixelsAlong(windowStart, windowEnd, radius) >= 20)
                } finally {
                    during.recycle()
                }
            }
        } finally {
            // Generates an actual ACTION_CANCEL through the same window as the finger events.
            dialog.performTouchInput { cancel() }
        }
        undo.assertIsNotEnabled()
        confirm.assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.sketch_dialog_clear)).assertIsNotEnabled()
        captureDialogBitmap().let { afterCancel ->
            try {
                assertTrue("Cancelled ink remained on the canvas",
                    afterCancel.whiteFractionAlong(windowStart, windowEnd, radius) > 0.98f)
            } finally {
                afterCancel.recycle()
            }
        }

        // A cancelled input stream must not leave pointer capture or the drawing model stuck.
        drawStroke()
        undo.assertIsEnabled()
        confirmAndAssertExportHasInk(exported, "Stroke after ACTION_CANCEL")
    }

    @Test
    fun twoFingersProduceExactlyTwoIndividuallyUndoableStrokes() {
        val exported = setUpHost()
        compose.onNodeWithText("Open sketch").performClick()
        val dialog = compose.onNode(isDialog()).assertIsDisplayed()
        val undo = compose.onNodeWithContentDescription(context.getString(R.string.sketch_dialog_undo))
        val redo = compose.onNodeWithContentDescription(context.getString(R.string.sketch_dialog_redo))
        val confirm = compose.onNodeWithContentDescription(context.getString(android.R.string.ok))
        undo.assertIsNotEnabled()

        dialog.performTouchInput {
            val halfLength = min(width, height) * 0.15f
            val separation = halfLength * 0.5f
            down(0, center + Offset(-halfLength, -separation))
            down(1, center + Offset(-halfLength, separation))
            updatePointerTo(0, center + Offset(halfLength, -separation))
            updatePointerTo(1, center + Offset(halfLength, separation))
            move(delayMillis = 240L)
            // The first pointer finishes while the second is still held against the canvas.
            up(0)
        }
        try {
            undo.assertIsEnabled()
            confirm.assertIsEnabled()
        } finally {
            dialog.performTouchInput { up(1) }
        }
        undo.assertIsEnabled().performClick()
        undo.assertIsEnabled()
        confirm.assertIsEnabled()
        undo.performClick()
        undo.assertIsNotEnabled()
        confirm.assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.sketch_dialog_clear)).assertIsNotEnabled()

        redo.assertIsEnabled().performClick()
        redo.assertIsEnabled().performClick()
        redo.assertIsNotEnabled()
        confirmAndAssertExportHasInk(exported, "Two individually completed finger strokes")
    }

    @Test
    fun everyBrushCanDrawAndExportThroughItsPublicMenu() {
        val exported = setUpHost()
        val pen = context.getString(R.string.sketch_dialog_brush_pen)
        val brushLabels = listOf(
            R.string.sketch_dialog_brush_pen,
            R.string.sketch_dialog_brush_marker,
            R.string.sketch_dialog_brush_flat,
            R.string.sketch_dialog_brush_calligraphy,
            R.string.sketch_dialog_brush_highlighter,
            R.string.sketch_dialog_brush_dashed,
        )
        brushLabels.forEach { labelRes ->
            exported.set(null)
            val label = context.getString(labelRes)
            compose.onNodeWithText("Open sketch").performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.sketch_dialog_undo)).assertIsNotEnabled()
            // Every opening starts with Pen; choose through the same menu used by a person.
            compose.onNodeWithContentDescription(pen).performClick()
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
            drawStroke()
            confirmAndAssertExportHasInk(exported, label)
        }
    }

    private fun setUpHost(): AtomicReference<SketchResult?> {
        val open = mutableStateOf(false)
        val exported = AtomicReference<SketchResult?>()
        compose.setContent {
            MaterialExpressiveTheme {
                Button(onClick = { open.value = true }) { Text("Open sketch") }
                if (open.value) {
                    SketchDialog(
                        onDismiss = { open.value = false },
                        onConfirm = {
                            exported.set(it)
                            open.value = false
                        },
                        aspectRatio = 1f,
                    )
                }
            }
        }
        return exported
    }

    private fun drawStroke() {
        compose.onNode(isDialog()).performTouchInput {
            val halfLength = min(width, height) * 0.15f
            swipe(
                start = center - Offset(halfLength, halfLength / 2),
                end = center + Offset(halfLength, halfLength / 2),
                durationMillis = 240L,
            )
        }
        // Ink commits asynchronously after finger-up. Observe the public Undo button rather
        // than reaching into SketchState or waiting an arbitrary number of frames.
        compose.waitUntil(timeoutMillis = 10_000L) {
            isEnabled().matches(
                compose.onNodeWithContentDescription(context.getString(R.string.sketch_dialog_undo)).fetchSemanticsNode()
            )
        }
    }

    private fun confirmAndAssertExportHasInk(exported: AtomicReference<SketchResult?>, label: String) {
        compose.onNodeWithContentDescription(context.getString(android.R.string.ok)).assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 10_000L) { exported.get() != null }
        compose.onNode(isDialog()).assertDoesNotExist()
        val bitmap = requireNotNull(exported.get()).bitmap
        try {
            assertTrue("$label exported an empty image", bitmap.hasNonWhitePixel())
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureDialogBitmap(): Bitmap {
        var captured: Bitmap? = null
        // Render the real dialog DecorView rather than SketchState or an export. This also works
        // on headless ATD images whose UiAutomation screenshots are entirely black. The standard
        // Compose Canvas renders both completed and in-progress ink into this same View hierarchy.
        onView(isRoot()).inRoot(isAndroidDialog()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun getDescription(): String = "capture the visible sketch dialog drawing"
            override fun perform(uiController: UiController, view: View) {
                check(view.width > 0 && view.height > 0) { "The dialog has no drawable bounds" }
                captured = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                    view.draw(Canvas(it))
                }
            }
        })
        return requireNotNull(captured)
    }

    private fun Bitmap.whiteFractionAlong(start: Offset, end: Offset, radius: Float): Float {
        var white = 0
        var total = 0
        forEachPixelAlong(start, end, radius) { pixel ->
            total++
            if (Color.alpha(pixel) >= 250 && Color.red(pixel) >= 245 &&
                Color.green(pixel) >= 245 && Color.blue(pixel) >= 245
            ) white++
        }
        check(total > 0) { "The expected canvas area is outside the captured dialog" }
        return white.toFloat() / total
    }

    private fun Bitmap.darkPixelsAlong(start: Offset, end: Offset, radius: Float): Int {
        var dark = 0
        forEachPixelAlong(start, end, radius) { pixel ->
            if (Color.alpha(pixel) >= 250 && Color.red(pixel) < 225 &&
                Color.green(pixel) < 225 && Color.blue(pixel) < 225
            ) dark++
        }
        return dark
    }

    private inline fun Bitmap.forEachPixelAlong(
        start: Offset,
        end: Offset,
        radius: Float,
        block: (Int) -> Unit,
    ) {
        val delta = end - start
        val lengthSquared = delta.x * delta.x + delta.y * delta.y
        val left = (minOf(start.x, end.x) - radius).roundToInt().coerceAtLeast(0)
        val top = (minOf(start.y, end.y) - radius).roundToInt().coerceAtLeast(0)
        val right = (maxOf(start.x, end.x) + radius).roundToInt().coerceAtMost(width - 1)
        val bottom = (maxOf(start.y, end.y) + radius).roundToInt().coerceAtMost(height - 1)
        for (y in top..bottom) {
            for (x in left..right) {
                val relative = Offset(x.toFloat(), y.toFloat()) - start
                val fraction = if (lengthSquared == 0f) 0f else {
                    ((relative.x * delta.x + relative.y * delta.y) / lengthSquared).coerceIn(0f, 1f)
                }
                val nearest = start + delta * fraction
                val dx = x - nearest.x
                val dy = y - nearest.y
                if (dx * dx + dy * dy <= radius * radius) block(getPixel(x, y))
            }
        }
    }

    private fun Bitmap.hasNonWhitePixel(): Boolean {
        val row = IntArray(width)
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            if (row.any { pixel -> Color.alpha(pixel) > 0 && pixel != Color.WHITE }) return true
        }
        return false
    }
}
