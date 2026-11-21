package com.constx.onvyra.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flowOf
import com.constx.onvyra.R
import com.constx.onvyra.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationDrawerListTest {
    @get:Rule val compose = createComposeRule()

    @Test fun collapsedRecordsReturnOnExpandAndHeaderScrollsWithThemWhileFooterStaysFixed() {
        val assistantId = Uuid.random()
        val current = conversation(assistantId, "当前聊天不在此项目")
        val pinned = conversation(assistantId, "置顶会话").copy(isPinned = true)
        val records = List(30) { conversation(assistantId, "项目聊天 $it") }
        val items = listOf(
            ConversationListItem.PinnedHeader,
            ConversationListItem.Item(pinned),
            ConversationListItem.DateHeader(LocalDate.now(), "测试日期分组"),
        ) + records.map { ConversationListItem.Item(it) }
        val paging = flowOf(PagingData.from<ConversationListItem>(items))
        val expanded = mutableStateOf(false)
        var opened: Uuid? = null

        compose.setContent {
            MaterialExpressiveTheme {
                val conversations = paging.collectAsLazyPagingItems()
                Column(Modifier.width(300.dp).height(420.dp)) {
                    ConversationList(
                        current = current,
                        conversations = conversations,
                        conversationJobs = emptyList(),
                        listState = rememberLazyListState(),
                        modifier = Modifier.weight(1f).testTag("drawer-records"),
                        expanded = expanded.value,
                        header = {
                            Column {
                                Text("聊天历史", Modifier.height(36.dp))
                                Text("媒体创作", Modifier.height(36.dp))
                                TextButton(
                                    onClick = { expanded.value = !expanded.value },
                                    modifier = Modifier.testTag("toggle-records"),
                                ) { Text("聊天") }
                            }
                        },
                        onClick = { opened = it.id },
                    )
                    Text("个人信息与快捷入口", Modifier.fillMaxWidth().height(48.dp).testTag("drawer-footer"))
                }
            }
        }

        compose.onNodeWithText("聊天历史").assertIsDisplayed()
        compose.onNodeWithText("置顶会话").assertDoesNotExist()
        compose.onNodeWithText("测试日期分组").assertDoesNotExist()
        compose.onNodeWithText("项目聊天 0").assertDoesNotExist()
        val footerBounds = compose.onNodeWithTag("drawer-footer").fetchSemanticsNode().boundsInRoot

        compose.onNodeWithTag("toggle-records").performClick()
        compose.onNodeWithText("置顶会话").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(pinned.id, opened) }
        compose.onNodeWithText("测试日期分组").assertIsDisplayed()
        compose.onNodeWithText("项目聊天 0").assertIsDisplayed()

        compose.onNodeWithTag("drawer-records").performScrollToNode(hasText("项目聊天 29"))
        compose.onNodeWithText("项目聊天 29").assertIsDisplayed()
        compose.onNodeWithText("聊天历史").assertIsNotDisplayed()
        compose.onNodeWithText("媒体创作").assertIsNotDisplayed()
        compose.onNodeWithTag("drawer-footer").assertIsDisplayed()
        assertEquals(footerBounds, compose.onNodeWithTag("drawer-footer").fetchSemanticsNode().boundsInRoot)

        compose.onNodeWithTag("drawer-records").performScrollToIndex(0)
        compose.onNodeWithTag("toggle-records").performClick()
        compose.onNodeWithText("聊天历史").assertIsDisplayed()
        compose.onNodeWithText("置顶会话").assertDoesNotExist()
        compose.onNodeWithText("测试日期分组").assertDoesNotExist()
        compose.onNodeWithText("项目聊天 0").assertDoesNotExist()
    }

    @Test fun emptyProjectOnlyShowsItsEmptyMessageWhenExpanded() {
        val current = conversation(Uuid.random(), "当前聊天")
        val paging = flowOf(PagingData.empty<ConversationListItem>())
        val expanded = mutableStateOf(false)
        val emptyMessage = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.chat_page_no_conversations)

        compose.setContent {
            MaterialExpressiveTheme {
                Column(Modifier.width(300.dp).height(420.dp)) {
                    ConversationList(
                        current = current,
                        conversations = paging.collectAsLazyPagingItems(),
                        conversationJobs = emptyList(),
                        listState = rememberLazyListState(),
                        modifier = Modifier.weight(1f),
                        expanded = expanded.value,
                        header = {
                            TextButton(onClick = { expanded.value = !expanded.value }) { Text("空项目") }
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("空项目").assertIsDisplayed()
        compose.onNodeWithText(emptyMessage).assertDoesNotExist()
        compose.onNodeWithText("空项目").performClick()
        compose.onNodeWithText(emptyMessage).assertIsDisplayed()
        compose.onNodeWithText("空项目").performClick()
        compose.onNodeWithText(emptyMessage).assertDoesNotExist()
    }

    private fun conversation(assistantId: Uuid, title: String) = Conversation(
        assistantId = assistantId,
        title = title,
        messageNodes = emptyList(),
    )
}
