package com.constx.onvyra.data.model

import kotlin.uuid.Uuid

/** Stable identity of the bundled, read-only mode. Names alone must never lock user modes. */
internal val BUILTIN_PROMPT_MODE_ID = Uuid.parse("6465344e-24b3-4d45-a2ac-bac70ff36bcf")

internal val PromptInjection.ModeInjection.isBuiltinMode: Boolean
    get() = id == BUILTIN_PROMPT_MODE_ID
