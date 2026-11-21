package com.constx.onvyra.data.ai

import com.constx.onvyra.data.ai.tools.*
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.model.AndroidControlPermissionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class NativeImageRequestTest {
    @Test fun directImageWaitsBeforeBillingAndResumesTheExactApprovedCall() = runBlocking {
        var calls = 0
        val native = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = {
            calls++
            assertEquals("original prompt", it.jsonObject["prompt"]!!.jsonPrimitive.content)
            listOf(UIMessagePart.Image("file:///fixture/image.png"))
        })
        val pending = executeNativeImageRequest("original prompt", native, null,
            permissionMode = { AndroidControlPermissionMode.CONFIRM_CHANGES }) {}
        val call = pending.filterIsInstance<UIMessagePart.Tool>().single()
        assertTrue(call.isPending)
        assertEquals(0, calls)
        val resumed = executeNativeImageRequest("different reconstructed prompt", native, null,
            permissionMode = { AndroidControlPermissionMode.CONFIRM_CHANGES },
            resuming = listOf(call.copy(approvalState = ToolApprovalState.Approved))) {}
        assertEquals(1, calls)
        assertEquals(call.toolCallId, resumed.filterIsInstance<UIMessagePart.Tool>().single().toolCallId)
    }

    @Test fun denyingDirectImageNeverBillsNativeOrFallback() = runBlocking {
        var calls = 0
        val native = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = { calls++; emptyList() })
        val fallback = Tool(GENERATE_MEDIA_TOOL, "fixture", execute = { calls++; emptyList() })
        val pending = executeNativeImageRequest("cat", native, fallback,
            permissionMode = { AndroidControlPermissionMode.CONFIRM_CHANGES }) {}
        val call = pending.filterIsInstance<UIMessagePart.Tool>().single()
        val denied = executeNativeImageRequest("cat", native, fallback,
            permissionMode = { AndroidControlPermissionMode.FULL_ACCESS },
            resuming = listOf(call.copy(approvalState = ToolApprovalState.Denied("no")))) {}
        assertEquals(0, calls)
        assertTrue(denied.filterIsInstance<UIMessagePart.Tool>().single().isExecuted)
        assertTrue(denied.filterIsInstance<UIMessagePart.Tool>().single().output.toString().contains("denied"))
    }

    @Test fun aFullAccessChangeReleasesImageWaitWithoutPretendingUserApproval() = runBlocking {
        var calls = 0
        val native = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = { calls++; listOf(UIMessagePart.Image("file:///fixture/image.png")) })
        val pending = executeNativeImageRequest("cat", native, null,
            permissionMode = { AndroidControlPermissionMode.CONFIRM_CHANGES }) {}
        val resumed = executeNativeImageRequest("cat", native, null,
            permissionMode = { AndroidControlPermissionMode.FULL_ACCESS }, resuming = pending) {}
        assertEquals(1, calls)
        assertEquals(ToolApprovalState.Auto, resumed.filterIsInstance<UIMessagePart.Tool>().single().approvalState)
    }

    @Test fun tighteningModeDuringProgressWaitsBeforeTheImageRequest() = runBlocking {
        var calls = 0
        var mode = AndroidControlPermissionMode.FULL_ACCESS
        val native = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = { calls++; emptyList() })
        val pending = executeNativeImageRequest("cat", native, null, permissionMode = { mode }) {
            mode = AndroidControlPermissionMode.CONFIRM_CHANGES
        }
        assertEquals(0, calls)
        assertTrue(pending.filterIsInstance<UIMessagePart.Tool>().single().isPending)
    }

    @Test fun fallbackApprovalResumesWithoutRepeatingTheFailedBilledNativeRequest() = runBlocking {
        var nativeCalls = 0
        var fallbackCalls = 0
        var mode = AndroidControlPermissionMode.FULL_ACCESS
        val native = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = { nativeCalls++; emptyList() })
        val fallback = Tool(GENERATE_MEDIA_TOOL, "fixture", execute = { fallbackCalls++; listOf(UIMessagePart.Image("file:///fixture/image.png")) })
        val pending = executeNativeImageRequest("cat", native, fallback, permissionMode = { mode }) { parts ->
            if (parts.filterIsInstance<UIMessagePart.Tool>().any { it.toolName == GENERATE_NATIVE_IMAGE_TOOL && it.isExecuted })
                mode = AndroidControlPermissionMode.CONFIRM_CHANGES
        }
        assertEquals(1, nativeCalls)
        assertEquals(0, fallbackCalls)
        assertTrue(pending.filterIsInstance<UIMessagePart.Tool>().last().isPending)
        val approved = pending.map { if (it is UIMessagePart.Tool && it.isPending) it.copy(approvalState = ToolApprovalState.Approved) else it }
        executeNativeImageRequest("cat", native, fallback, permissionMode = { mode }, resuming = approved) {}
        assertEquals(1, nativeCalls)
        assertEquals(1, fallbackCalls)
    }

    @Test fun compositeImageRequestsReturnToThePlannerInsteadOfEndingAfterImageGeneration() {
        for (text in listOf("生成猫图并保存到相册", "生成小猫图片再发布到小红书", "生成挥手GIF后发聊天", "Create a cat image then save it", "Make an animated GIF and send it to my friend")) {
            assertNull(text, imageCreationRequest(listOf(UIMessage.user(text))))
            assertTrue(text, pureImageRequestHasFollowUpActions(text))
        }
        assertNotNull(imageCreationRequest(listOf(UIMessage.user("生成小猫图片"))))
        assertNotNull(imageCreationRequest(listOf(UIMessage.user("生成小猫GIF"))))
    }
    @Test fun explicitGifCreationUsesFormatAwareParametersButDiscussionDoesNot() {
        for (text in listOf("生成一张小猫GIF动图", "帮我做一个挥手动图", "Create an animated GIF of a cat")) {
            val request = imageCreationRequest(listOf(UIMessage.user(text)))!!
            assertEquals("gif", request.animationOptions!!["output_format"]!!.jsonPrimitive.content)
            assertEquals(4, request.animationOptions["frame_count"]!!.jsonPrimitive.int)
        }
        for (text in listOf("为什么GIF生成失败", "解释GIF原理", "生成GIF的代码", "先别生成GIF", "生成一个视频"))
            assertNull(text, imageCreationRequest(listOf(UIMessage.user(text))))
        val detailed = imageCreationRequest(listOf(UIMessage.user("制作一个8帧小猫动图，每帧125毫秒，本地合成")))!!
        assertEquals(8, detailed.animationOptions!!["frame_count"]!!.jsonPrimitive.int)
        assertEquals(125, detailed.animationOptions["frame_duration_ms"]!!.jsonPrimitive.int)
        assertEquals("storyboard", detailed.animationOptions["animation_mode"]!!.jsonPrimitive.content)
    }

    @Test fun gifFailureNeverAutomaticallyChargesAnotherImageEndpoint() = runBlocking {
        var calls = 0
        val native = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = { args ->
            calls++; assertEquals("gif", args.jsonObject["output_format"]!!.jsonPrimitive.content)
            listOf(UIMessagePart.Text("""{"status":"failed"}"""))
        })
        val fallback = Tool(GENERATE_MEDIA_TOOL, "fixture", execute = { error("GIF cannot silently retry another billed model") })
        val options = imageCreationRequest(listOf(UIMessage.user("生成一个小猫GIF")))!!.animationOptions
        assertThrows(IllegalStateException::class.java) {
            runBlocking { executeNativeImageRequest("cat", native, fallback, animationOptions = options) {} }
        }
        assertEquals(1, calls)
    }

    @Test fun dedicatedImageModelsKeepTheirOwnModelForGifWithoutRequiringFunctionCallingAbility() = runBlocking {
        val selected = Model(modelId = "gpt-image-2.6-flare", type = ModelType.IMAGE, abilities = emptyList())
        val unrelated = Model(modelId = "gpt-image-2.5-sunburst", type = ModelType.IMAGE)
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(selected, unrelated))), chatImageModelId = unrelated.id)
        var revision = 0L
        var calls = 0
        val tool = createNativeChatImageTool(settings, selected, requestRevision = { revision }) { _, model, _, params ->
            assertEquals(selected.id, model.id); assertEquals("gif", params.outputFormat)
            assertEquals(4, params.frameCount); calls++
            listOf(UIMessagePart.Image("file:///fixture/real-animation.gif"))
        }!!
        val args = buildJsonObject { put("prompt", "wave"); put("output_format", "gif"); put("frame_count", 4) }
        assertTrue(tool.execute(args).hasUsableGeneratedImage())
        assertFalse(tool.execute(args).hasUsableGeneratedImage())
        revision++
        assertTrue(tool.execute(args).hasUsableGeneratedImage())
        assertEquals(2, calls)
    }
    @Test fun newInstructionBeforeImageStartsAvoidsSubmittingAnOutdatedBilledRequest() = runBlocking {
        val native = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = { error("Must not start") })
        val result = executeNativeImageRequest("old image", native, null, shouldContinue = { false }) {}
        assertTrue(result.isEmpty())
    }

    @Test fun newInstructionDuringImageFinishesTheCurrentActionAndSkipsAnOutdatedFallback() = runBlocking {
        var newerInput = false
        var nativeCalls = 0
        val native = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = {
            nativeCalls++
            newerInput = true
            listOf(UIMessagePart.Text("upstream image unavailable"))
        })
        val fallback = Tool(GENERATE_MEDIA_TOOL, "fixture", execute = { error("Must replan before starting another image") })
        val result = executeNativeImageRequest("old image", native, fallback, shouldContinue = { !newerInput }) {}
        assertEquals(1, nativeCalls)
        assertEquals(listOf(GENERATE_NATIVE_IMAGE_TOOL), result.filterIsInstance<UIMessagePart.Tool>().map { it.toolName })
        assertEquals("upstream image unavailable", result.filterIsInstance<UIMessagePart.Tool>().single().output.single().let { (it as UIMessagePart.Text).text })
    }

    @Test fun newInstructionDoesNotDiscardAnImageThatAlreadyFinished() = runBlocking {
        var newerInput = false
        val image = UIMessagePart.Image("file:///fixture/completed.png")
        val native = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = { newerInput = true; listOf(image) })
        val result = executeNativeImageRequest("old image", native, null, shouldContinue = { !newerInput }) {}
        assertEquals(listOf(image), result.filterIsInstance<UIMessagePart.Tool>().single().output)
    }

    @Test fun onlyExplicitCreationRequestsBypassTextChat() {
        for (text in listOf("生成一张小猫图", "帮我生成小猫图", "请画一张小猫图", "Generate a small cat image.")) {
            assertEquals(text, nativeImageCreationPrompt(listOf(UIMessage.user(text))))
        }
        for (text in listOf("为什么这个模型不能生成图片", "解释生图原理", "写一个生图提示词", "hello", "Explain image generation")) {
            assertNull(text, nativeImageCreationPrompt(listOf(UIMessage.user(text))))
        }
        for (text in listOf("生成一张小猫 GIF 动图", "生成一张动画图", "生成一个视频并附封面图",
            "Generate an animated cat image", "Create an animation picture", "Make a video poster", "Draw a movie image")) {
            assertNull("Motion and video requests must use the format-aware tools", nativeImageCreationPrompt(listOf(UIMessage.user(text))))
        }
    }

    @Test fun lunaAndSolUseTheirOwnModelBeforeAnyConfiguredImageModel() = runBlocking {
        for (id in listOf("gpt-6-luna", "gpt-6.1-sol")) {
            val model = Model(modelId = id)
            val flare = Model(modelId = "gpt-image-2.5-flare", type = ModelType.IMAGE)
            val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(model, flare))),
                chatImageModelId = flare.id)
            var nativeCalls = 0
            val native = createNativeChatImageTool(settings, model) { provider, selected, _, _ ->
                nativeCalls++
                assertEquals(model.id, selected.id)
                assertEquals(id, selected.modelId)
                assertEquals(model.id, provider.id)
                listOf(UIMessagePart.Image("file:///fixture/native.png"))
            }!!
            val fallback = Tool(GENERATE_MEDIA_TOOL, "fixture", execute = { error("Successful native image must not delegate") })
            val parts = executeNativeImageRequest("生成一张小猫图", native, fallback) {}
            assertEquals(1, nativeCalls)
            assertEquals(listOf(GENERATE_NATIVE_IMAGE_TOOL), parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolName })
            assertTrue(parts.filterIsInstance<UIMessagePart.Tool>().single().output.hasUsableGeneratedImage())
        }
    }

    @Test fun emptyOrMalformedNativeResultInvokesConfiguredFallbackExactlyOnce() = runBlocking {
        for (nativeResult in listOf(emptyList(), listOf(UIMessagePart.Text("no image tool")),
            listOf(UIMessagePart.Image("data:image/png;base64,")))) {
            val order = mutableListOf<String>()
            val native = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = { order += "native"; nativeResult })
            val fallback = Tool(GENERATE_MEDIA_TOOL, "fixture", execute = {
                order += "configured-flare"; listOf(UIMessagePart.Image("file:///fixture/flare.png"))
            })
            val parts = executeNativeImageRequest("cat", native, fallback) {}
            assertEquals(listOf("native", "configured-flare"), order)
            assertTrue(parts.filterIsInstance<UIMessagePart.Tool>().last().output.hasUsableGeneratedImage())
        }
    }

    @Test fun decodingFailureFallsBackButCancellationNeverDoes() = runBlocking {
        var calls = 0
        val fallback = Tool(GENERATE_MEDIA_TOOL, "fixture", execute = { calls++; listOf(UIMessagePart.Image("file:///fixture/flare.png")) })
        val bad = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = { throw IllegalArgumentException("fixture decoder") })
        executeNativeImageRequest("cat", bad, fallback) {}
        assertEquals(1, calls)
        val cancelled = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = { throw CancellationException("fixture") })
        try { executeNativeImageRequest("cat", cancelled, fallback) {}; fail("Cancellation must propagate") }
        catch (_: CancellationException) {}
        assertEquals(1, calls)
    }

    @Test fun bothFailuresStopWithoutReportingTextAsSuccessfulGeneration() = runBlocking {
        var calls = 0
        val native = Tool(GENERATE_NATIVE_IMAGE_TOOL, "fixture", execute = { calls++; emptyList() })
        val fallback = Tool(GENERATE_MEDIA_TOOL, "fixture", execute = { calls++; listOf(UIMessagePart.Text("success")) })
        try { executeNativeImageRequest("cat", native, fallback) {}; fail("No image must fail") }
        catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("未返回可用图片")) }
        assertEquals(2, calls)
    }
}
