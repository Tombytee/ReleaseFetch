package com.tombytee.releasefetch.data.local

import kotlinx.coroutines.flow.Flow

class RepoHistoryRepository(
    private val daoProvider: () -> SavedRepoDao,
) {
    constructor(dao: SavedRepoDao) : this({ dao })

    private val dao: SavedRepoDao
        get() = daoProvider()

    val pinnedRepos: Flow<List<SavedRepoEntity>> by lazy { dao.observePinnedRepos() }
    val recentRepos: Flow<List<SavedRepoEntity>> by lazy { dao.observeRecentRepos() }

    suspend fun recordAnalysis(
        fullName: String,
        owner: String,
        repoName: String,
        avatarUrl: String?,
        latestTag: String?,
        assetCount: Int,
        hasReleases: Boolean,
    ) {
        val existing = dao.getByFullName(fullName)
        val entry = SavedRepoEntity(
            fullName = fullName,
            owner = owner,
            repoName = repoName,
            avatarUrl = avatarUrl ?: existing?.avatarUrl,
            isPinned = existing?.isPinned ?: false,
            latestTag = latestTag,
            assetCount = assetCount,
            hasReleases = hasReleases,
            lastAnalyzedAt = System.currentTimeMillis(),
        )
        dao.upsert(entry)
    }

    suspend fun togglePin(fullName: String, currentlyPinned: Boolean) {
        dao.setPinned(fullName, !currentlyPinned)
    }

    suspend fun removeRepo(fullName: String) {
        dao.deleteByFullName(fullName)
    }

    suspend fun clearRecentHistory() {
        dao.clearUnpinnedHistory()
    }
}
