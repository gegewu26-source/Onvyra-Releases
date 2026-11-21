package com.constx.onvyra.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
sealed class Avatar {
    @Serializable
    @SerialName("avatar_dummy")
    data object Dummy : Avatar()

    @Serializable
    @SerialName("avatar_emoji")
    data class Emoji(val content: String) : Avatar()

    @Serializable
    @SerialName("avatar_image")
    data class Image(val url: String) : Avatar()
}
