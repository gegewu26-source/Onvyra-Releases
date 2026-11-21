package com.constx.onvyra.ui.pages.stats

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constx.onvyra.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheStatsCardTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun displaysActualInputBasedPercentage() {
        compose.setContent { MaterialExpressiveTheme {
            CacheStatsCard(AppStats(totalPromptTokens = 100, totalCompletionTokens = 100, totalCachedTokens = 80))
        } }
        compose.onNodeWithText(formatCacheHitRate(calculateCacheHitRate(100, 80))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.stats_page_cache_hit_rate)).assertIsDisplayed()
    }

    @Test fun zeroCacheStillHasAVisibleCardAndZeroPercentage() {
        compose.setContent { MaterialExpressiveTheme {
            CacheStatsCard(AppStats(totalPromptTokens = 100, totalCachedTokens = 0))
        } }
        compose.onNodeWithText(context.getString(R.string.stats_page_cached_tokens)).assertIsDisplayed()
        compose.onNodeWithText(formatCacheHitRate(calculateCacheHitRate(100, 0))).assertIsDisplayed()
    }

    @Test fun noInputShowsUnavailableInsteadOfAFabricatedPercentage() {
        compose.setContent { MaterialExpressiveTheme { CacheStatsCard(AppStats()) } }
        compose.onNodeWithText("—").assertIsDisplayed()
    }

    @Test fun inconsistentCountersAreNotClampedToOneHundredPercent() {
        compose.setContent { MaterialExpressiveTheme {
            CacheStatsCard(AppStats(totalPromptTokens = 100, totalCachedTokens = 120))
        } }
        compose.onNodeWithText("—").assertIsDisplayed()
    }
}
