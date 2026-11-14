package com.tombytee.releasefetch.data.local

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "saved_repos")
data class SavedRepoEntity(
    @PrimaryKey val fullName: String,
    val owner: String,
    val repoName: String,
    val avatarUrl: String? = null,
    val isPinned: Boolean = false,
    val latestTag: String? = null,
    val assetCount: Int = 0,
    val hasReleases: Boolean = true,
    val lastAnalyzedAt: Long = System.currentTimeMillis(),
)
