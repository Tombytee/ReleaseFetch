package com.tombytee.releasefetch.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_repo_pages")
data class CachedRepoPageEntity(
    @PrimaryKey val repoKey: String,
    val canonicalFullName: String,
    val ownerAvatarUrl: String?,
    val etag: String?,
    val releasesJson: String,
    val hasMoreReleases: Boolean,
    val cachedAtMs: Long,
)
