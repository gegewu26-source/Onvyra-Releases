package com.constx.onvyra.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.TemporaryChatMode
import kotlin.uuid.Uuid

/** Process memory only. Closed IDs stay blocked so late cancelled callbacks cannot persist them. */
internal class TemporaryChatRegistry {
    private data class Entry(
        val mode: MutableStateFlow<TemporaryChatMode>,
        var original: Conversation? = null,
        var closed: Boolean = false,
    )
    private val entries = mutableMapOf<Uuid, Entry>()

    @Synchronized fun modeFlow(id: Uuid): StateFlow<TemporaryChatMode> =
        entries.getOrPut(id) { Entry(MutableStateFlow(TemporaryChatMode.OFF)) }.mode.asStateFlow()

    @Synchronized fun mode(id: Uuid): TemporaryChatMode = entries[id]?.mode?.value ?: TemporaryChatMode.OFF
    @Synchronized fun isClosed(id: Uuid): Boolean = entries[id]?.closed == true
    @Synchronized fun original(id: Uuid): Conversation? = entries[id]?.original
    @Synchronized fun blocksStorage(id: Uuid): Boolean = entries[id]?.let { it.closed || it.mode.value.isTemporary } == true

    @Synchronized fun change(id: Uuid, mode: TemporaryChatMode, original: Conversation): Conversation? {
        val entry = entries.getOrPut(id) { Entry(MutableStateFlow(TemporaryChatMode.OFF)) }
        check(!entry.closed) { "临时聊天已结束，请新建聊天" }
        if (mode.isTemporary && !entry.mode.value.isTemporary) entry.original = original
        val restored = if (mode == TemporaryChatMode.OFF) entry.original else null
        entry.mode.value = mode
        if (mode == TemporaryChatMode.OFF) entry.original = null
        return restored
    }

    @Synchronized fun close(id: Uuid): Boolean {
        val entry = entries[id] ?: return false
        if (!entry.mode.value.isTemporary || entry.closed) return false
        entry.closed = true
        entry.original = null
        return true
    }
}

/** Shared by the service and every repository persistence entry point. Never serialized. */
internal val temporaryChatRegistry = TemporaryChatRegistry()
