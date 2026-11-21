package com.constx.onvyra.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.constx.onvyra.data.db.entity.WorkspaceEntity

@Dao
interface WorkspaceDAO {
    @Query("SELECT * FROM workspaces ORDER BY updated_at DESC")
    fun listFlow(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun getById(id: String): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE root = :root")
    suspend fun getByRoot(root: String): WorkspaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workspace: WorkspaceEntity)

    @Query("SELECT * FROM workspaces")
    suspend fun getAll(): List<WorkspaceEntity>

    @Query("UPDATE workspaces SET shell_status = :shellStatus, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long): Int

    @Query("UPDATE workspaces SET shell_compatibility_mode = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long): Int

    @Query("UPDATE workspaces SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long): Int

    @Query("UPDATE workspaces SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long): Int

    @Query("UPDATE workspaces SET tool_approvals = :approvals, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateToolApprovals(id: String, approvals: String, updatedAt: Long): Int

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
