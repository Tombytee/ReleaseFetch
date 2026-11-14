package com.tombytee.releasefetch.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedRepoDao {
    @Query("SELECT * FROM saved_repos WHERE isPinned = 1 ORDER BY lastAnalyzedAt DESC")
    fun observePinnedRepos(): Flow<List<SavedRepoEntity>>

    @Query("SELECT * FROM saved_repos WHERE isPinned = 0 ORDER BY lastAnalyzedAt DESC LIMIT 15")
    fun observeRecentRepos(): Flow<List<SavedRepoEntity>>

    @Query("SELECT * FROM saved_repos WHERE fullName = :fullName LIMIT 1")
    suspend fun getByFullName(fullName: String): SavedRepoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(repo: SavedRepoEntity)

    @Query("UPDATE saved_repos SET isPinned = :pinned WHERE fullName = :fullName")
    suspend fun setPinned(fullName: String, pinned: Boolean)

    @Query("DELETE FROM saved_repos WHERE fullName = :fullName")
    suspend fun deleteByFullName(fullName: String)

    @Query("DELETE FROM saved_repos WHERE isPinned = 0")
    suspend fun clearUnpinnedHistory()
}
