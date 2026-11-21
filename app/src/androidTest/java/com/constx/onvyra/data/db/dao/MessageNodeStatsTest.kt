package com.constx.onvyra.data.db.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import com.constx.onvyra.data.db.AppDatabase
import com.constx.onvyra.data.db.entity.ConversationEntity
import com.constx.onvyra.data.db.entity.MessageNodeEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageNodeStatsTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: MessageNodeDAO
    private var nodeIndex = 0

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        dao = database.messageNodeDao()
        database.conversationDao().insert(
            ConversationEntity(
                id = "stats-test",
                assistantId = "assistant",
                title = "Stats test",
                nodes = "[]",
                createAt = 0,
                updateAt = 0,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun malformedRowsAreSkippedWhileValidMessagesAreAggregated() = runBlocking {
        insertMessages("""[{"role":"user"""")
        insertMessages(
            """[
                {"role":"user","createdAt":"2026-08-12T09:00:00"},
                {"role":"assistant","createdAt":"2026-08-12T09:01:00",
                 "usage":{"promptTokens":100,"completionTokens":40,"cachedTokens":20}},
                {"role":"user","createdAt":"2026-08-12T10:00:00"},
                {"role":"user","createdAt":"2026-08-13T09:00:00"},
                {"role":"user","createdAt":"2026-08-01T09:00:00"}
            ]""".trimIndent()
        )
        insertMessages("not json")
        insertMessages(
            """[{"role":"assistant","createdAt":"2026-08-13T09:01:00",
                 "usage":{"promptTokens":50,"completionTokens":10,"cachedTokens":5}}]"""
        )
        insertMessages("[]")

        assertEquals(MessageTokenStats(6, 150, 50, 25), dao.getTokenStats())
        assertEquals(
            mapOf("2026-08-12" to 2, "2026-08-13" to 1),
            dao.getMessageCountPerDay("2026-08-12").associate { it.day to it.count },
        )
        assertEquals(5, dao.getNodesOfConversation("stats-test").size)
    }

    @Test
    fun onlyMalformedOrEmptyRowsProduceEmptyStats() = runBlocking {
        insertMessages("")
        insertMessages("[")
        insertMessages("not json")
        insertMessages("[]")

        assertEquals(MessageTokenStats(), dao.getTokenStats())
        assertEquals(emptyList<MessageDayCount>(), dao.getMessageCountPerDay("2026-08-12"))
    }

    @Test
    fun emptyDatabaseProducesEmptyStats() = runBlocking {
        assertEquals(MessageTokenStats(), dao.getTokenStats())
        assertEquals(emptyList<MessageDayCount>(), dao.getMessageCountPerDay("2026-08-12"))
    }

    @Test
    fun forkedHistoryIsNotChargedTwiceButRegeneratedMessagesAreCounted() = runBlocking {
        val shared = """{"id":"shared","role":"assistant","usage":{"promptTokens":100,"completionTokens":40,"cachedTokens":80}}"""
        insertMessages("[$shared]")
        insertMessages("""[$shared,{"id":"regenerated","role":"assistant","usage":{"promptTokens":50,"completionTokens":10,"cachedTokens":20}}]""")
        assertEquals(MessageTokenStats(3, 150, 50, 100), dao.getTokenStats())
    }

    @Test
    fun cumulativeSnapshotsAreNotAddedAgainAndLegacyMessagesWithoutIdsRemainDistinct() = runBlocking {
        insertMessages("""[{"id":"shared","usage":{"promptTokens":100,"completionTokens":20,"cachedTokens":50}}]""")
        insertMessages("""[{"id":"shared","usage":{"promptTokens":150,"completionTokens":30,"cachedTokens":80}}]""")
        repeat(2) { insertMessages("""[{"usage":{"promptTokens":10,"completionTokens":2,"cachedTokens":5}}]""") }
        assertEquals(MessageTokenStats(4, 170, 34, 90), dao.getTokenStats())
    }

    @Test
    fun cacheIsASubsetOfInputAndTotalsUse64BitSums() = runBlocking {
        repeat(3) { index ->
            insertMessages("""[{"id":"large-$index","usage":{"promptTokens":2000000000,"completionTokens":1000000000,"cachedTokens":1500000000}}]""")
        }
        assertEquals(MessageTokenStats(3, 6000000000L, 3000000000L, 4500000000L), dao.getTokenStats())
    }

    @Test
    fun invalidElementsAndCountersCannotBreakOrSubtractFromStats() = runBlocking {
        insertMessages("""[null,42,"not json",[],{"role":"user","createdAt":"2026-08-12T09:00:00"},
            {"id":"bad","usage":{"promptTokens":-10,"completionTokens":"9999","cachedTokens":true}},
            {"id":"good","usage":{"promptTokens":100,"completionTokens":20,"cachedTokens":40}}]""")
        insertMessages("""{"usage":{"promptTokens":900}}""")
        assertEquals(MessageTokenStats(3, 100, 20, 40), dao.getTokenStats())
        assertEquals(listOf(MessageDayCount("2026-08-12", 1)), dao.getMessageCountPerDay("2026-08-12"))
    }

    private suspend fun insertMessages(messages: String) {
        val index = nodeIndex++
        dao.insert(
            MessageNodeEntity(
                id = "node-$index",
                conversationId = "stats-test",
                nodeIndex = index,
                messages = messages,
                selectIndex = 0,
            )
        )
    }
}
