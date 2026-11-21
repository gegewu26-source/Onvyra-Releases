package com.constx.onvyra.data.datastore

import com.constx.onvyra.data.model.Avatar
import com.constx.onvyra.utils.JsonInstant
import com.constx.onvyra.utils.JsonInstantPretty
import org.junit.Assert.*
import org.junit.Test

class OnvyraIdentityCompatibilityTest {
    @Test fun oldPackageAvatarsRemainReadableAndNewArchivesUseNeutralNames() {
        for (json in listOf(JsonInstant, JsonInstantPretty)) {
            assertEquals(Avatar.Emoji("hello"), json.decodeFromString<Avatar>(
                """{"type":"me.rerere.rikkahub.data.model.Avatar.Emoji","content":"hello"}"""))
            assertEquals(Avatar.Image("file:///data/local/example.png"), json.decodeFromString<Avatar>(
                """{"type":"me.rerere.rikkahub.data.model.Avatar.Image","url":"file:///data/local/example.png"}"""))
            assertEquals(Avatar.Dummy, json.decodeFromString<Avatar>(
                """{"type":"me.rerere.rikkahub.data.model.Avatar.Dummy"}"""))
            val encoded = json.encodeToString<Avatar>(Avatar.Emoji("hello"))
            assertTrue(encoded.contains("avatar_emoji"))
            assertFalse(encoded.contains("rikkahub"))
            assertEquals(Avatar.Emoji("hello"), json.decodeFromString<Avatar>(encoded))
        }
    }
}
