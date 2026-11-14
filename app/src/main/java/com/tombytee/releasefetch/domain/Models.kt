package com.tombytee.releasefetch.domain

import androidx.compose.runtime.Immutable

@Immutable
data class DeviceProfile(
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val androidVersion: String,
    val sdkInt: Int,
    val deviceModel: String,
) {
    val is64Bit: Boolean
        get() = supportedAbis.any { it.contains("64") }

    val summaryLabel: String = "$primaryAbi • Android $androidVersion (API $sdkInt)"
}

@Immutable
data class RepoIdentifier(
    val owner: String,
    val repo: String,
) {
    val fullName: String = "$owner/$repo"
    val htmlUrl: String = "https://github.com/$owner/$repo"
}

enum class PlatformCategory(val displayName: String) {
    ALL("All"),
    ANDROID("Android"),
    WINDOWS("Windows"),
    MACOS("macOS"),
    LINUX("Linux"),
    IOS("iOS"),
    OTHER("Other"),
}

@Immutable
data class RawReleaseAsset(
    val id: Long,
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val downloadCount: Int,
    val contentType: String?,
)

@Immutable
data class RawRelease(
    val id: Long,
    val tagName: String,
    val name: String?,
    val publishedAt: String?,
    val isPrerelease: Boolean,
    val body: String?,
    val assets: List<RawReleaseAsset>,
)

@Immutable
data class ClassifiedAsset(
    val id: Long,
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sizeLabel: String,
    val downloadCount: Int,
    val platform: PlatformCategory,
    val fileExtension: String,
    val archLabel: String?,
    val isCompatibleWithDevice: Boolean,
    val isRecommendedForDevice: Boolean,
    val compatibilityNote: String?,
    val compatibilityScore: Int,
    val extensionBadgeLabel: String = ".${fileExtension.uppercase()}",
    val metaLabel: String = "$sizeLabel • $downloadCount downloads",
    val recommendedMetaLabel: String = "$sizeLabel • APK",
)

@Immutable
data class ClassifiedRelease(
    val id: Long,
    val tagName: String,
    val displayTitle: String,
    val publishedDateLabel: String,
    val isPrerelease: Boolean,
    val isLatest: Boolean,
    val releaseNotes: String,
    val assets: List<ClassifiedAsset>,
    val recommendedAsset: ClassifiedAsset?,
    val platformCounts: Map<PlatformCategory, Int>,
    val groupedAssets: Map<PlatformCategory, List<ClassifiedAsset>>,
    val availableCategories: List<PlatformCategory>,
)

@Immutable
data class ReleaseVersionSummary(
    val id: Long,
    val tagName: String,
    val isPrerelease: Boolean,
    val isLatest: Boolean,
)

@Immutable
data class AnalyzedRepository(
    val identifier: RepoIdentifier,
    val canonicalFullName: String,
    val ownerAvatarUrl: String?,
    val rawReleases: List<RawRelease>,
    val classifiedCache: Map<Long, ClassifiedRelease>,
    val latestReleaseIndex: Int = 0,
    val currentPage: Int = 1,
    val hasMoreReleases: Boolean = false,
    val etag: String? = null,
) {
    val versionSummaries: List<ReleaseVersionSummary> =
        rawReleases.mapIndexed { index, raw ->
            ReleaseVersionSummary(
                id = raw.id,
                tagName = raw.tagName,
                isPrerelease = raw.isPrerelease,
                isLatest = index == latestReleaseIndex,
            )
        }

    fun withClassifiedRelease(
        releaseId: Long,
        classified: ClassifiedRelease,
        maxCachedReleases: Int = MAX_CLASSIFIED_RELEASES_IN_MEMORY,
    ): AnalyzedRepository {
        val updated = LinkedHashMap<Long, ClassifiedRelease>(classifiedCache.size + 1)
        for ((k, v) in classifiedCache) {
            if (k != releaseId) updated[k] = v
        }
        updated[releaseId] = classified
        while (updated.size > maxCachedReleases) {
            val oldestKey = updated.entries.firstOrNull()?.key ?: break
            updated.remove(oldestKey)
        }
        return copy(classifiedCache = updated)
    }

    companion object {
        const val MAX_CLASSIFIED_RELEASES_IN_MEMORY = 8
    }
}

enum class DownloadStatus {
    IDLE,
    QUEUED,
    RUNNING,
    PAUSED,
    SUCCESSFUL,
    FAILED,
}

@Immutable
data class DownloadItemState(
    val downloadId: Long,
    val assetId: Long,
    val fileName: String,
    val repoFullName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val progressPercent: Int,
    val localUri: String? = null,
    val errorMessage: String? = null,
    val isIndeterminate: Boolean = false,
)
