package me.rerere.androidcontrol

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.graphics.BitmapFactory
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.rerere.androidcontrol.accessibility.AndroidControlAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the dedicated emulator fixture APKs and its explicitly started UID-2000 helper. */
@RunWith(AndroidJUnit4::class)
class AndroidControlEndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun nativeSelectorsAndBatchOperateWithoutAgentScreenshotsAndExplicitVisualFallbackStillCaptures() = runBlocking {
        assertEmulator()
        val service=awaitService()
        val control = AndroidControl.get(instrumentation.targetContext)
        withTimeout(15_000) { while (control.bridge.shell.value == null) delay(100) }
        val oldEnabled=control.enabled;val oldPreview=control.previewEnabled
        val owner="native-first-${UUID.randomUUID()}"
        try {
            control.enabled=true;control.previewEnabled=false
            control.setApprovalMode(owner,AndroidControlApprovalMode.FULL_ACCESS)
            val captures=control.state()["agent_screenshot_count"]!!.jsonPrimitive.long
            val ocr=control.state()["local_ocr_count"]!!.jsonPrimitive.long
            val nativeCaptures=service.agentScreenshotRequestCount()
            assertOkay(control.execute("open_app",json {put("package_name",FIXTURE_PACKAGE)},owner))
            val native=awaitTree(control,owner){nodes(it).any {it.resourceEndsWith("input_editor")}}
            val editor=nodes(native).single { it.resourceEndsWith("input_editor") }
            val editorResource=requireNotNull(editor.string("resourceId"))
            val applyLabel=requireNotNull(nodes(native).single { it.resourceEndsWith("apply_button") }.string("text"))
            assertEquals("accessibility",native.string("observation_method"))
            assertTrue(native["native_actionable_nodes"]!!.jsonPrimitive.int>0)
            val redundant=control.execute("screenshot",json {},owner)
            assertEquals("native_controls_available_use_nodes",redundant.string("error"))
            val batch=control.execute("run_steps",json {
                put("steps",JsonArray(listOf(
                    json {put("tool","input_text");put("args",json {put("resource_id",editorResource);put("text","无障碍优先")})},
                    json {put("tool","tap");put("args",json {put("target_text",applyLabel)});put("expect","Applied: 无障碍优先");put("timeout_ms",2000)}
                )))
            },owner)
            if(batch["ok"]?.jsonPrimitive?.booleanOrNull!=true) {
                val diagnostic=screen(control.execute("get_ui_tree",json {},owner))
                println("PHONE_NATIVE_SELECTOR_DIAGNOSTIC="+nodes(diagnostic).filter { it.resourceEndsWith("input_editor") || it.resourceEndsWith("apply_button") }.map {node->
                    JsonObject(node.filterKeys {it in setOf("id","type","resourceId","text","hint","enabled","visible","editable","clickable")})
                })
            }
            assertOkay(batch)
            val stepResults=(batch["steps"] as JsonArray).map { (it as JsonObject)["result"] as JsonObject }
            assertEquals(listOf("accessibility_set_text","accessibility_node"),stepResults.map {it.string("execution_method")})
            assertTrue(nodes(screen(batch)).any {it.string("text")=="Applied: 无障碍优先"})
            assertTrue(batch["elapsed_ms"]!!.jsonPrimitive.long>=0)
            val partial=control.execute("run_steps",json {
                put("steps",JsonArray(listOf(
                    json {put("tool","input_text");put("args",json {put("resource_id",editorResource);put("text","已完成的第一步")})},
                    json {put("tool","tap");put("args",json {put("target_text","NOT_A_REAL_FIXTURE_CONTROL")})},
                    json {put("tool","tap");put("args",json {put("target_text",applyLabel)})}
                )))
            },owner)
            assertFalse(partial["ok"]!!.jsonPrimitive.booleanOrNull==true)
            assertEquals(1,partial["stopped_at"]!!.jsonPrimitive.int)
            assertEquals(2,(partial["steps"] as JsonArray).size)
            assertEquals("stale_or_unknown_node_id",partial.string("error"))
            assertEquals("accessibility",(partial["screen"] as JsonObject).string("observation_method"))
            assertTrue(nodes(partial["screen"] as JsonObject).any {it.string("text")=="已完成的第一步"})
            val beforeScroll=screen(control.execute("get_ui_tree",json {},owner))
            val scrollResource=requireNotNull(nodes(beforeScroll).single {it.resourceEndsWith("scroll_container")}.string("resourceId"))
            val scroll=control.execute("swipe",json {put("resource_id",scrollResource);put("direction","down")},owner)
            assertOkay(scroll);assertEquals("accessibility_scroll",scroll.string("execution_method"))
            assertEquals(captures,control.state()["agent_screenshot_count"]!!.jsonPrimitive.long)
            assertEquals(ocr,control.state()["local_ocr_count"]!!.jsonPrimitive.long)
            assertEquals("The actual Accessibility screenshot API must remain unused",nativeCaptures,service.agentScreenshotRequestCount())
            val visual=control.execute("screenshot",json {put("fallback_reason","user_requested")},owner)
            assertOkay(visual);assertEquals("visual_screenshot",visual.string("execution_method"))
            assertTrue(File(requireNotNull(visual.string("screenshot_path"))).length()>0)
            assertEquals(captures+1,control.state()["agent_screenshot_count"]!!.jsonPrimitive.long)
            assertEquals(nativeCaptures+1,service.agentScreenshotRequestCount())
            println("PHONE_NATIVE_FIRST=PASS; node_text_scroll=true; local_batch=true; agent_screenshots_before_fallback=0; local_ocr=0; explicit_fallback=1; batch_elapsed_ms=${batch["elapsed_ms"]}")
        } finally {control.stopAll();control.previewEnabled=oldPreview;control.enabled=oldEnabled}
    }

    @Test fun canvasPopupUsesTheScreenshotFrameForOcrTextAndImageCoordinatesWithoutResendingUnchangedPixels() = runBlocking {
        assertEmulator()
        awaitService()
        val control = AndroidControl.get(instrumentation.targetContext)
        withTimeout(15_000) { while (control.bridge.shell.value == null) delay(100) }
        val oldEnabled = control.enabled
        val oldPreview = control.previewEnabled
        val owner = "canvas-popup-${UUID.randomUUID()}"
        var phase = "open canvas"
        try {
            control.enabled = true
            control.previewEnabled = false
            control.setApprovalMode(owner, AndroidControlApprovalMode.FULL_ACCESS)
            val opened = control.execute("open_app", json { put("package_name", FIXTURE_PACKAGE) }, owner)
            assertOkay(opened)
            val displayId = requireNotNull(opened["display_id"]).jsonPrimitive.int
            suspend fun launchPopup() {
                fun topApplicationWindow(tree: JsonObject): JsonObject? =
                    (tree["windows"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                        ?.firstOrNull { it["type"]?.jsonPrimitive?.int == 1 }
                val previousTree = screen(control.execute("get_ui_tree", json {}, owner))
                val previousWindowId = requireNotNull(topApplicationWindow(previousTree)?.get("id"))
                // Clear the old native fixture instead of leaving native buttons behind a dialog.
                labShell("am start --display $displayId -f 0x10008000 -n $FIXTURE_PACKAGE/.CanvasPopupFixtureActivity")
                val native = awaitTree(control, owner) { tree ->
                    val top = topApplicationWindow(tree)
                    top != null && top["id"] != previousWindowId &&
                        nodes(json { put("windows", JsonArray(listOf(top))) })
                            .any { it.string("description") == "Canvas popup surface" }
                }
                assertEquals("The painted buttons must have no native click targets", 0,
                    requireNotNull(native["native_actionable_nodes"]).jsonPrimitive.int)
                assertTrue("The action labels must exist only as pixels", nodes(native).none {
                    it.string("text")?.contains("继续游戏") == true || it.string("text") == "确定"
                })
            }
            suspend fun capture(): JsonObject {
                val result = control.execute("screenshot", json { put("fallback_reason", "visual_content") }, owner)
                assertOkay(result)
                assertTrue("A screenshot must expose OCR from the same returned frame", ocrNodes(screen(result)).isNotEmpty())
                return result
            }
            fun actionLabel(frame: JsonObject, chinese: String, english: String): JsonObject =
                ocrNodes(screen(frame)).firstOrNull { it.string("text").orEmpty().contains(chinese) }
                    ?: ocrNodes(screen(frame)).firstOrNull { it.string("text").orEmpty().trim().equals(english,ignoreCase=true) }
                    ?: error("The actual OCR did not recognize either painted button: ${ocrNodes(screen(frame))}")
            fun status(frame: JsonObject, expected: String) {
                val labels = ocrNodes(screen(frame)).joinToString(" ") { it.string("text").orEmpty() }
                assertTrue("Expected a real painted status $expected, observed=$labels", labels.replace(Regex("\\s+"), "").contains(expected.replace(" ", "")))
            }
            launchPopup()
            phase = "local-only painted lobby controls"
            val screenshotsBeforeLocal = control.state()["agent_screenshot_count"]!!.jsonPrimitive.long
            val localReadsBefore = control.state()["local_frame_capture_count"]!!.jsonPrimitive.long
            val local = control.execute("get_ui_tree",json {put("visual_fallback",true)},owner)
            assertOkay(local)
            status(local,"CANVAS WAITING")
            assertNull("A local tree fallback must not export a picture",local.string("screenshot_path"))
            val localAfter = control.state()["local_frame_capture_count"]!!.jsonPrimitive.long
            assertTrue("Canvas labels need actual on-device pixels",localAfter>localReadsBefore)
            val localRepeated = control.execute("get_ui_tree",json {put("visual_fallback",true)},owner)
            assertOkay(localRepeated)
            assertEquals("An unchanged local tree must reuse its recognition",localAfter,
                control.state()["local_frame_capture_count"]!!.jsonPrimitive.long)
            val localResume = actionLabel(local,"继续游戏","RESUME")
            assertOkay(control.execute("tap",json {put("target_text",localResume.string("text")!!)},owner))
            val localPlaying = control.execute("get_ui_tree",json {put("visual_fallback",true)},owner)
            assertOkay(localPlaying)
            status(localPlaying,"CANVAS PLAYING")
            assertNull(localPlaying.string("screenshot_path"))
            assertEquals("Painted lobby text must work without the Agent screenshot tool",screenshotsBeforeLocal,
                control.state()["agent_screenshot_count"]!!.jsonPrimitive.long)
            println("PHONE_CANVAS_LOCAL_TREE=PASS; native_click_targets=0; actual_painted_click=true; exported_images=0; same_tree_reused=true")
            launchPopup()
            val captures = control.state()["agent_screenshot_count"]!!.jsonPrimitive.long
            val recognitions = control.state()["local_ocr_count"]!!.jsonPrimitive.long
            phase = "first pixels and OCR"
            val first = capture()
            status(first, "CANVAS WAITING")
            val firstId = requireNotNull(first.string("frame_id"))
            val firstPath = requireNotNull(first.string("screenshot_path"))
            assertTrue(File(firstPath).length() > 0)
            val capturesAfterFirst = control.state()["agent_screenshot_count"]!!.jsonPrimitive.long
            assertTrue(capturesAfterFirst in (captures + 1)..(captures + 4))
            val ocrAfterFirst = control.state()["local_ocr_count"]!!.jsonPrimitive.long
            assertTrue(ocrAfterFirst in (recognitions + 1)..(recognitions + 2))
            phase = "unchanged frame reuse"
            val repeat = capture()
            assertEquals(firstId, repeat.string("frame_id"))
            assertTrue(repeat["image_unchanged"]?.jsonPrimitive?.booleanOrNull == true)
            assertNull("An identical image must not be attached to the Agent again", repeat.string("screenshot_path"))
            assertEquals("A repeated read without an action should use the cached frame", capturesAfterFirst,
                control.state()["agent_screenshot_count"]!!.jsonPrimitive.long)
            assertEquals("Identical pixels must reuse their OCR", ocrAfterFirst,
                control.state()["local_ocr_count"]!!.jsonPrimitive.long)
            phase = "painted resume target_text"
            val resume = actionLabel(first, "继续游戏", "RESUME")
            assertEquals("local_ocr", resume.string("source"))
            val resumed = control.execute("tap", json { put("target_text", requireNotNull(resume.string("text")));put("observe_after",false) }, owner)
            assertOkay(resumed)
            assertEquals("display_scoped_gesture", resumed.string("execution_method"))
            val playing = capture()
            assertNotEquals("Touching a painted button must create a different frame", firstId, playing.string("frame_id"))
            status(playing, "CANVAS PLAYING")
            phase = "new popup image origin"
            launchPopup()
            val popup = capture()
            status(popup, "CANVAS WAITING")
            val origin = requireNotNull(popup["image_origin"] as? JsonArray).map { it.jsonPrimitive.int }
            assertEquals(2, origin.size)
            assertTrue("Inset fixture must exercise a nonzero window image origin: $origin", origin.any { it > 0 })
            val confirm = actionLabel(popup, "确定", "CONFIRM")
            val bounds = requireNotNull(confirm["bounds"] as? JsonArray).map { it.jsonPrimitive.int }
            val imageX = (((bounds[0] + bounds[2]) / 2 - origin[0]).toLong() * popup["image_width"]!!.jsonPrimitive.int /
                popup["display_image_width"]!!.jsonPrimitive.int).toInt()
            val imageY = (((bounds[1] + bounds[3]) / 2 - origin[1]).toLong() * popup["image_height"]!!.jsonPrimitive.int /
                popup["display_image_height"]!!.jsonPrimitive.int).toInt()
            assertTrue(imageX in 0 until requireNotNull(popup["image_width"]).jsonPrimitive.int)
            assertTrue(imageY in 0 until requireNotNull(popup["image_height"]).jsonPrimitive.int)
            phase = "frame-bound image coordinate tap"
            val confirmed = control.execute("tap", json {
                put("x", imageX); put("y", imageY)
                put("coordinate_space", "image"); put("frame_id", requireNotNull(popup.string("frame_id")))
                put("observe_after",false)
            }, owner)
            assertOkay(confirmed)
            assertEquals("display_scoped_gesture", confirmed.string("execution_method"))
            val confirmedFrame = capture()
            status(confirmedFrame, "CANVAS CONFIRMED")
            status(confirmedFrame, "MISSES 0")
            phase = "unchanged actions pause input while screenshots remain readable"
            repeat(2) { attempt ->
                val label = actionLabel(confirmedFrame, "确定", "CONFIRM").string("text")!!
                assertOkay(control.execute("tap",json {put("target_text",label);put("observe_after",false)},owner))
                val unchanged = control.execute("screenshot",json {put("fallback_reason","visual_content")},owner)
                assertTrue(unchanged["image_unchanged"]!!.jsonPrimitive.booleanOrNull == true)
                assertNull(unchanged.string("screenshot_path"))
                assertEquals("no_visible_change",unchanged.string("action_effect"))
                assertOkay(unchanged)
                assertNull(unchanged["manual_takeover_required"])
                if (attempt == 1) {
                    assertTrue(unchanged["visual_actions_paused"]!!.jsonPrimitive.booleanOrNull == true)
                }
            }
            val blocked = control.execute("tap",json {put("target_text",actionLabel(confirmedFrame,"确定","CONFIRM").string("text")!!)},owner)
            assertEquals("visual_no_progress_use_user_takeover",blocked.string("error"))
            assertOkay(control.execute("screenshot",json {put("fallback_reason","user_requested")},owner))
            phase = "fast visual action returns its next frame without cold OCR or a separate tool call"
            launchPopup()
            val ocrBeforeFast = control.state()["local_ocr_count"]!!.jsonPrimitive.long
            val fast = control.execute("screenshot",json {put("fallback_reason","visual_content");put("include_ocr",false)},owner)
            assertOkay(fast)
            assertEquals("image/jpeg",fast.string("mime_type"))
            assertFalse(fast["ocr_enabled"]!!.jsonPrimitive.booleanOrNull == true)
            val fastPath = File(requireNotNull(fast.string("screenshot_path")))
            val decoded = BitmapFactory.decodeFile(fastPath.absolutePath)
            try {
                assertEquals(fast["image_width"]!!.jsonPrimitive.int,decoded.width)
                assertEquals(fast["image_height"]!!.jsonPrimitive.int,decoded.height)
                assertTrue(maxOf(decoded.width,decoded.height)<=1280)
            } finally {decoded.recycle()}
            val fastX=(((bounds[0]+bounds[2])/2-origin[0]).toLong()*fast["image_width"]!!.jsonPrimitive.int /
                fast["display_image_width"]!!.jsonPrimitive.int).toInt()
            val fastY=(((bounds[1]+bounds[3])/2-origin[1]).toLong()*fast["image_height"]!!.jsonPrimitive.int /
                fast["display_image_height"]!!.jsonPrimitive.int).toInt()
            val autoObserved = control.execute("tap",json {
                put("x",fastX);put("y",fastY);put("coordinate_space","image");put("frame_id",fast.string("frame_id")!!)
            },owner)
            assertOkay(autoObserved)
            assertTrue(autoObserved["action_observation"]!!.jsonPrimitive.booleanOrNull == true)
            assertTrue(autoObserved["input_accepted"]!!.jsonPrimitive.booleanOrNull == true)
            assertNotEquals(fast.string("frame_id"),autoObserved.string("frame_id"))
            assertNotNull(autoObserved.string("screenshot_path"))
            assertEquals("Fast visual path must not trigger local inference",ocrBeforeFast,
                control.state()["local_ocr_count"]!!.jsonPrimitive.long)
            println("PHONE_VISUAL_FAST_PATH=PASS; auto_action_observation=true; image_mime=jpeg; longest_side_max=1280; local_ocr_calls=0; image_bytes=${fast["image_bytes"]}; capture_timings=${fast["local_timings_ms"]}; action_timings=${autoObserved["local_timings_ms"]}")
            println("PHONE_CANVAS_FRAME_OCR=PASS; native_click_targets=0; observed_target_text=true; unchanged_image_resent=false; unchanged_ocr_reused=true; nonzero_image_origin=$origin; frame_bound_image_tap=true; canvas_touch_readback=true")
        } catch (failure: Throwable) {
            throw AssertionError("Canvas popup phase=$phase state=${control.state()}", failure)
        } finally {
            control.stopAll()
            control.previewEnabled = oldPreview
            control.enabled = oldEnabled
            labShell("am force-stop $FIXTURE_PACKAGE")
        }
    }

    private fun ocrNodes(tree: JsonObject): List<JsonObject> =
        (tree["ocr"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    @Test fun userCloseStopsTheBoundRunButToolStopDoesNotCancelTheAgent() = runBlocking {
        assertEmulator()
        awaitService()
        val control = AndroidControl.get(instrumentation.targetContext)
        withTimeout(15_000) { while (control.bridge.shell.value == null) delay(100) }
        val enabled = control.enabled
        val preview = control.previewEnabled
        val owner = "user-close-${UUID.randomUUID()}"
        val oldRun = java.util.concurrent.atomic.AtomicInteger()
        val newRun = java.util.concurrent.atomic.AtomicInteger()
        val toolStopped = java.util.concurrent.atomic.AtomicInteger()
        try {
            control.enabled = true
            control.previewEnabled = false
            control.setApprovalMode(owner, AndroidControlApprovalMode.FULL_ACCESS)
            val opened = control.execute("open_app", json { put("package_name", FIXTURE_PACKAGE) }, owner,
                onUserStop = { oldRun.incrementAndGet() })
            assertOkay(opened)
            val displays = instrumentation.targetContext.getSystemService(DisplayManager::class.java)
            val mainMetrics = DisplayMetrics()
            val taskMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            displays.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(mainMetrics)
            @Suppress("DEPRECATION")
            requireNotNull(displays.getDisplay(requireNotNull(opened["display_id"]).jsonPrimitive.int))
                .getRealMetrics(taskMetrics)
            val expected = floatingDisplayGeometry(mainMetrics.widthPixels, mainMetrics.heightPixels, mainMetrics.densityDpi)
            assertEquals(expected.width, taskMetrics.widthPixels)
            assertEquals(expected.height, taskMetrics.heightPixels)
            assertEquals(expected.dpi, taskMetrics.densityDpi)
            println("PHONE_DISPLAY_PROFILE=PASS; width=${taskMetrics.widthPixels}; height=${taskMetrics.heightPixels}; dpi=${taskMetrics.densityDpi}; native_small_window_ratio=true")
            assertOkay(control.execute("get_ui_tree", json {}, owner,
                onUserStop = { newRun.incrementAndGet() }))
            control.requestStop()
            assertEquals("User close must synchronously reach the run currently using this display",1,newRun.get())
            assertEquals("A replaced run must not receive a later close",0,oldRun.get())
            control.requestStop()
            assertEquals("Double close must be idempotent",1,newRun.get())
            withTimeout(5000) { while (control.state()["task_id"] != null) delay(25) }
            // Reopen immediately: a delayed removeTask process cleanup must not
            // kill the next task's freshly launched activity.
            repeat(3) {
                assertOkay(control.execute("open_app", json { put("package_name", FIXTURE_PACKAGE) }, owner,
                    onUserStop = { toolStopped.incrementAndGet() }))
                assertOkay(control.execute("stop_task", json {}, owner))
            }
            assertEquals("An Agent may stop its display then finish its answer normally",0,toolStopped.get())
            println("PHONE_USER_STOP_BINDING=PASS; latest_run_only=true; immediate=true; idempotent=true; immediate_reopen_cycles=3; tool_stop_does_not_cancel_agent=true")
        } finally {
            control.stopAll()
            control.previewEnabled = preview
            control.enabled = enabled
        }
    }

    @Test fun userPermissionModeControlsRealMutationsWithoutBlockingStop() = runBlocking {
        assertEmulator()
        val service = awaitService()
        val control = AndroidControl.get(instrumentation.targetContext)
        withTimeout(15_000) { while (control.bridge.shell.value == null) delay(100) }
        val previousEnabled = control.enabled
        val previousPreview = control.previewEnabled
        val owner = "permission-${UUID.randomUUID()}"
        val open = json { put("package_name", FIXTURE_PACKAGE) }
        try {
            control.enabled = true
            control.previewEnabled = false
            control.setApprovalMode(owner, AndroidControlApprovalMode.CONFIRM_CHANGES)
            val approval = requireNotNull(control.approvalFingerprint("open_app", open, owner))
            assertEquals("confirmation_required_retry_single_action", control.execute("open_app", open, owner).string("error"))
            assertFalse("A denied open must not create a display", control.hasActiveSession())
            assertOkay(control.execute("open_app", open, owner, approval))
            val tree = awaitTree(control, owner) { nodes(it).any { node -> node.resourceEndsWith("apply_button") } }
            val button = nodes(tree).first { it.resourceEndsWith("apply_button") }
            val idTap = json { put("node_id", requireNotNull(button.string("id"))) }
            val bounds = button["bounds"] as JsonArray
            val coordinateTap = json {
                put("x", (bounds[0].jsonPrimitive.int + bounds[2].jsonPrimitive.int) / 2)
                put("y", (bounds[1].jsonPrimitive.int + bounds[3].jsonPrimitive.int) / 2)
            }
            control.setApprovalMode(owner, AndroidControlApprovalMode.AUTONOMOUS)
            assertNull(control.approvalFingerprint("tap", idTap, owner))
            assertNotNull(control.approvalFingerprint("tap", coordinateTap, owner))
            assertEquals("confirmation_required_retry_single_action", control.execute("tap", coordinateTap, owner).string("error"))
            control.setApprovalMode(owner, AndroidControlApprovalMode.FULL_ACCESS)
            // A stale generation snapshot must not undo the new explicit choice.
            control.registerApprovalMode(owner, AndroidControlApprovalMode.AUTONOMOUS)
            assertNull(control.approvalFingerprint("tap", coordinateTap, owner))
            assertOkay(control.execute("tap", coordinateTap, owner))
            control.setApprovalMode(owner, AndroidControlApprovalMode.CONFIRM_CHANGES)
            assertNotNull(control.approvalFingerprint("tap", idTap, owner))
            assertEquals("confirmation_required_retry_single_action", control.execute("tap", idTap, owner).string("error"))
            assertOkay(control.execute("stop_task", json {}, owner))
            assertNull(control.state()["task_id"])
        } finally {
            control.stopAll()
            control.previewEnabled = previousPreview
            control.enabled = previousEnabled
        }
    }

    @Test fun stopDuringDisplayCreationCancelsThePendingOwnerWithoutOpeningTheApp() = runBlocking {
        assertEmulator()
        val control = AndroidControl.get(instrumentation.targetContext)
        val service = awaitService()
        val actual = withTimeout(15_000) {
            while (control.bridge.shell.value == null) delay(100)
            requireNotNull(control.bridge.shell.value)
        }
        val previousEnabled = control.enabled
        val owner = "creating-${UUID.randomUUID()}"
        val mainBefore = mainDisplayResumedActivity()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val continueCreation = java.util.concurrent.CountDownLatch(1)
        val createdDisplay = java.util.concurrent.atomic.AtomicInteger(-1)
        // Test-only wrapper delays the real UID-2000 Binder call before it creates its display.
        // asBinder() remains the actual authenticated Binder, so no disconnect is fabricated.
        val hub = me.rerere.androidcontrol.shell.AndroidShellHub.of(instrumentation.targetContext)
        val field = hub.javaClass.getDeclaredField("mutableShell").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val shellState = field.get(hub) as kotlinx.coroutines.flow.MutableStateFlow<me.rerere.androidcontrol.shell.IAndroidShell?>
        val wrapped = java.lang.reflect.Proxy.newProxyInstance(actual.javaClass.classLoader,
            arrayOf(me.rerere.androidcontrol.shell.IAndroidShell::class.java)) { proxy, method, args ->
            if (method.name == "equals") proxy === args?.firstOrNull()
            else if (method.name == "hashCode") System.identityHashCode(proxy)
            else if (method.name == "toString") "DelayedCreationTestShell"
            else if (method.name == "createDisplay") {
                entered.complete(Unit)
                check(continueCreation.await(5, java.util.concurrent.TimeUnit.SECONDS))
                (method.invoke(actual, *(args ?: emptyArray())) as Int).also(createdDisplay::set)
            } else method.invoke(actual, *(args ?: emptyArray()))
        } as me.rerere.androidcontrol.shell.IAndroidShell
        try {
            control.enabled = true
            shellState.value = wrapped
            val opened = async(kotlinx.coroutines.Dispatchers.IO) {
                control.execute("open_app", json { put("package_name", FIXTURE_PACKAGE) }, owner)
            }
            withTimeout(3_000) { entered.await() }
            val denied = control.execute("stop_task", json {}, "another-conversation")
            assertEquals("task_owned_by_another_conversation", denied.string("error"))
            val epoch = control.javaClass.getDeclaredField("epoch").apply {isAccessible=true}
                .get(control) as java.util.concurrent.atomic.AtomicLong
            val beforeStop = epoch.get()
            val stopped = async(kotlinx.coroutines.Dispatchers.IO) {control.execute("stop_task", json {}, owner)}
            withTimeout(2_000) {while(epoch.get()==beforeStop) delay(10)}
            continueCreation.countDown()
            assertEquals("task_stopped", withTimeout(3_000) {opened.await()}.string("error"))
            assertOkay(withTimeout(3_000) {stopped.await()})
            assertTrue(createdDisplay.get()>0)
            assertNull(control.state()["task_id"])
            assertTrue((service.tree(createdDisplay.get())["windows"] as JsonArray).isEmpty())
            assertEquals(mainBefore,mainDisplayResumedActivity())
        } finally {
            continueCreation.countDown()
            shellState.value=actual
            control.stopAll()
            control.enabled=previousEnabled
        }
    }

    @Test fun realSecondaryDisplayAcceptsChineseTextClicksAndScrollsWhileTheMainScreenStaysUntouched() = runBlocking {
        assertEmulator()
        val control = AndroidControl.get(instrumentation.targetContext)
        val service = awaitService()
        val shell = withTimeout(15_000) {
            while (control.bridge.shell.value == null) delay(100)
            requireNotNull(control.bridge.shell.value)
        }
        assertEquals("The test must exercise the actual shell helper, without root", 2000, shell.identity()[0])
        assertFalse("Preview output must reject the user's main display", shell.setPreviewSurface(0, null))
        assertFalse("Preview output must reject an unowned display", shell.setPreviewSurface(Int.MAX_VALUE, null))
        val rejected = instrumentation.targetContext.contentResolver.call(
            Uri.parse("content://${instrumentation.targetContext.packageName}.android-control-shell"), "attach", null, Bundle(),
        )
        assertEquals("An ordinary app UID must not be accepted as a shell helper", "NotShell", rejected?.getString("reason"))
        assertSame("A rejected handoff must not replace the already authenticated helper", shell, control.bridge.shell.value)
        val owner = "fixture-${UUID.randomUUID()}"
        val previousEnabled = control.enabled
        val previousPreview = control.previewEnabled
        val mainBefore = service.tree(0)
        assertMainMarker(mainBefore)
        val mainPackage = mainBefore["packageName"]
        val mainResumed = mainDisplayResumedActivity()
        control.enabled = true
        var displayId = -1
        try {
            // The test owns the preview requirement, independently of saved lab preferences.
            control.previewEnabled = true
            val opened = control.execute("open_app", json {
                put("app_name", "Control Test Fixture")
                put("width", 720); put("height", 1_280); put("dpi", 240)
            }, owner)
            assertOkay(opened)
            displayId = requireNotNull(opened["display_id"]).jsonPrimitive.int
            assertTrue("Agent work must run on a secondary display", displayId > 0)
            val initial = awaitTree(control, owner) { nodes(it).any { node -> node.resourceEndsWith("input_editor") } }
            assertEquals(FIXTURE_PACKAGE, initial.string("packageName"))
            withTimeout(8_000) { while (!control.previewState.value.nativeStreaming) { println("NATIVE_PREVIEW_WAIT="+control.previewState.value.copy(bitmap=null)); delay(500) } }
            println("NATIVE_PREVIEW_STATIC="+control.previewState.value.copy(bitmap=null))
            val staticPreview = control.previewState.value
            if (staticPreview.renderedFrames == 0L) {
                assertNotNull("A static application must retain its protected first-frame poster until native output arrives", staticPreview.bitmap)
                assertTrue("The initial poster stays bounded", staticPreview.bitmap!!.width <= 360)
            }
            assertEquals("The visible preview must belong to this task's actual display", displayId, control.previewState.value.displayId)
            assertTrue(control.previewState.value.visible)
            val animate = nodes(initial).single { it.resourceEndsWith("animate_preview_button") }
            assertOkay(control.execute("tap", json { put("node_id", requireNotNull(animate.string("id"))) }, owner))
            withTimeout(8_000) { while (control.previewState.value.renderedFrames < 2) { println("NATIVE_PREVIEW_ANIMATED="+control.previewState.value.copy(bitmap=null)); delay(500) } }
            assertNull("Native preview must not transport each video frame through CPU bitmaps", control.previewState.value.bitmap)
            delay(300)
            val framesBefore = control.previewState.value.renderedFrames
            val sampleStarted = SystemClock.elapsedRealtime()
            delay(2_000)
            val received = control.previewState.value.renderedFrames - framesBefore
            val sampleMs = SystemClock.elapsedRealtime() - sampleStarted
            println("ANDROID_CONTROL_NATIVE_PREVIEW_FRAMES=$received; SAMPLE_MS=$sampleMs")
            assertTrue("Actual VD-to-TextureView delivery must exceed the old two-screenshot-per-second transport: $received frames / $sampleMs ms", received >= 20)
            assertMainMarker(service.tree(0))
            assertEquals(mainResumed, mainDisplayResumedActivity())
            val animated = awaitTree(control, owner) { nodes(it).any { node -> node.resourceEndsWith("animate_preview_button") } }
            val stopAnimation = nodes(animated).single { it.resourceEndsWith("animate_preview_button") }
            assertOkay(control.execute("tap", json { put("node_id", requireNotNull(stopAnimation.string("id"))) }, owner))
            assertFalse(initial.toString().contains(FIXTURE_SECRET))
            val editor = nodes(initial).single { it.resourceEndsWith("input_editor") }
            val editorId = requireNotNull(editor.string("id"))
            val reread = screen(control.execute("get_ui_tree", json {}, owner))
            assertEquals("An unchanged field must keep its short reference", editorId,
                nodes(reread).single { it.resourceEndsWith("input_editor") }.string("id"))

            val text = "中文长文本与🙂输入验证，保留换行。\n".repeat(300)
            assertTrue("This case must exceed the compact tree's text limit", text.length > 4_000)
            assertOkay(control.execute("input_text", json { put("node_id", editorId); put("text", text) }, owner))
            val typed = awaitTree(control, owner) { tree ->
                nodes(tree).any { node -> node.resourceEndsWith("input_editor") &&
                    node.string("text")?.let { it.length >= 1_000 && text.startsWith(it) } == true }
            }
            assertTrue("Full readback must include the part omitted from the compact tree", service.textMatches(displayId, editorId, text))
            assertFalse("A shortened prefix must not count as complete input", service.textMatches(displayId, editorId, text.dropLast(1)))
            val beforeClick = requireNotNull(typed["revision"]).jsonPrimitive.long
            val apply = nodes(typed).single { it.resourceEndsWith("apply_button") }
            assertOkay(control.execute("tap", json { put("node_id", requireNotNull(apply.string("id"))) }, owner))
            assertTrue("A real view click must publish a change on that display", service.awaitChange(displayId, beforeClick, 3_000))
            assertOkay(control.execute("wait_for", json { put("expect", "Applied: "); put("timeout_ms", 5_000) }, owner))
            val applied = awaitTree(control, owner) { tree ->
                nodes(tree).any { node -> node.resourceEndsWith("result_text") &&
                    node.string("text")?.let { it.length >= 1_000 && "Applied: $text".startsWith(it) } == true }
            }
            assertFalse(applied.toString().contains(FIXTURE_SECRET))

            var reachedBottom = false
            repeat(16) {
                if (!reachedBottom) {
                    // ACTION_SCROLL_FORWARD can return while its ScrollView is still animating.
                    // Bounds are intentionally part of a node's identity: choose a fresh reference
                    // only after this display's events settle, rather than acting on a moving one.
                    val current = awaitSettledTree(control, owner, service, displayId)
                    val target = nodes(current).firstOrNull { it.resourceEndsWith("bottom_target") && it.visible() }
                    if (target != null) {
                        assertOkay(control.execute("tap", json { put("node_id", requireNotNull(target.string("id"))) }, owner))
                        reachedBottom = true
                    } else {
                        val scroll = nodes(current).first { it.resourceEndsWith("scroll_container") }
                        assertOkay(control.execute("swipe", json { put("node_id", requireNotNull(scroll.string("id"))); put("direction", "down") }, owner))
                    }
                }
            }
            assertTrue("The actual ScrollView must reveal its offscreen target", reachedBottom)
            assertOkay(control.execute("wait_for", json { put("expect", "Bottom reached"); put("timeout_ms", 5_000) }, owner))
            val screenshot = control.execute("screenshot", json { put("fallback_reason", "user_requested") }, owner)
            assertOkay(screenshot)
            val screenshotPath = requireNotNull(screenshot.string("screenshot_path"))
            println("ANDROID_CONTROL_SECONDARY_SCREENSHOT=$screenshotPath")
            val bitmap = BitmapFactory.decodeFile(screenshotPath)
            assertNotNull("The actual screenshot tool must save a readable secondary-display PNG", bitmap)
            bitmap!!.let {
                try { assertEquals(720, it.width); assertEquals(1_280, it.height) }
                finally { it.recycle() }
            }
            assertEquals(mainPackage, service.tree(0)["packageName"])
            assertMainMarker(service.tree(0))
            assertEquals("The user's main-display activity must keep focus", mainResumed, mainDisplayResumedActivity())

            var secureButton: JsonObject? = null
            repeat(8) {
                if (secureButton == null) {
                    val tree = awaitSettledTree(control, owner, service, displayId)
                    secureButton = nodes(tree).firstOrNull { it.resourceEndsWith("show_password_button") && it.visible() }
                    if (secureButton == null) {
                        val scroll = nodes(tree).first { it.resourceEndsWith("scroll_container") }
                        assertOkay(control.execute("swipe", json { put("node_id", requireNotNull(scroll.string("id"))); put("direction", "down") }, owner))
                    }
                }
            }
            assertNotNull("The fixture must expose its isolated password case only after ordinary actions finish", secureButton)
            assertOkay(control.execute("tap", json { put("node_id", requireNotNull(secureButton!!.string("id"))) }, owner))
            val protectedTree = awaitTree(control, owner) { tree ->
                nodes(tree).any { it.resourceEndsWith("password_editor") && it.visible() }
            }
            val password = nodes(protectedTree).single { it.resourceEndsWith("password_editor") }
            assertFalse(protectedTree.toString().contains(FIXTURE_SECRET))
            assertTrue(password["password"]?.jsonPrimitive?.booleanOrNull == true)
            assertNull(password["text"])
            assertNull(password["description"])
            assertNull(password["hint"])
            val passwordId = requireNotNull(password.string("id"))
            assertFalse("Password fields must not accept text through the ordinary input path",
                service.perform(displayId, passwordId, "set_text", "must-not-write"))
            val takeover = control.execute("input_text", json { put("node_id", passwordId); put("text", "must-not-write") }, owner)
            assertEquals("user_takeover_required", takeover.string("error"))
            delay(1_100)
            assertNull("A FLAG_SECURE window must not be captured through another display or a fallback API", service.screenshot(displayId))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertEquals(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW, service.lastScreenshotError(displayId))
            }
            withTimeout(3_000) { while (control.previewState.value.bitmap != null || control.previewState.value.nativeStreaming) delay(50) }
            val protectedPreview = withTimeout(3_000) {
                var capture = service.screenshotPreviewWithWindow(displayId)
                while (capture.skipped) { delay(100); capture = service.screenshotPreviewWithWindow(displayId) }
                capture
            }
            assertNull("Optional preview must never use the raw sink to capture protected content", protectedPreview.capture)
            assertEquals(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW, protectedPreview.error)
            assertEquals("Preview failures must not overwrite the Agent's screenshot error", AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW, service.lastScreenshotError(displayId))

            assertOkay(control.execute("stop_task", json {}, owner))
            withTimeout(2_000) { while (control.previewState.value.displayId != null) delay(50) }
            assertFalse("A released task must not be rebound by a delayed preview callback", shell.setPreviewSurface(displayId, null))
            withTimeout(5_000) { while ((service.tree(displayId)["windows"] as JsonArray).isNotEmpty()) delay(100) }
            assertFalse("Retired references must not act on another window", service.perform(displayId, editorId, "click"))
            delay(1_050)
            assertNull("A released display must not fall back to a main-screen screenshot", service.screenshot(displayId))
            assertMainMarker(service.tree(0))
            assertEquals(mainResumed, mainDisplayResumedActivity())
        } finally {
            runCatching { control.execute("stop_task", json {}, owner) }
            control.previewEnabled = previousPreview
            control.enabled = previousEnabled
        }
    }

    @Test fun nodeReferencesAndChangeWaitsNeverCrossDisplayBoundaries() = runBlocking {
        assertEmulator()
        val control = AndroidControl.get(instrumentation.targetContext)
        val service = awaitService()
        withTimeout(15_000) { while (control.bridge.shell.value == null) delay(100) }
        assertEquals(2000, requireNotNull(control.bridge.shell.value).identity()[0])
        val owner = "isolation-${UUID.randomUUID()}"
        val previousEnabled = control.enabled
        assertMainMarker(service.tree(0))
        control.enabled = true
        try {
            val opened = control.execute("open_app", json { put("package_name", FIXTURE_PACKAGE) }, owner)
            assertOkay(opened)
            val displayId = requireNotNull(opened["display_id"]).jsonPrimitive.int
            val tree = awaitTree(control, owner) { nodes(it).any { node -> node.resourceEndsWith("apply_button") } }
            val buttonId = requireNotNull(nodes(tree).single { it.resourceEndsWith("apply_button") }.string("id"))
            assertFalse("A secondary-display node ID must never act on the main display", service.perform(0, buttonId, "click"))
            assertFalse("An absent display must never borrow the main display's root", service.perform(999_999, buttonId, "click"))
            assertTrue((service.tree(999_999)["windows"] as JsonArray).isEmpty())
            val unaffected = service.revision(999_999)
            assertTrue(service.perform(displayId, buttonId, "click"))
            assertFalse("An event on the fixture display must not wake another display's waiter", service.awaitChange(999_999, unaffected, 150))
            assertMainMarker(service.tree(0))
        } finally {
            runCatching { control.execute("stop_task", json {}, owner) }
            control.enabled = previousEnabled
        }
    }

    @Test fun stoppingASixtySecondWaitPromptlyReleasesItsDisplayAndDoesNotTouchTheMainScreen() = runBlocking {
        assertEmulator()
        val control = AndroidControl.get(instrumentation.targetContext)
        val service = awaitService()
        withTimeout(15_000) { while (control.bridge.shell.value == null) delay(100) }
        assertEquals(2000, requireNotNull(control.bridge.shell.value).identity()[0])
        val owner = "stop-${UUID.randomUUID()}"
        val previousEnabled = control.enabled
        assertMainMarker(service.tree(0))
        val mainResumed = mainDisplayResumedActivity()
        control.enabled = true
        try {
            val opened = control.execute("open_app", json { put("package_name", FIXTURE_PACKAGE) }, owner)
            assertOkay(opened)
            val displayId = requireNotNull(opened["display_id"]).jsonPrimitive.int
            awaitTree(control, owner) { it.string("packageName") == FIXTURE_PACKAGE }
            val waiting = async {
                control.execute("wait_for", json {
                    put("expect", "this-fixture-state-will-never-appear-${UUID.randomUUID()}")
                    put("timeout_ms", 60_000)
                }, owner)
            }
            delay(300)
            assertFalse("The condition wait must be active before testing STOP", waiting.isCompleted)
            val started = SystemClock.elapsedRealtime()
            assertOkay(control.execute("stop_task", json {}, owner))
            val stoppedAfter = SystemClock.elapsedRealtime() - started
            assertTrue("STOP must interrupt the long wait within two seconds (was ${stoppedAfter}ms)", stoppedAfter < 2_000L)
            val interrupted = withTimeout(2_000) { waiting.await() }
            assertEquals("task_stopped", interrupted.string("error"))
            withTimeout(5_000) { while ((service.tree(displayId)["windows"] as JsonArray).isNotEmpty()) delay(100) }
            assertMainMarker(service.tree(0))
            assertEquals(mainResumed, mainDisplayResumedActivity())
        } finally {
            runCatching { control.execute("stop_task", json {}, owner) }
            control.enabled = previousEnabled
        }
    }

    @Test fun nativeTouchLongPressSliderDragAndABatchUseTheSecondaryDisplayAndCoordinateApprovalIsEnforced() = runBlocking {
        assertEmulator()
        val control = AndroidControl.get(instrumentation.targetContext)
        val service = awaitService()
        withTimeout(15_000) { while (control.bridge.shell.value == null) delay(100) }
        val shell = requireNotNull(control.bridge.shell.value)
        assertEquals(2000, shell.identity()[0])
        val owner = "native-${UUID.randomUUID()}"
        val previousEnabled = control.enabled
        val previousConfirmSensitive = control.confirmSensitive
        assertMainMarker(service.tree(0))
        val mainResumed = mainDisplayResumedActivity()
        // Only this isolated fixture test may bypass interactive approval while exercising raw touch.
        control.enabled = true
        control.confirmSensitive = false
        try {
            val opened = control.execute("open_app", json {
                put("package_name", FIXTURE_PACKAGE)
                put("width", 720); put("height", 1_280); put("dpi", 240)
            }, owner)
            assertOkay(opened)
            val displayId = requireNotNull(opened["display_id"]).jsonPrimitive.int
            val initial = awaitTree(control, owner) { nodes(it).any { node -> node.resourceEndsWith("drag_slider") && node.visible() } }
            val editorId = requireNotNull(nodes(initial).single { it.resourceEndsWith("input_editor") }.string("id"))
            assertOkay(control.execute("input_text", json { put("node_id", editorId); put("text", "native-touch") }, owner))

            val beforeTap = screen(control.execute("get_ui_tree", json {}, owner))
            val apply = nodes(beforeTap).single { it.resourceEndsWith("apply_button") }
            val applyBounds = requireNotNull(apply["bounds"] as? JsonArray).map { it.jsonPrimitive.int }
            val applyX = (applyBounds[0] + applyBounds[2]) / 2
            val applyY = (applyBounds[1] + applyBounds[3]) / 2
            assertOkay(control.execute("tap", json { put("x", applyX); put("y", applyY) }, owner))
            assertOkay(control.execute("wait_for", json { put("expect", "Applied: native-touch"); put("timeout_ms", 5_000) }, owner))
            assertOkay(control.execute("tap", json { put("x", applyX); put("y", applyY); put("duration_ms", 900) }, owner))
            assertOkay(control.execute("wait_for", json { put("expect", "Long pressed"); put("timeout_ms", 5_000) }, owner))

            val beforeDrag = screen(control.execute("get_ui_tree", json {}, owner))
            val slider = nodes(beforeDrag).single { it.resourceEndsWith("drag_slider") && it.visible() }
            val sliderBounds = requireNotNull(slider["bounds"] as? JsonArray).map { it.jsonPrimitive.int }
            val sliderWidth = sliderBounds[2] - sliderBounds[0]
            val sliderY = (sliderBounds[1] + sliderBounds[3]) / 2
            assertOkay(control.execute("swipe", json {
                put("x1", sliderBounds[0] + sliderWidth / 12); put("y1", sliderY)
                put("x2", sliderBounds[2] - sliderWidth / 12); put("y2", sliderY)
                put("duration_ms", 650)
            }, owner))
            val dragged = awaitTree(control, owner) { tree ->
                nodes(tree).any { node -> node.resourceEndsWith("result_text") &&
                    node.string("text")?.removePrefix("Slider: ")?.toIntOrNull()?.let { it > 70 } == true }
            }
            val sliderResult = nodes(dragged).single { it.resourceEndsWith("result_text") }.string("text")
            assertTrue("The fixture must observe a real fromUser SeekBar change, not an accessibility-only action",
                sliderResult?.removePrefix("Slider: ")?.toIntOrNull()?.let { it > 70 } == true)

            val currentEditor = requireNotNull(nodes(dragged).single { it.resourceEndsWith("input_editor") }.string("id"))
            val currentApply = requireNotNull(nodes(dragged).single { it.resourceEndsWith("apply_button") }.string("id"))
            val batch = control.execute("run_steps", json {
                put("steps", JsonArray(listOf(
                    json {
                        put("tool", "input_text")
                        put("args", json { put("node_id", currentEditor); put("text", "batch-touch") })
                        put("expect", "batch-touch")
                    },
                    json {
                        put("tool", "tap")
                        put("args", json { put("node_id", currentApply) })
                        put("expect", "Applied: batch-touch")
                    },
                    json { put("tool", "get_ui_tree"); put("args", json {}); put("expect_app", FIXTURE_PACKAGE) },
                )))
            }, owner)
            assertOkay(batch)
            val steps = requireNotNull(batch["steps"] as? JsonArray)
            assertEquals(3, steps.size)
            steps.forEach { step -> assertOkay(requireNotNull((step as JsonObject)["result"] as? JsonObject)) }
            assertTrue(nodes(screen(batch)).any { it.resourceEndsWith("result_text") && it.string("text") == "Applied: batch-touch" })

            assertFalse("The shell must refuse to inject a pointer into the user's main display", shell.injectTap(0, applyX, applyY))
            assertOkay(control.execute("press_key", json { put("key", "SEARCH") }, owner))
            assertMainMarker(service.tree(0))
            assertEquals(mainResumed, mainDisplayResumedActivity())

            control.confirmSensitive = true
            val beforeDenied = screen(control.execute("get_ui_tree", json {}, owner))
            val currentResult = nodes(beforeDenied).single { it.resourceEndsWith("result_text") }.string("text")
            val denied = control.execute("tap", json { put("x", applyX); put("y", applyY); put("duration_ms", 900) }, owner)
            assertEquals("confirmation_required_retry_single_action", denied.string("error"))
            val afterDenied = screen(control.execute("get_ui_tree", json {}, owner))
            assertEquals("An unapproved coordinate action must not reach the fixture", currentResult,
                nodes(afterDenied).single { it.resourceEndsWith("result_text") }.string("text"))
            assertMainMarker(service.tree(0))
            assertEquals(mainResumed, mainDisplayResumedActivity())
            assertTrue(displayId > 0)
        } finally {
            runCatching { control.execute("stop_task", json {}, owner) }
            control.confirmSensitive = previousConfirmSensitive
            control.enabled = previousEnabled
        }
    }

    @Test fun heldDragSurvivesObservationThenReleasesAndRejectsStaleOrFailedMoves() = runBlocking {
        assertEmulator()
        val service = awaitService()
        val control = AndroidControl.get(instrumentation.targetContext)
        val shell = withTimeout(15_000) {
            while (control.bridge.shell.value == null) delay(100)
            requireNotNull(control.bridge.shell.value)
        }
        assertEquals(2000, shell.identity()[0])
        val previousEnabled = control.enabled
        val previousPreview = control.previewEnabled
        val owner = "held-drag-${UUID.randomUUID()}"
        val mainResumed = mainDisplayResumedActivity()
        val hub = me.rerere.androidcontrol.shell.AndroidShellHub.of(instrumentation.targetContext)
        val field = hub.javaClass.getDeclaredField("mutableShell").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val shellState = field.get(hub) as kotlinx.coroutines.flow.MutableStateFlow<me.rerere.androidcontrol.shell.IAndroidShell?>
        try {
            control.enabled = true
            control.previewEnabled = false
            control.setApprovalMode(owner, AndroidControlApprovalMode.FULL_ACCESS)
            val opened = control.execute("open_app", json {
                put("package_name", FIXTURE_PACKAGE)
                put("width", 720); put("height", 1_280); put("dpi", 240)
            }, owner)
            assertOkay(opened)
            val displayId = requireNotNull(opened["display_id"]).jsonPrimitive.int
            val initial = awaitTree(control, owner) { nodes(it).any { node -> node.resourceEndsWith("drag_slider") && node.visible() } }
            val slider = nodes(initial).single { it.resourceEndsWith("drag_slider") && it.visible() }
            val bounds = requireNotNull(slider["bounds"] as? JsonArray).map { it.jsonPrimitive.int }
            val startX = bounds[0] + (bounds[2] - bounds[0]) / 12
            val middleX = (bounds[0] + bounds[2]) / 2
            val endX = bounds[2] - (bounds[2] - bounds[0]) / 12
            val y = (bounds[1] + bounds[3]) / 2
            suspend fun drag(phase: String, x: Int = startX): JsonObject = control.execute("drag", json {
                put("phase", phase)
                if (phase in setOf("begin", "move")) { put("x", x); put("y", y); put("duration_ms", 240) }
            }, owner)

            val began = drag("begin")
            assertOkay(began)
            assertEquals(true, began["drag_active"]?.jsonPrimitive?.booleanOrNull)
            val observed = control.execute("get_ui_tree", json {}, owner)
            assertOkay(observed)
            assertEquals("Native observation must leave the same finger held", true,
                observed["drag_active"]?.jsonPrimitive?.booleanOrNull)
            val captured = control.execute("screenshot", json {
                put("fallback_reason", "user_requested"); put("include_ocr", false)
            }, owner)
            assertOkay(captured)
            assertEquals("A real screenshot must not synthesize UP", true,
                captured["drag_active"]?.jsonPrimitive?.booleanOrNull)
            val screenshot = BitmapFactory.decodeFile(requireNotNull(captured.string("screenshot_path")))
            assertNotNull("Observation while held must save actual readable display pixels", screenshot)
            screenshot?.recycle()
            assertEquals("A second DOWN must be rejected while observation retains the finger", 0L,
                shell.beginDrag(displayId, startX, y))
            assertOkay(drag("move", middleX))
            awaitTree(control, owner) { tree -> nodes(tree).any { node ->
                node.resourceEndsWith("result_text") && node.string("text")?.removePrefix("Slider: ")
                    ?.toIntOrNull()?.let { it in 35..65 } == true
            } }
            assertOkay(drag("move", endX))
            val ended = drag("end")
            assertOkay(ended)
            assertEquals(false, ended["drag_active"]?.jsonPrimitive?.booleanOrNull)
            awaitTree(control, owner) { tree -> nodes(tree).any { node ->
                node.resourceEndsWith("result_text") && node.string("text")?.removePrefix("Slider: ")
                    ?.toIntOrNull()?.let { it > 70 } == true
            } }

            // Exercise Binder tokens directly: an old caller cannot move or release a newer DOWN.
            val oldToken = shell.beginDrag(displayId, startX, y)
            assertTrue(oldToken > 0)
            assertTrue(shell.endDrag(displayId, oldToken, true))
            val currentToken = shell.beginDrag(displayId, startX, y)
            assertTrue(currentToken > oldToken)
            assertFalse(shell.moveDrag(displayId, oldToken, endX, y, 0))
            assertFalse(shell.endDrag(displayId, oldToken, false))
            assertTrue("Rejecting an old token must preserve the current finger", shell.moveDrag(displayId, currentToken, middleX, y, 120))
            assertTrue(shell.endDrag(displayId, currentToken, false))

            // A test-only Binder wrapper rejects MOVE after the real helper accepted DOWN.
            // All other calls, including CANCEL, still reach the authenticated live helper.
            val rejectMove = java.util.concurrent.atomic.AtomicBoolean(true)
            val wrapped = java.lang.reflect.Proxy.newProxyInstance(shell.javaClass.classLoader,
                arrayOf(me.rerere.androidcontrol.shell.IAndroidShell::class.java)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "RejectOneHeldMoveTestShell"
                    "moveDrag" -> if (rejectMove.compareAndSet(true, false)) false else method.invoke(shell, *(args ?: emptyArray()))
                    else -> method.invoke(shell, *(args ?: emptyArray()))
                }
            } as me.rerere.androidcontrol.shell.IAndroidShell
            shellState.value = wrapped
            assertOkay(drag("begin"))
            val rejected = drag("move", endX)
            assertEquals("drag_not_active_or_move_failed", rejected.string("error"))
            shellState.value = shell
            val recoveredToken = shell.beginDrag(displayId, startX, y)
            assertTrue("A rejected MOVE must CANCEL the remote finger before returning failure", recoveredToken > currentToken)
            assertTrue(shell.endDrag(displayId, recoveredToken, true))
            assertEquals("The tool must also clear its local drag token after rejection",
                "drag_not_active_or_expired", drag("end").string("error"))

            val after = screen(control.execute("get_ui_tree", json {}, owner))
            val buttonBounds = (nodes(after).single { it.resourceEndsWith("apply_button") }["bounds"] as JsonArray)
                .map { it.jsonPrimitive.int }
            assertOkay(control.execute("tap", json {
                put("x", (buttonBounds[0] + buttonBounds[2]) / 2)
                put("y", (buttonBounds[1] + buttonBounds[3]) / 2)
            }, owner))
            awaitTree(control, owner) { tree -> nodes(tree).any {
                it.resourceEndsWith("result_text") && it.string("text")?.startsWith("Applied: ") == true
            } }
            assertMainMarker(service.tree(0))
            assertEquals(mainResumed, mainDisplayResumedActivity())
            println("HELD_DRAG_LIFECYCLE=PASS; real_screenshot_while_down=true; segmented_seekbar_move=true; stale_token_rejected=true; rejected_move_cancels=true; subsequent_real_tap=true")
        } finally {
            shellState.value = shell
            control.stopAll()
            control.previewEnabled = previousPreview
            control.enabled = previousEnabled
        }
    }

    @Test fun aRealScreenshotFailureCancelsTheFingerHeldBeforeTheWindowBecameProtected() = runBlocking {
        assertEmulator()
        val service = awaitService()
        val control = AndroidControl.get(instrumentation.targetContext)
        val shell = withTimeout(15_000) {
            while (control.bridge.shell.value == null) delay(100)
            requireNotNull(control.bridge.shell.value)
        }
        val previousEnabled = control.enabled
        val previousPreview = control.previewEnabled
        val owner = "held-capture-failure-${UUID.randomUUID()}"
        try {
            control.enabled = true
            control.previewEnabled = false
            control.setApprovalMode(owner, AndroidControlApprovalMode.FULL_ACCESS)
            val opened = control.execute("open_app", json { put("package_name", FIXTURE_PACKAGE) }, owner)
            assertOkay(opened)
            val displayId = requireNotNull(opened["display_id"]).jsonPrimitive.int
            var secureButton: JsonObject? = null
            repeat(20) {
                if (secureButton == null) {
                    val tree = awaitSettledTree(control, owner, service, displayId)
                    secureButton = nodes(tree).firstOrNull { it.resourceEndsWith("show_password_button") && it.visible() }
                    if (secureButton == null) {
                        val scroll = nodes(tree).first { it.resourceEndsWith("scroll_container") }
                        assertOkay(control.execute("swipe", json {
                            put("node_id", requireNotNull(scroll.string("id"))); put("direction", "down")
                        }, owner))
                    }
                }
            }
            assertNotNull("The existing fixture must expose its capture-failure stimulus", secureButton)
            val bounds = (requireNotNull(secureButton)["bounds"] as JsonArray).map { it.jsonPrimitive.int }
            val x = (bounds[0] + bounds[2]) / 2
            val y = (bounds[1] + bounds[3]) / 2
            val began = control.execute("drag", json { put("phase", "begin"); put("x", x); put("y", y) }, owner)
            assertOkay(began)
            assertEquals(0L, shell.beginDrag(displayId, x, y))

            // A fixture-only accessibility click changes the capture policy while DOWN remains
            // active, as an app can do asynchronously. No protected-content bypass is attempted.
            val heldTree = screen(control.execute("get_ui_tree", json {}, owner))
            val currentButton = nodes(heldTree).single { it.resourceEndsWith("show_password_button") && it.visible() }
            assertTrue(service.perform(displayId, requireNotNull(currentButton.string("id")), "click"))
            awaitTree(control, owner) { tree -> nodes(tree).any { it.resourceEndsWith("password_editor") && it.visible() } }
            delay(1_100)
            val failedCapture = control.execute("screenshot", json {
                put("fallback_reason", "user_requested"); put("include_ocr", false)
            }, owner)
            assertEquals("screenshot_protected_window", failedCapture.string("error"))
            assertEquals("The screenshot failure must clear the tool's local held state", "drag_not_active_or_expired",
                control.execute("drag", json { put("phase", "end") }, owner).string("error"))
            val recovered = shell.beginDrag(displayId, x, y)
            assertTrue("The actual helper must have received CANCEL after capture failed", recovered > 0L)
            assertTrue(shell.endDrag(displayId, recovered, true))
            println("HELD_DRAG_CAPTURE_FAILURE=PASS; app_changed_capture_policy_while_down=true; failed_capture_cancels_local_and_remote_pointer=true")
        } finally {
            control.stopAll()
            control.previewEnabled = previousPreview
            control.enabled = previousEnabled
        }
    }

    @Test fun stoppingAFiveSecondNativeHoldCancelsTheInputAndReleasesItsDisplayPromptly() = runBlocking {
        assertEmulator()
        val control = AndroidControl.get(instrumentation.targetContext)
        val service = awaitService()
        withTimeout(15_000) { while (control.bridge.shell.value == null) delay(100) }
        assertEquals(2000, requireNotNull(control.bridge.shell.value).identity()[0])
        val owner = "stop-hold-${UUID.randomUUID()}"
        val previousEnabled = control.enabled
        val previousConfirmSensitive = control.confirmSensitive
        assertMainMarker(service.tree(0))
        val mainResumed = mainDisplayResumedActivity()
        control.enabled = true
        control.confirmSensitive = false
        try {
            val opened = control.execute("open_app", json { put("package_name", FIXTURE_PACKAGE) }, owner)
            assertOkay(opened)
            val displayId = requireNotNull(opened["display_id"]).jsonPrimitive.int
            val tree = awaitTree(control, owner) { nodes(it).any { node -> node.resourceEndsWith("apply_button") && node.visible() } }
            val button = nodes(tree).single { it.resourceEndsWith("apply_button") }
            val bounds = requireNotNull(button["bounds"] as? JsonArray).map { it.jsonPrimitive.int }
            val held = async {
                control.execute("tap", json {
                    put("x", (bounds[0] + bounds[2]) / 2)
                    put("y", (bounds[1] + bounds[3]) / 2)
                    put("duration_ms", 5_000)
                }, owner)
            }
            delay(200)
            assertFalse("The five-second native input must still be active before STOP", held.isCompleted)
            val started = SystemClock.elapsedRealtime()
            assertOkay(control.execute("stop_task", json {}, owner))
            val stoppedAfter = SystemClock.elapsedRealtime() - started
            assertTrue("STOP must cancel the active native input within two seconds (was ${stoppedAfter}ms)", stoppedAfter < 2_000L)
            val interrupted = withTimeout(2_000) { held.await() }
            assertFalse(interrupted["ok"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals("task_stopped", interrupted.string("error"))
            withTimeout(5_000) { while ((service.tree(displayId)["windows"] as JsonArray).isNotEmpty()) delay(100) }
            assertMainMarker(service.tree(0))
            assertEquals("Cancelling native input must leave the user's main screen untouched", mainResumed, mainDisplayResumedActivity())
        } finally {
            runCatching { control.execute("stop_task", json {}, owner) }
            control.confirmSensitive = previousConfirmSensitive
            control.enabled = previousEnabled
        }
    }

    @Test fun realAppOrientationRequestsDriveTheOwnedDisplayAndStopAllowsMainReopen() = runBlocking {
        assertEmulator()
        val service = awaitService()
        val context = instrumentation.targetContext
        val control = AndroidControl.get(context)
        withTimeout(15_000) { while (control.bridge.shell.value == null) delay(100) }
        val manager = context.getSystemService(DisplayManager::class.java)
        val mainRotation = manager.getDisplay(Display.DEFAULT_DISPLAY).rotation
        val previousEnabled = control.enabled
        val previousPreview = control.previewEnabled
        val owner = "app-request-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences("android_control_preview_layout", android.content.Context.MODE_PRIVATE)
        val savedWidth = prefs.getInt("custom_width", 0)
        val savedVersion = prefs.getInt("size_version", 0)
        prefs.edit().putInt("custom_width", 10000).putInt("size_version", 3).commit()
        var phase = "open"
        var openedDisplay = -1
        try {
            control.enabled = true
            control.previewEnabled = true
            control.setApprovalMode(owner, AndroidControlApprovalMode.FULL_ACCESS)
            val opened = control.execute("open_app", json { put("package_name", FIXTURE_PACKAGE) }, owner)
            assertOkay(opened)
            val displayId = requireNotNull(opened["display_id"]).jsonPrimitive.int
            openedDisplay = displayId
            val target = requireNotNull(manager.getDisplay(displayId))
            val physicalSize = Point().also { target.getRealSize(it) }
            withTimeout(5000) { while (control.previewState.value.windowBounds == null) delay(40) }
            val restored = requireNotNull(control.previewState.value.windowBounds)
            withTimeout(8000) {
                while (control.previewState.value.bitmap == null && !control.previewState.value.nativeStreaming) delay(40)
            }
            val mainDisplay = requireNotNull(manager.getDisplay(Display.DEFAULT_DISPLAY))
            val mainSize = Point().also { mainDisplay.getRealSize(it) }
            val mainInsets = service.createDisplayContext(mainDisplay).getSystemService(WindowManager::class.java)
                .maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val usableMainHeight = mainSize.y - mainInsets.top - mainInsets.bottom
            assertTrue("An old full-height width must start within half the usable main screen: preview=$restored main=$mainSize insets=$mainInsets",
                restored.height() <= usableMainHeight / 2 + 1)
            assertTrue("Compact startup must preserve the owned-display aspect ratio",
                kotlin.math.abs(restored.height() - restored.width() * physicalSize.y.toDouble() / physicalSize.x) <= 1)
            // Emulate an OEM ignoring app requests. The TEST NEVER issues user-rotation/lock;
            // the production helper must derive changes from the real ActivityRecord request.
            labShell("wm set-ignore-orientation-request -d $displayId true")
            labShell("am start --display $displayId -n $FIXTURE_PACKAGE/.FixedLandscapeFixtureActivity")
            suspend fun awaitOrientation(landscape: Boolean, rotation: Int? = null) {
                val reached = withTimeoutOrNull(12_000) {
                    while (true) {
                        val size = Point().also { target.getRealSize(it) }
                        val bounds = control.previewState.value.windowBounds
                        if ((size.x > size.y) == landscape && (rotation == null || target.rotation == rotation) &&
                            bounds != null && (bounds.width() > bounds.height()) == landscape) return@withTimeoutOrNull true
                        delay(60)
                    }
                } == true
                if (!reached) {
                    val diagnostic = labShell("dumpsys activity activities $FIXTURE_PACKAGE").lineSequence()
                        .filter { it.contains("Hist") || it.contains("requestedOrientation=") || it.contains("displayId=") || it.contains("mDisplayId=") }.joinToString("; ")
                    val size = Point().also { target.getRealSize(it) }
                    fail("App orientation not reached: wantLandscape=$landscape wantRotation=$rotation size=$size rotation=${target.rotation} preview=${control.previewState.value.windowBounds}; $diagnostic")
                }
                assertEquals("Background app orientation must not rotate the main phone screen", mainRotation,
                    manager.getDisplay(Display.DEFAULT_DISPLAY).rotation)
            }
            phase = "manifest landscape"
            awaitOrientation(true)
            phase = "first landscape tree"
            val portraitWidth = minOf(physicalSize.x, physicalSize.y)
            // Display/preview rotation can settle before the app's accessibility
            // layout. Also, open_app may briefly retain the preceding task's
            // landscape configuration, so physicalSize.x is not a portrait width.
            var tree = awaitTree(control, owner) { candidate ->
                val size = Point().also { target.getRealSize(it) }
                val topWindow = (candidate["windows"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { it["type"]?.jsonPrimitive?.int == 1 }
                val windowBounds = (topWindow?.get("bounds") as? JsonArray)?.map { it.jsonPrimitive.int }
                val candidateNodes = if (topWindow == null) emptyList() else
                    nodes(json { put("windows", JsonArray(listOf(topWindow))) })
                val rightBounds = candidateNodes.firstOrNull { it.resourceEndsWith("orientation_right_target") }
                    ?.get("bounds") as? JsonArray
                val center = rightBounds?.let { (it[0].jsonPrimitive.int + it[2].jsonPrimitive.int) / 2 }
                val centerY = rightBounds?.let { (it[1].jsonPrimitive.int + it[3].jsonPrimitive.int) / 2 }
                val windowSettled = windowBounds != null && windowBounds.size == 4 &&
                    windowBounds[0] >= 0 && windowBounds[1] >= 0 &&
                    windowBounds[2] <= size.x && windowBounds[3] <= size.y &&
                    windowBounds[2] - windowBounds[0] > windowBounds[3] - windowBounds[1] &&
                    center != null && center in windowBounds[0] until windowBounds[2] &&
                    centerY != null && centerY in windowBounds[1] until windowBounds[3]
                size.x > size.y && center != null && center >= portraitWidth && center < size.x &&
                    centerY != null && centerY in 0 until size.y && windowSettled &&
                    candidateNodes.any { it.string("text")?.startsWith("OWNED_ORIENTATION ${size.x}x${size.y} ") == true }
            }
            val right = nodes(tree).first { it.resourceEndsWith("orientation_right_target") }
            val bounds = right["bounds"] as JsonArray
            val x = (bounds[0].jsonPrimitive.int + bounds[2].jsonPrimitive.int) / 2
            val y = (bounds[1].jsonPrimitive.int + bounds[3].jsonPrimitive.int) / 2
            val landscapeSize = Point().also { target.getRealSize(it) }
            assertTrue("Use coordinates beyond the old portrait width: initial=$physicalSize current=$landscapeSize target=$bounds",
                x >= portraitWidth && x >= landscapeSize.x / 2 && x < landscapeSize.x && y in 0 until landscapeSize.y)
            phase = "right-half coordinate tap"
            assertOkay(control.execute("tap", json { put("x", x); put("y", y) }, owner))
            awaitTree(control, owner) { nodes(it).any { node -> node.string("text")?.contains("taps=1") == true } }
            suspend fun request(button: String, landscape: Boolean, rotation: Int? = null) {
                phase = button
                var accepted = false
                for (attempt in 0..3) {
                    tree = screen(control.execute("get_ui_tree", json {}, owner))
                    val node = nodes(tree).first { it.resourceEndsWith(button) }
                    val tapped = control.execute("tap", json { put("node_id", requireNotNull(node.string("id"))) }, owner)
                    if (tapped["ok"]?.jsonPrimitive?.booleanOrNull == true) { accepted = true; break }
                    // No action was dispatched for an expired node; refresh only
                    // this precondition failure while the rotation layout settles.
                    if (tapped.string("error") != "stale_or_unknown_node_id") assertOkay(tapped)
                    delay(250)
                }
                assertTrue("Fresh orientation button must become actionable: $button", accepted)
                awaitOrientation(landscape, rotation)
            }
            request("request_portrait", false)
            request("request_reverse", true, 3)
            request("request_landscape", true, 1)
            phase = "stop"
            assertOkay(control.execute("stop_task", json {}, owner))
            withTimeout(5000) { while (manager.getDisplay(displayId)?.isValid == true || control.previewState.value.displayId != null) delay(40) }
            // Explicit user-style reopening after STOP must create a live activity on display 0.
            phase = "main reopen"
            labShell("am start --display 0 -n $FIXTURE_PACKAGE/.FixedLandscapeFixtureActivity")
            withTimeout(8000) {
                while (!nodes(service.tree(Display.DEFAULT_DISPLAY)).any { it.string("text")?.startsWith("OWNED_ORIENTATION") == true }) delay(80)
            }
            println("OWNED_APP_REQUEST_ORIENTATION=PASS; manifest_landscape=true; runtime_portrait_and_reverse=true; right_half_input=true; compact_restored_width=true; main_unchanged_during_background=true; stop_then_main_reopen=true; test_forced_rotation=false")
        } catch (failure: Exception) {
            val status = nodes(service.tree(openedDisplay)).mapNotNull { it.string("text") }.filter { it.startsWith("OWNED_ORIENTATION") }.toList()
            throw AssertionError("Orientation/stop phase=$phase preview=${control.previewState.value.windowBounds} capture=${control.previewState.value.message} fixture=$status", failure)
        } finally {
            control.stopAll()
            control.previewEnabled = previousPreview
            control.enabled = previousEnabled
            prefs.edit().putInt("custom_width", savedWidth).putInt("size_version", savedVersion).commit()
            labShell("am force-stop $FIXTURE_PACKAGE")
            phase = "main reopen"
            labShell("am start --display 0 -n $MAIN_FIXTURE_PACKAGE/.MainFixtureActivity")
        }
    }

    private fun assertEmulator() {
        assertTrue("This test is restricted to the dedicated emulator, never the user's phone",
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
    }

    private suspend fun awaitService(): AndroidControlAccessibilityService {
        assertEmulator()
        // Connect this way before awaiting the real service: default UiAutomation suppresses it.
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertEquals("Service reconnection is permitted only on the explicitly owned lab AVD", "1", labShell("getprop ro.kernel.qemu"))
        val labAvd = InstrumentationRegistry.getArguments().getString("lab_avd_name", "StatsUsageTest")
        check(labAvd in setOf("StatsUsageTest", "CodexReaderQA", "CodexReaderBarsQA")) { "Unknown dedicated lab AVD" }
        assertEquals("Never change accessibility authorization on an arbitrary emulator or phone", labAvd, labShell("getprop ro.boot.qemu.avd_name"))
        if (AndroidControlAccessibilityService.instance == null) {
            val component = ComponentName(instrumentation.targetContext, AndroidControlAccessibilityService::class.java)
            fun enabledServices() = labShell("settings get secure enabled_accessibility_services")
                .takeUnless { it == "null" }.orEmpty().split(':').filter(String::isNotBlank)
            fun isOurs(value: String) = ComponentName.unflattenFromString(value) == component
            val enabled = enabledServices()
            val allowLabSetup = InstrumentationRegistry.getArguments().getString("allow_lab_a11y_setup") == "true"
            assertTrue("The isolated lab must explicitly allow accessibility fixture setup with -e allow_lab_a11y_setup true; " +
                "expected=${component.flattenToString()}, actual=${enabled.joinToString(":")}", allowLabSetup)
            // Instrumentation force-stops its target package at startup. Android records its bound
            // service as crashed or removes its entry. Only this explicitly authorized lab setup
            // may restore this test package's service; production code never enables accessibility.
            val remaining = enabled.filterNot(::isOurs)
            writeLabAccessibilityServices(remaining)
            labShell("settings put secure accessibility_enabled ${if (remaining.isEmpty()) 0 else 1}")
            withTimeoutOrNull(2_000) { while (AndroidControlAccessibilityService.instance != null) delay(50) }
            delay(250)
            // Keep other entries even if another lab component changed them during the reconnect.
            val restored = enabledServices().filterNot(::isOurs) + component.flattenToString()
            writeLabAccessibilityServices(restored)
            labShell("settings put secure accessibility_enabled 1")
            assertEquals("The lab component list must survive UiAutomation command argument handling",
                restored.joinToString(":"), labShell("settings get secure enabled_accessibility_services"))
        }
        val connected = withTimeoutOrNull(15_000) {
            while (AndroidControlAccessibilityService.instance == null) delay(100)
            requireNotNull(AndroidControlAccessibilityService.instance)
        }
        return connected ?: error("Accessibility fixture did not bind: enabled=" +
            labShell("settings get secure enabled_accessibility_services") + "; global=" +
            labShell("settings get secure accessibility_enabled") + "; " +
            labShell("dumpsys accessibility").lineSequence().filter {
                it.contains("Bound services:") || it.contains("Enabled services:") ||
                    it.contains("Binding services:") || it.contains("Crashed services:")
            }.joinToString(" | "))
    }

    private fun labShell(command: String): String {
        val descriptor = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
    }

    private fun writeLabAccessibilityServices(components: List<String>) {
        assertTrue("Accessibility component arguments must contain no whitespace or shell syntax",
            components.all { it.matches(Regex("[A-Za-z0-9_.$/]+")) && ComponentName.unflattenFromString(it) != null })
        if (components.isEmpty()) labShell("settings delete secure enabled_accessibility_services")
        else labShell("settings put secure enabled_accessibility_services ${components.joinToString(":")}")
    }

    private suspend fun awaitTree(control: AndroidControl, owner: String, matches: (JsonObject) -> Boolean): JsonObject = withTimeout(10_000) {
        var tree: JsonObject
        do {
            tree = screen(control.execute("get_ui_tree", json {}, owner))
            if (!matches(tree)) delay(100)
        } while (!matches(tree))
        tree
    }

    private suspend fun awaitSettledTree(
        control: AndroidControl,
        owner: String,
        service: AndroidControlAccessibilityService,
        displayId: Int,
    ): JsonObject = withTimeout(5_000) {
        var tree = screen(control.execute("get_ui_tree", json {}, owner))
        var settled: Boolean
        do {
            val revision = requireNotNull(tree["revision"]).jsonPrimitive.long
            val changed = service.awaitChange(displayId, revision, 150)
            val current = screen(control.execute("get_ui_tree", json {}, owner))
            settled = !changed && current["revision"]?.jsonPrimitive?.long == revision
            tree = current
        } while (!settled)
        tree
    }

    private fun screen(response: JsonObject): JsonObject {
        assertOkay(response)
        return response["screen"] as? JsonObject ?: error("Missing actual screen tree: $response")
    }

    private fun assertOkay(response: JsonObject) {
        assertTrue("Control operation failed: $response", response["ok"]?.jsonPrimitive?.booleanOrNull == true)
    }

    private fun assertMainMarker(tree: JsonObject) {
        assertTrue("The isolated main fixture must stay visible throughout agent operations",
            nodes(tree).any { it.string("text") == "MAIN_SCREEN_UNCHANGED" })
    }

    private fun mainDisplayResumedActivity(): String {
        val activities = labShell("dumpsys activity activities")
        val windows = labShell("dumpsys window displays")
        fun failure(message: String): Nothing {
            val diagnostic = File(instrumentation.targetContext.cacheDir,
                "android-control-main-display-${SystemClock.uptimeMillis()}.txt")
            diagnostic.writeText("ACTIVITY DUMP\n$activities\n\nWINDOW DUMP\n$windows")
            println("ANDROID_CONTROL_MAIN_DISPLAY_DIAGNOSTIC=${diagnostic.absolutePath}")
            throw AssertionError("$message; full activity/window dump: ${diagnostic.absolutePath}")
        }
        val section = Regex("(?m)^[ \\t]*Display #0[^\\n]*\\n([\\s\\S]*?)(?=^[ \\t]*Display #\\d+|\\z)")
            .find(activities)?.value ?: failure("No main-display activity section in dumpsys")
        // ResumedActivity summaries are emitted after all displays. Read the actual ActivityRecord
        // inside Display #0 instead, and require its own state to remain RESUMED.
        val headers = Regex("(?m)^([ \\t]*)\\* Hist[^\\n]*?(ActivityRecord\\{[^\\n]+\\})[ \\t]*$")
            .findAll(section).toList()
        val header = headers.firstOrNull { it.groupValues[2].contains("$MAIN_FIXTURE_PACKAGE/") }
            ?: failure("The main fixture ActivityRecord disappeared from Display #0")
        val indent = header.groupValues[1].length
        val recordBody = section.substring(header.range.last + 1).lineSequence().takeWhile { line ->
            line.isBlank() || line.takeWhile { it == ' ' || it == '\t' }.length > indent
        }.joinToString("\n")
        if (!Regex("(?:^|\\s)state=RESUMED(?:\\s|$)").containsMatchIn(recordBody)) {
            failure("The main fixture ActivityRecord is no longer RESUMED: ${header.groupValues[2]}")
        }
        val displayWindows = Regex("(?m)^[ \\t]*Display: mDisplayId=0[^\\n]*\\n([\\s\\S]*?)(?=^[ \\t]*Display: mDisplayId=\\d+|\\z)")
            .find(windows)?.value ?: failure("No main-display window section in dumpsys")
        val focusedWindow = displayWindows.lineSequence().firstOrNull { it.trimStart().startsWith("mCurrentFocus=") }
            ?.trim() ?: failure("No main-display focused window in dumpsys")
        val focusedApp = displayWindows.lineSequence().firstOrNull { it.trimStart().startsWith("mFocusedApp=") }
            ?.trim() ?: failure("No main-display focused app in dumpsys")
        if (!focusedWindow.contains("$MAIN_FIXTURE_PACKAGE/") || !focusedApp.contains("$MAIN_FIXTURE_PACKAGE/")) {
            failure("The main fixture lost its display's window focus: $focusedWindow; $focusedApp")
        }
        return "${header.groupValues[2]} | $focusedWindow | $focusedApp"
    }

    private fun nodes(tree: JsonObject): List<JsonObject> = buildList {
        fun walk(node: JsonObject) {
            add(node)
            (node["children"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::walk) }
        }
        (tree["windows"] as? JsonArray)?.forEach { window -> ((window as? JsonObject)?.get("root") as? JsonObject)?.let(::walk) }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
    private fun JsonObject.resourceEndsWith(name: String) = string("resourceId")?.endsWith(":id/$name") == true
    private fun JsonObject.visible() = get("visible")?.jsonPrimitive?.booleanOrNull == true
    private fun json(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    companion object {
        private const val FIXTURE_PACKAGE = "me.rerere.androidcontrol.fixture"
        private const val MAIN_FIXTURE_PACKAGE = "me.rerere.androidcontrol.mainfixture"
        private const val FIXTURE_SECRET = "fixture-secret-never-export"
    }
}
