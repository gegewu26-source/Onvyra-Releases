package com.constx.onvyra.data.datastore

import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

// Keep historical model selection IDs stable; this is an existing empty-selection placeholder.
val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")
val DEFAULT_OPENAI_PROVIDER_ID = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e")

val DEFAULT_PROVIDERS: List<ProviderSetting> = listOf(
    ProviderSetting.OpenAI(
        id = DEFAULT_OPENAI_PROVIDER_ID,
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        builtIn = true,
    ),
)
