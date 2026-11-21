package com.constx.onvyra.data.ai

import com.constx.onvyra.data.ai.tools.GENERATE_MEDIA_TOOL
import com.constx.onvyra.data.ai.tools.GENERATE_NATIVE_IMAGE_TOOL
import com.constx.onvyra.data.model.AndroidControlPermissionMode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.accumulate
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.*
import kotlin.uuid.Uuid

internal data class ImageCreationRequest(val prompt: String, val animationOptions: JsonObject? = null)

internal fun pureImageRequestHasFollowUpActions(text: String): Boolean =
    Regex("保存|相册|发送|发布|上传|分享|导出|小红书|发(?:给|到|至|聊天)|并(?:写|配|附)|另(?:写|给)|附(?:上|带).*(?:文案|说明|解释)|\\b(?:save|album|gallery|send|publish|upload|share|export|post)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(text)

/** Explicit GIF creation enters the format-aware path even for image-only models without tools. */
internal fun imageCreationRequest(messages: List<UIMessage>): ImageCreationRequest? {
    val user = messages.lastOrNull { it.role == MessageRole.USER } ?: return null
    if (user.parts.any { it is UIMessagePart.Image || it is UIMessagePart.Document }) return null
    val text = user.toText().trim()
    if (text.isBlank() || text.length > 32_000) return null
    if (pureImageRequestHasFollowUpActions(text)) return null
    nativeImageCreationPrompt(messages)?.let { return ImageCreationRequest(it) }
    if (!Regex("(?<![A-Za-z])GIF(?![A-Za-z])|动图|动态图片|动画图片", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
    if (Regex("原理|报错|接口|配置|代码|提示词|为什么|如何|怎么|不能|prompt|explain|why|how", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
    val create = Regex("^(?:(?:请|帮我|给我|为我|麻烦|你|再|直接|现在|重新|我要|我想要|，|,|\\s))*(?:生成|画|绘制|生图|制作|设计|做)").containsMatchIn(text) ||
        Regex("^(?:please\\s+)?(?:generate|draw|create|make|render|design)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    if (!create) return null
    val frames = Regex("(?:^|\\D)(\\d{1,2})\\s*(?:帧|frames?\\b)", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 4
    val delay = Regex("(\\d{2,4})\\s*(?:毫秒|ms\\b)", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 200
    val mode = when {
        Regex("分镜合成|本地合成|storyboard", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "storyboard"
        Regex("原生\\s*GIF|native\\s+GIF", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "native"
        else -> "auto"
    }
    return ImageCreationRequest(text, buildJsonObject {
        put("output_format", "gif"); put("frame_count", frames); put("frame_duration_ms", delay); put("animation_mode", mode)
    })
}

/** Only direct creation requests take this path; discussion, prompts, edits and attachments use normal chat. */
internal fun nativeImageCreationPrompt(messages: List<UIMessage>): String? {
    val user = messages.lastOrNull { it.role == MessageRole.USER } ?: return null
    if (user.parts.any { it is UIMessagePart.Image || it is UIMessagePart.Document }) return null
    val text = user.toText().trim()
    if (text.isBlank() || text.length > 32_000) return null
    // The normal tool schema validates output formats and video capabilities.
    // This shortcut only makes a still image and must not silently replace motion.
    if (Regex("GIF|动图|动画|视频|\\b(?:animation|animated|video|movie)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
        return null
    if (Regex("原理|报错|接口|配置|代码|提示词|为什么|如何|怎么|不能|prompt|explain|why|how", RegexOption.IGNORE_CASE).containsMatchIn(text))
        return null
    val chinese = Regex("^(?:(?:请|帮我|给我|为我|麻烦|你|再|直接|现在|重新|我要|我想要|，|,|\\s))*(?:生成|画|绘制|生图|制作|设计)").containsMatchIn(text) &&
        (Regex("图片|图像|图|照片|插画|海报").containsMatchIn(text) || text.startsWith("画"))
    val english = Regex("^(?:please\\s+)?(?:generate|draw|create|make|render|design)\\b.{0,300}\\b(?:image|picture|photo|illustration|poster|drawing)\\b",
        RegexOption.IGNORE_CASE).containsMatchIn(text)
    return text.takeIf { chinese || english }
}

internal fun List<UIMessagePart>.hasUsableGeneratedImage(): Boolean = any { part ->
    part is UIMessagePart.Image && part.url.isNotBlank() &&
        (!part.url.startsWith("data:") || part.url.substringAfter("base64,", "").isNotBlank())
}

internal fun List<UIMessagePart>.generatedImageTokenUsage(): TokenUsage? {
    val seen = mutableSetOf<String>()
    fun collect(parts: List<UIMessagePart>, scope: String): TokenUsage? {
        var total: TokenUsage? = null
        parts.forEach { part ->
            if (part is UIMessagePart.Tool && part.toolName in setOf(GENERATE_NATIVE_IMAGE_TOOL, GENERATE_MEDIA_TOOL)) {
                total = total.accumulate(collect(part.output, part.toolCallId))
            } else if (part is UIMessagePart.Image) {
                val usage = part.metadata?.get("onvyra_token_usage") as? JsonObject ?: return@forEach
                val id = (usage["request_id"] as? JsonPrimitive)?.contentOrNull ?: scope
                fun count(key: String) = (usage[key] as? JsonPrimitive)?.longOrNull
                    ?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
                if (seen.add(id)) total = total.accumulate(TokenUsage(
                    promptTokens = count("prompt_tokens"), completionTokens = count("completion_tokens"),
                    cachedTokens = count("cached_tokens"), totalTokens = count("total_tokens")))
            }
        }
        return total
    }
    return collect(this, "direct-image")
}

/** Account each newly completed image tool once; later text requests keep this turn usage. */
internal fun UIMessage.withImageToolResults(parts: List<UIMessagePart>): UIMessage {
    val completed = getTools().filter { it.output.isNotEmpty() }.mapTo(mutableSetOf()) { it.toolCallId }
    val added = parts.filterIsInstance<UIMessagePart.Tool>().filter { it.toolCallId !in completed }
        .generatedImageTokenUsage()
    return copy(parts = parts, usage = usage.accumulate(added))
}

/** One actual attempt with the current model; a configured image model is only the failure recovery. */
internal suspend fun executeNativeImageRequest(prompt: String, native: Tool, fallback: Tool?,
    shouldContinue: () -> Boolean = { true },
    animationOptions: JsonObject? = null,
    permissionMode: () -> AndroidControlPermissionMode = { AndroidControlPermissionMode.AUTONOMOUS },
    resuming: List<UIMessagePart> = emptyList(),
    onProgress: suspend (List<UIMessagePart>) -> Unit,
): List<UIMessagePart> {
    val calls = mutableListOf<UIMessagePart>()
    var awaitingOrDenied = false
    suspend fun attempt(tool: Tool): Boolean {
        val newArgs = buildJsonObject {
            put("prompt", prompt); if (tool.name == GENERATE_MEDIA_TOOL) put("kind", "image")
            animationOptions?.forEach { (key, value) -> put(key, value) }
        }
        val original = resuming.filterIsInstance<UIMessagePart.Tool>().firstOrNull {
            it.toolName == tool.name && it.isDirectImageRequest
        } ?: UIMessagePart.Tool(toolCallId = Uuid.random().toString(), toolName = tool.name, input = newArgs.toString(),
            metadata = buildJsonObject { put("onvyra_direct_image", true) })
        var call = resolveTaskToolApproval(original, tool, permissionMode())
        val args = original.inputAsJson()
        if (call.isExecuted) {
            calls += call
            return call.output.hasUsableGeneratedImage()
        }
        if (call.approvalState is ToolApprovalState.Denied) {
            calls += call.copy(output = listOf(UIMessagePart.Text("""{"status":"denied","reason":"user_denied_image_request"}""")))
            awaitingOrDenied = true
            onProgress(calls)
            return false
        }
        onProgress(calls + call)
        if (call.approvalState is ToolApprovalState.Pending) {
            calls += call
            awaitingOrDenied = true
            return false
        }
        if (!shouldContinue()) {
            calls += call.copy(output = listOf(UIMessagePart.Text("""{"status":"cancelled","reason":"new_user_instruction_before_image_request"}""")))
            onProgress(calls)
            return false
        }
        // Publishing progress suspends while UI/configuration can change. Recheck
        // at the last boundary before a billed action, retaining exact approvals.
        call = resolveTaskToolApproval(call, tool, permissionMode())
        if (call.isPending) {
            calls += call
            awaitingOrDenied = true
            onProgress(calls)
            return false
        }
        val result = try { tool.execute(args) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { listOf(UIMessagePart.Text("""{"status":"failed","error_code":"image_attempt_failed"}""")) }
        // Even a malformed empty response completed an actual request. Mark it
        // as a failed result so approval resumption cannot replay that billed call.
        calls += call.copy(output = result.ifEmpty {
            listOf(UIMessagePart.Text("""{"status":"failed","error_code":"empty_image_result"}"""))
        })
        onProgress(calls)
        return result.hasUsableGeneratedImage()
    }
    if (!shouldContinue()) return calls
    if (attempt(native)) return calls
    if (awaitingOrDenied) return calls
    // The current HTTP/tool action has finished. Do not start another billed fallback for an
    // outdated instruction when the user changed the task while that request was running.
    if (!shouldContinue()) return calls
    if ((animationOptions?.get("output_format") as? JsonPrimitive)?.contentOrNull == "gif") {
        error("GIF生成未返回有效动画。请查看工具结果；原生接口不支持时，可明确选择分镜合成后重试。本次未自动重复提交生图请求。")
    }
    if (fallback != null && fallback.name == GENERATE_MEDIA_TOOL) {
        if (attempt(fallback)) return calls
        if (awaitingOrDenied) return calls
    }
    if (!shouldContinue()) return calls
    throw IllegalStateException("当前模型的原生生图未返回可用图片" +
        if (fallback == null) "，且没有配置备用生图模型。" else "，配置的备用生图模型也未生成成功。")
}

internal val UIMessagePart.Tool.isDirectImageRequest: Boolean
    get() = metadata?.get("onvyra_direct_image")?.jsonPrimitive?.booleanOrNull == true
