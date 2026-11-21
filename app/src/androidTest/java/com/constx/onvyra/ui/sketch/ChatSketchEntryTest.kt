package com.constx.onvyra.ui.sketch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constx.onvyra.R
import com.constx.onvyra.RouteActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min
import me.rerere.ui.R as SketchR

/** Exercises the real chat attachment entry in the isolated instrumentation app. */
@RunWith(AndroidJUnit4::class)
class ChatSketchEntryTest {
    @get:Rule
    val compose = createAndroidComposeRule<RouteActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun allowNotificationPrompt() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Test
    fun sketchReplacesAttachmentSheetAndDiscardReturnsDirectlyToChat() {
        val moreOptions = context.getString(R.string.more_options)
        val sketch = context.getString(R.string.sketch)
        val undo = compose.onNodeWithContentDescription(context.getString(SketchR.string.sketch_dialog_undo))
        compose.waitUntil(timeoutMillis = 15_000L) {
            compose.onAllNodesWithContentDescription(moreOptions).fetchSemanticsNodes().size == 1
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithContentDescription(moreOptions).assertIsDisplayed().performClick()
        compose.onNodeWithText(sketch).assertIsDisplayed()
        // Material3 ModalBottomSheet and SketchDialog both expose semantics.dialog().
        compose.onAllNodes(isDialog()).assertCountEquals(1)

        val openedAt = SystemClock.elapsedRealtimeNanos()
        compose.onNodeWithText(sketch).performClick()
        undo.assertIsDisplayed().assertIsNotEnabled()
        // The attachment sheet must be removed rather than kept below the new dialog.
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText(sketch).assertDoesNotExist()
        compose.onNode(isDialog()).performTouchInput {
            val halfLength = min(width, height) * 0.15f
            swipe(
                start = center - Offset(halfLength, halfLength / 2),
                end = center + Offset(halfLength, halfLength / 2),
                durationMillis = 240L,
            )
        }
        compose.waitUntil(timeoutMillis = 10_000L) {
            isEnabled().matches(undo.fetchSemanticsNode())
        }
        undo.assertIsEnabled()
        val firstStrokeCommittedMs = (SystemClock.elapsedRealtimeNanos() - openedAt) / 1_000_000
        Log.i("ChatSketchEntryTest", "firstStrokeCommittedMs=$firstStrokeCommittedMs")

        compose.onNodeWithContentDescription(context.getString(android.R.string.cancel)).performClick()
        compose.onNodeWithText(context.getString(SketchR.string.sketch_dialog_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()

        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithContentDescription(moreOptions).assertIsDisplayed()
        compose.onNodeWithText(sketch).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.take_picture)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.upload_file)).assertDoesNotExist()
    }
}
