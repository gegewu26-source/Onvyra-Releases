package com.constx.onvyra.ui.components.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** b/549552303: baseline measurement and recycled rows must not corrupt RectManager. */
@RunWith(AndroidJUnit4::class)
class TaskProgressLayoutRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun streamingProgressAndReusedRowsCanResolveBaselinesDuringMeasure() {
        val listState = LazyListState()
        val progress = mutableIntStateOf(0)
        compose.setContent {
            MaterialExpressiveTheme {
                LazyColumn(state = listState, modifier = Modifier.height(48.dp)) {
                    items((0 until 30).toList(), key = { it }) { step ->
                        BaselineProbe {
                            Box(Modifier.fillMaxWidth()) {
                                Spacer(Modifier.layout { child, constraints ->
                                    val measured = child.measure(constraints)
                                    layout(30 + progress.intValue, 48,
                                        alignmentLines = mapOf(FirstBaseline to step)) {
                                        measured.placeRelative(0, 0)
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
        repeat(20) { update ->
            compose.runOnIdle {
                progress.intValue = update + 1
                runBlocking { listState.scrollBy(48f) }
            }
            compose.waitForIdle()
        }
    }
}

@Composable
private fun BaselineProbe(content: @Composable () -> Unit) {
    Layout(content = content) { children, constraints ->
        val child = children.single().measure(constraints)
        val baseline = child[FirstBaseline]
        layout(child.width, child.height, alignmentLines = mapOf(FirstBaseline to baseline)) {
            child.placeRelative(0, 0)
        }
    }
}
