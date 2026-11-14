package com.tombytee.releasefetch.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RepoCacheDao {
    @Query("SELECT * FROM cached_repo_pages WHERE repoKey = :repoKey LIMIT 1")
    suspend fun getByRepoKey(repoKey: String): CachedRepoPageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CachedRepoPageEntity)

    @Query("UPDATE cached_repo_pages SET cachedAtMs = :timestampMs WHERE repoKey = :repoKey")
    suspend fun touchTimestamp(repoKey: String, timestampMs: Long)

    @Query(
        "DELETE FROM cached_repo_pages WHERE repoKey NOT IN " +
            "(SELECT repoKey FROM cached_repo_pages ORDER BY cachedAtMs DESC LIMIT :maxEntries)"
    )
    suspend fun pruneOldEntries(maxEntries: Int = MAX_PERSISTENT_CACHED_REPOS)

    companion object {
        const val MAX_PERSISTENT_CACHED_REPOS = 20
    }
}
