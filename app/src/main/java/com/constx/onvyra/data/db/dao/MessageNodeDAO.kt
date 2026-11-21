package com.constx.onvyra.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.constx.onvyra.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    // 使用与 messages 相同的 JSON 编码，保守保留所有分支中出现的 URL。
    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE instr(messages, :encodedFileUrl) > 0)")
    suspend fun hasFileReference(encodedFileUrl: String): Boolean

    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query("SELECT id FROM message_node WHERE conversation_id = :conversationId")
    suspend fun getNodeIdsOfConversation(conversationId: String): List<String>

    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId AND id = :nodeId")
    suspend fun getNodeOfConversation(conversationId: String, nodeId: String): MessageNodeEntity?

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: MessageNodeEntity)

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    // 使用 @RawQuery 绕过 Room 编译期校验，以便使用 json_each() 虚拟表
    @RawQuery
    suspend fun getTokenStatsRaw(query: SupportSQLiteQuery): MessageTokenStats

    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>
}

data class MessageTokenStats(
    val totalMessages: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class MessageDayCount(val day: String, val count: Int)

// 在 json_each() 的参数内校验 JSON，避免损坏行导致整个统计查询失败。
// 使用 CASE 而非依赖 WHERE 条件的求值顺序，无效 JSON 按空数组处理。
private const val VALID_MESSAGES_JSON = "CASE WHEN json_valid(mn.messages) THEN " +
    "CASE WHEN json_type(mn.messages) = 'array' THEN mn.messages ELSE '[]' END ELSE '[]' END"
private const val MESSAGE_OBJECT_JSON = "CASE WHEN j.type = 'object' THEN j.value ELSE '{}' END"

// History copied into a fork retains UIMessage.id and does not spend tokens again.
// Keep the largest saved cumulative usage for that identity, including unselected regenerations
// with their own IDs. Legacy records without an ID remain separate by node and array index.
private fun tokenField(field: String) = "CASE WHEN json_type(message, '$.usage.$field') = 'integer' " +
    "AND json_extract(message, '$.usage.$field') >= 0 THEN json_extract(message, '$.usage.$field') ELSE 0 END"

// SQLite json_each() 展开 messages JSON 数组，json_extract() 提取 Token 字段并聚合
private val TOKEN_STATS_SQL = SimpleSQLiteQuery(
    "WITH raw_messages AS (" +
        "SELECT $MESSAGE_OBJECT_JSON AS message, mn.id AS node_id, j.key AS message_index " +
        "FROM message_node mn, json_each($VALID_MESSAGES_JSON) j WHERE j.type = 'object'" +
        "), unique_usage AS (SELECT " +
        "CASE WHEN json_type(message, '$.id') = 'text' AND length(json_extract(message, '$.id')) > 0 " +
        "THEN 'id:' || json_extract(message, '$.id') ELSE 'node:' || node_id || ':' || message_index END AS identity, " +
        "MAX(${tokenField("promptTokens")}) AS prompt, " +
        "MAX(${tokenField("completionTokens")}) AS completion, " +
        "MAX(${tokenField("cachedTokens")}) AS cached " +
        "FROM raw_messages GROUP BY identity) " +
        "SELECT (SELECT COUNT(*) FROM raw_messages) AS totalMessages, " +
        "COALESCE(SUM(prompt), 0) AS promptTokens, COALESCE(SUM(completion), 0) AS completionTokens, " +
        "COALESCE(SUM(cached), 0) AS cachedTokens FROM unique_usage"
)

suspend fun MessageNodeDAO.getTokenStats(): MessageTokenStats = getTokenStatsRaw(TOKEN_STATS_SQL)

// 按用户消息的 createdAt 字段（LocalDateTime ISO 字符串前10位即日期）统计每日消息数
suspend fun MessageNodeDAO.getMessageCountPerDay(startDate: String): List<MessageDayCount> =
    getMessageCountPerDayRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract($MESSAGE_OBJECT_JSON, '$.createdAt'), 1, 10) AS day, " +
                "COUNT(*) AS count " +
                "FROM message_node mn, json_each($VALID_MESSAGES_JSON) j " +
                "WHERE json_extract($MESSAGE_OBJECT_JSON, '$.role') = 'user' " +
                "AND json_extract($MESSAGE_OBJECT_JSON, '$.createdAt') >= ? " +
                "GROUP BY day",
            arrayOf(startDate)
        )
    )
