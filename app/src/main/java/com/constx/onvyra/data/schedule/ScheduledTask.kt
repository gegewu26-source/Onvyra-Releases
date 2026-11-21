package com.constx.onvyra.data.schedule

import kotlinx.serialization.Serializable
import com.constx.onvyra.data.model.ConversationConfig
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
enum class ScheduledTaskRepeat { ONCE, DAILY, WEEKLY }

@Serializable
enum class ScheduledTaskStatus { IDLE, RUNNING, SUCCEEDED, FAILED }

@Serializable
data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val assistantId: String,
    val prompt: String,
    val firstRunAtMillis: Long,
    val repeat: ScheduledTaskRepeat = ScheduledTaskRepeat.ONCE,
    val enabled: Boolean = true,
    val revision: Long = 1,
    val zoneId: String = ZoneId.systemDefault().id,
    val nextRunAtMillis: Long? = null,
    val modelId: String = "",
    val providerId: String = "",
    val modelPublicId: String = "",
    val modelFingerprint: String = "",
    val providerFingerprint: String = "",
    val conversationConfig: ConversationConfig? = null,
    val lastStatus: ScheduledTaskStatus = ScheduledTaskStatus.IDLE,
    val lastMessage: String = "",
    val lastConversationId: String? = null,
    val lastRunToken: String? = null,
    val lastScheduledAtMillis: Long? = null,
) {
    fun token(atMillis: Long) = "$id:$revision:$atMillis"
    fun conversationId(atMillis: Long): String =
        UUID.nameUUIDFromBytes(token(atMillis).toByteArray(Charsets.UTF_8)).toString()
}

/** Strictly after [afterMillis]; calendar repetition preserves local wall-clock time across DST. */
fun nextScheduledTaskTime(task: ScheduledTask, afterMillis: Long): Long? {
    val first = task.firstRunAtMillis
    if (first > afterMillis) return first
    if (task.repeat == ScheduledTaskRepeat.ONCE) return null
    val zone = ZoneId.of(task.zoneId)
    val anchor = Instant.ofEpochMilli(first).atZone(zone)
    val after = Instant.ofEpochMilli(afterMillis).atZone(zone)
    val step = if (task.repeat == ScheduledTaskRepeat.DAILY) 1L else 7L
    val days = ChronoUnit.DAYS.between(anchor.toLocalDate(), after.toLocalDate()).coerceAtLeast(0)
    var periods = days / step
    fun occurrenceAt(index: Long) = anchor.toLocalDate().plusDays(index * step)
        .atTime(anchor.toLocalTime()).atZone(zone)
    var occurrence = occurrenceAt(periods)
    while (occurrence.toInstant().toEpochMilli() <= afterMillis) occurrence = occurrenceAt(++periods)
    return occurrence.toInstant().toEpochMilli()
}
