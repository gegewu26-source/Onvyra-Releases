package com.constx.onvyra.data.ai

import java.text.Normalizer
import java.util.Locale

/** A model may defer a stop, but it cannot turn an ordinary task update into a phone stop. */
enum class ModelStopTaskDecision { ALLOWED, DEFERRED, DENIED }

private const val MAX_WHOLE_STOP_COMMAND_LENGTH = 96

private val politeStopPrefix = Regex(
    "^(?:请(?:你|您)?|麻烦(?:你|您)?|劳驾(?:你|您)?|帮我|给我|现在|马上|立即|立刻|先|" +
        "please\\b|(?:can|could|would|will)\\s+you\\b|just\\b|now\\b)\\s*",
)
private val politeStopSuffix = Regex(
    "(?:\\s*(?:谢谢(?:你|您)?|多谢|麻烦了|好吗|好不好|可以吗|行吗|吧|了)|" +
        "\\s+(?:please|thanks|thank you|right now|now|immediately))$",
)
private val stopPunctuation = Regex("[,，。.!！?？]+")
private val stopWhitespace = Regex("\\s+")
private val quotedCommand = Regex("^[\\\"'“”‘’`].*[\\\"'“”‘’`]$")

private const val CHINESE_TASK_OBJECT =
    "(?:(?:当前|本次|这次|这个|整个|全部|所有|我的|这项|此|正在运行|正在执行)(?:的)?){0,2}任务"
private const val CHINESE_PHONE_OBJECT =
    "(?:(?:当前|全部|所有)(?:的)?)?(?:手机(?:操作|操控|控制)|(?:操控|操作|控制)手机)(?:任务)?"
private const val CHINESE_STOP_OBJECT =
    "(?:$CHINESE_TASK_OBJECT|$CHINESE_PHONE_OBJECT|(?:当前的?)?(?:所有|全部)的?操作|运行|执行)"
private val chineseWholeStop = Regex(
    "(?:" +
        "(?:停止|暂停|终止|中止|取消|结束)\\s*(?:$CHINESE_STOP_OBJECT)?(?:一下|了)?|" +
        "停|停下|停下来|停住|停一下|停一停|" +
        "(?:全部|所有|都)\\s*(?:停止|暂停|终止|中止|取消|停下|停下来)|" +
        "(?:停止|暂停|终止|中止|取消)\\s*全部|" +
        "(?:别|不用|不要)(?:再)?(?:继续(?:执行|运行|操作手机)?|做|执行|运行)(?:了)?" +
        ")",
)

private const val ENGLISH_STOP_ACTION = "(?:stop|cancel|abort|halt|terminate|end|pause)"
private const val ENGLISH_TASK_OBJECT =
    "(?:(?:the|this|that|current|ongoing|running|entire|whole|all)\\s+)*(?:task|tasks|job|jobs)"
private const val ENGLISH_PHONE_OBJECT =
    "(?:(?:the|this|current|all)\\s+)*(?:phone|android)\\s+(?:control|operation|operations)"
private val englishWholeStop = Regex(
    "$ENGLISH_STOP_ACTION(?:\\s+(?:$ENGLISH_TASK_OBJECT|$ENGLISH_PHONE_OBJECT|all operations|everything|all|it|running|working))?|" +
        "(?:do not|don't)\\s+continue",
)
private val mixedWholeStop = Regex(
    "$ENGLISH_STOP_ACTION\\s*(?:$CHINESE_TASK_OBJECT|$CHINESE_PHONE_OBJECT)|" +
        "(?:停止|暂停|终止|中止|取消|结束)\\s*(?:$ENGLISH_TASK_OBJECT|$ENGLISH_PHONE_OBJECT)",
)

/**
 * Only a complete short command authorizes ending the whole task. This deliberately does not
 * classify intent from arbitrary prose: cancelling a proposed publish/search action, changing
 * the goal, or asking what a stop means must keep the phone session alive for replanning.
 *
 * Callers inspecting actual message parts must set [hasAttachments]; a text fragment on its
 * own cannot establish what an accompanying image/document means.
 */
fun explicitWholeTaskStop(text: String, hasAttachments: Boolean = false): Boolean {
    if (hasAttachments) return false
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_WHOLE_STOP_COMMAND_LENGTH) return false
    if (trimmed.any { it == '\n' || it == '\r' || it == ';' || it == '；' || it == ':' || it == '：' }) return false
    if (quotedCommand.matches(trimmed) || '`' in trimmed) return false

    var command = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('’', '\'')
    if ('?' in command) {
        // A standalone question is not a stop command. Conventional polite requests remain
        // accepted, while explanatory/conditional questions still fail the full-body match.
        val politeQuestion = Regex("^(?:please\\s+)?(?:can|could|would|will)\\s+you\\b").containsMatchIn(command) ||
            Regex("(?:好吗|好不好|可以吗|行吗)[?\\s]+$").containsMatchIn(command)
        if (!politeQuestion) return false
    }
    command = stopWhitespace.replace(stopPunctuation.replace(command, " "), " ").trim()
    repeat(4) {
        command = politeStopPrefix.replaceFirst(command, "").trim()
        command = politeStopSuffix.replaceFirst(command, "").trim()
    }
    if (command.isEmpty()) return false
    return chineseWholeStop.matches(command) || englishWholeStop.matches(command) || mixedWholeStop.matches(command)
}

/** Unread updates always win; ordinary changes never authorize releasing the owned display. */
fun modelStopTaskDecision(
    latestUserText: String,
    hasPendingUpdate: Boolean,
    hasAttachments: Boolean = false,
): ModelStopTaskDecision = when {
    hasPendingUpdate -> ModelStopTaskDecision.DEFERRED
    explicitWholeTaskStop(latestUserText, hasAttachments) -> ModelStopTaskDecision.ALLOWED
    else -> ModelStopTaskDecision.DENIED
}
