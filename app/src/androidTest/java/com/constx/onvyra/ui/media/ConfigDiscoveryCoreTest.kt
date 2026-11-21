package com.constx.onvyra.ui.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the supplied decoder/discovery fixtures against Android's real org.json implementation. */
@RunWith(AndroidJUnit4::class)
class ConfigDiscoveryCoreTest {
    @Test fun suppliedConfigDiscoveryOfflineAssertionsPassOnAndroid() {
        com.example.configdiscovery.CoreTest.main(emptyArray())
    }
}
