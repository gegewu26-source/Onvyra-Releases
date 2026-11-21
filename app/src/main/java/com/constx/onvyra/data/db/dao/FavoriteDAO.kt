package com.constx.onvyra.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.constx.onvyra.data.db.entity.FavoriteEntity

@Dao
interface FavoriteDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("SELECT * FROM favorites WHERE NOT EXISTS (SELECT 1 FROM conversationentity AS protected_chat WHERE protected_chat.is_locked = 1 AND favorites.ref_key LIKE 'node:' || protected_chat.id || ':%') ORDER BY created_at DESC")
    fun listAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE type = :type AND NOT EXISTS (SELECT 1 FROM conversationentity AS protected_chat WHERE protected_chat.is_locked = 1 AND favorites.ref_key LIKE 'node:' || protected_chat.id || ':%') ORDER BY created_at DESC")
    fun listByType(type: String): Flow<List<FavoriteEntity>>

    @Query("SELECT ref_key FROM favorites WHERE type = :type")
    suspend fun getRefKeysByType(type: String): List<String>

    @Query("SELECT substr(ref_key, length('node:' || :conversationId || ':') + 1) FROM favorites WHERE ref_key LIKE 'node:' || :conversationId || ':%'")
    suspend fun getFavoriteNodeIdsOfConversation(conversationId: String): List<String>

    @Query("SELECT * FROM favorites WHERE ref_key = :refKey AND NOT EXISTS (SELECT 1 FROM conversationentity AS protected_chat WHERE protected_chat.is_locked = 1 AND favorites.ref_key LIKE 'node:' || protected_chat.id || ':%') LIMIT 1")
    suspend fun getByRefKey(refKey: String): FavoriteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE ref_key = :refKey)")
    suspend fun existsByRefKey(refKey: String): Boolean

    @Query("DELETE FROM favorites WHERE ref_key = :refKey")
    suspend fun deleteByRefKey(refKey: String): Int

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
