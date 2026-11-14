package com.tombytee.releasefetch.data.remote

import android.util.LruCache
import com.tombytee.releasefetch.data.local.CachedRepoPageEntity
import com.tombytee.releasefetch.data.local.RepoCacheDao
import com.tombytee.releasefetch.data.local.RepoCacheSerializer
import com.tombytee.releasefetch.domain.AnalyzedRepository
import com.tombytee.releasefetch.domain.AssetClassifier
import com.tombytee.releasefetch.domain.DeviceProfile
import com.tombytee.releasefetch.domain.RawRelease
import com.tombytee.releasefetch.domain.RawReleaseAsset
import com.tombytee.releasefetch.domain.RepoIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

sealed class ReleaseFetchOutcome {
    data class Success(val repository: AnalyzedRepository) : ReleaseFetchOutcome()
    data class NoReleases(
        val identifier: RepoIdentifier,
        val canonicalFullName: String,
        val ownerAvatarUrl: String?,
    ) : ReleaseFetchOutcome()

    data class Failure(val message: String) : ReleaseFetchOutcome()
}

data class ReleasesPageResult(
    val releases: List<RawRelease>,
    val hasMoreReleases: Boolean,
)

interface ReleaseAnalyzerDataSource {
    suspend fun analyzeRepository(
        identifier: RepoIdentifier,
        deviceProfile: DeviceProfile,
    ): ReleaseFetchOutcome

    suspend fun fetchReleasesPage(
        identifier: RepoIdentifier,
        page: Int,
    ): Result<ReleasesPageResult> {
        return Result.success(ReleasesPageResult(emptyList(), hasMoreReleases = false))
    }
}

class GitHubReleaseRepository(
    private val apiServiceProvider: () -> GitHubApiService,
    private val repoCacheDaoProvider: (() -> RepoCacheDao)? = null,
) : ReleaseAnalyzerDataSource {

    constructor(apiService: GitHubApiService) : this({ apiService }, null)

    private data class CachedRepoEntry(
        val repository: AnalyzedRepository,
        val cachedAtMs: Long,
    )

    private data class CachedPageEntry(
        val result: ReleasesPageResult,
        val etag: String?,
        val cachedAtMs: Long,
    )

    private val memoryCache = LruCache<String, CachedRepoEntry>(MAX_CACHED_REPOS)
    private val pageCache = LruCache<String, CachedPageEntry>(MAX_CACHED_PAGES)
    private val inFlightAnalyses = ConcurrentHashMap<String, CompletableDeferred<ReleaseFetchOutcome>>()
    private val inFlightPages = ConcurrentHashMap<String, CompletableDeferred<Result<ReleasesPageResult>>>()

    override suspend fun analyzeRepository(
        identifier: RepoIdentifier,
        deviceProfile: DeviceProfile,
    ): ReleaseFetchOutcome = withContext(Dispatchers.IO) {
        val cacheKey = "${identifier.owner.lowercase()}/${identifier.repo.lowercase()}"
        val now = System.currentTimeMillis()
        synchronized(memoryCache) {
            val cached = memoryCache.get(cacheKey)
            if (cached != null && (now - cached.cachedAtMs) <= CACHE_TTL_MS) {
                return@withContext ReleaseFetchOutcome.Success(cached.repository)
            }
        }

        // Deduplicate concurrent requests for the exact same repository
        while (true) {
            val newDeferred = CompletableDeferred<ReleaseFetchOutcome>()
            val existing = inFlightAnalyses.putIfAbsent(cacheKey, newDeferred)
            if (existing != null) {
                try {
                    return@withContext existing.await()
                } catch (_: CancellationException) {
                    // Previous owner cancelled; retry acquisition
                    continue
                }
            }

            try {
                val outcome = performAnalyzeRepository(identifier, deviceProfile, cacheKey)
                newDeferred.complete(outcome)
                return@withContext outcome
            } catch (c: CancellationException) {
                newDeferred.cancel(c)
                throw c
            } catch (t: Throwable) {
                val failure = ReleaseFetchOutcome.Failure(
                    "Unexpected error while analyzing repository: ${t.localizedMessage ?: "Unknown error"}"
                )
                newDeferred.complete(failure)
                return@withContext failure
            } finally {
                inFlightAnalyses.remove(cacheKey, newDeferred)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ReleaseFetchOutcome.Failure("Unable to analyze repository.")
    }

    private suspend fun performAnalyzeRepository(
        identifier: RepoIdentifier,
        deviceProfile: DeviceProfile,
        cacheKey: String,
    ): ReleaseFetchOutcome {
        val now = System.currentTimeMillis()
        val cacheDao = repoCacheDaoProvider?.invoke()
        val persisted = try {
            cacheDao?.getByRepoKey(cacheKey)
        } catch (_: Exception) {
            null
        }
        val persistedRawReleases = if (persisted != null) {
            withContext(Dispatchers.Default) {
                RepoCacheSerializer.decode(persisted.releasesJson)
            }
        } else {
            null
        }

        if (persisted != null && persistedRawReleases != null && (now - persisted.cachedAtMs) <= CACHE_TTL_MS) {
            val analyzed = buildAnalyzedFromRaw(
                identifier = parseCanonicalIdentifier(persisted.canonicalFullName, identifier),
                canonicalFullName = persisted.canonicalFullName,
                ownerAvatarUrl = persisted.ownerAvatarUrl,
                rawReleases = persistedRawReleases,
                hasMore = persisted.hasMoreReleases,
                etag = persisted.etag,
                deviceProfile = deviceProfile,
            )
            storeInMemoryCache(cacheKey, analyzed, now)
            return ReleaseFetchOutcome.Success(analyzed)
        }

        val conditionalEtag = if (persisted != null && persistedRawReleases != null) persisted.etag else null

        try {
            val apiService = apiServiceProvider()
            val (repoResponse, releasesResponse) = coroutineScope {
                val repoDeferred = async {
                    apiService.getRepository(identifier.owner, identifier.repo)
                }
                val releasesDeferred = async {
                    apiService.getReleases(
                        owner = identifier.owner,
                        repo = identifier.repo,
                        perPage = RELEASES_PAGE_SIZE,
                        page = 1,
                        ifNoneMatch = conditionalEtag,
                    )
                }
                repoDeferred.await() to releasesDeferred.await()
            }

            // Fast path: 304 Not Modified -> reuse cached release metadata with zero JSON re-parsing
            if (releasesResponse.code() == HTTP_NOT_MODIFIED && persisted != null && persistedRawReleases != null) {
                val updatedAt = System.currentTimeMillis()
                val canonicalName = repoResponse.body()?.fullName ?: persisted.canonicalFullName
                val avatarUrl = repoResponse.body()?.owner?.avatarUrl ?: persisted.ownerAvatarUrl
                val canonicalId = parseCanonicalIdentifier(canonicalName, identifier)
                val analyzed = buildAnalyzedFromRaw(
                    identifier = canonicalId,
                    canonicalFullName = canonicalName,
                    ownerAvatarUrl = avatarUrl,
                    rawReleases = persistedRawReleases,
                    hasMore = persisted.hasMoreReleases,
                    etag = releasesResponse.headers()["ETag"] ?: persisted.etag,
                    deviceProfile = deviceProfile,
                )
                storeInMemoryCache(cacheKey, analyzed, updatedAt)
                try {
                    cacheDao?.touchTimestamp(cacheKey, updatedAt)
                } catch (_: Exception) {
                }
                return ReleaseFetchOutcome.Success(analyzed)
            }

            if (!repoResponse.isSuccessful) {
                return ReleaseFetchOutcome.Failure(mapHttpError(repoResponse.code(), identifier))
            }

            val repoDto = repoResponse.body()
                ?: return ReleaseFetchOutcome.Failure("GitHub returned an empty response for ${identifier.fullName}.")

            val canonicalIdentifier = parseCanonicalIdentifier(repoDto.fullName, identifier)

            if (!releasesResponse.isSuccessful) {
                return ReleaseFetchOutcome.Failure(mapHttpError(releasesResponse.code(), canonicalIdentifier))
            }

            val releaseDtos = releasesResponse.body().orEmpty()
                .filter { !it.draft }

            if (releaseDtos.isEmpty()) {
                return ReleaseFetchOutcome.NoReleases(
                    identifier = canonicalIdentifier,
                    canonicalFullName = repoDto.fullName,
                    ownerAvatarUrl = repoDto.owner.avatarUrl,
                )
            }

            val linkHeader = releasesResponse.headers()["Link"]
            val hasMore = linkHeader?.contains("rel=\"next\"") ?: (releaseDtos.size >= RELEASES_PAGE_SIZE)
            val responseEtag = releasesResponse.headers()["ETag"]

            val rawReleases = withContext(Dispatchers.Default) {
                releaseDtos.map { dto -> dto.toRawRelease() }
            }

            val analyzed = buildAnalyzedFromRaw(
                identifier = canonicalIdentifier,
                canonicalFullName = repoDto.fullName,
                ownerAvatarUrl = repoDto.owner.avatarUrl,
                rawReleases = rawReleases,
                hasMore = hasMore,
                etag = responseEtag,
                deviceProfile = deviceProfile,
            )

            val savedAt = System.currentTimeMillis()
            storeInMemoryCache(cacheKey, analyzed, savedAt)
            storePageInMemoryCache(
                pageKey = pageCacheKey(cacheKey, 1),
                result = ReleasesPageResult(releases = rawReleases, hasMoreReleases = hasMore),
                etag = responseEtag,
                timestamp = savedAt,
            )

            if (cacheDao != null) {
                try {
                    val encodedJson = withContext(Dispatchers.Default) {
                        RepoCacheSerializer.encode(rawReleases)
                    }
                    cacheDao.upsert(
                        CachedRepoPageEntity(
                            repoKey = cacheKey,
                            canonicalFullName = repoDto.fullName,
                            ownerAvatarUrl = repoDto.owner.avatarUrl,
                            etag = responseEtag,
                            releasesJson = encodedJson,
                            hasMoreReleases = hasMore,
                            cachedAtMs = savedAt,
                        )
                    )
                    cacheDao.pruneOldEntries(RepoCacheDao.MAX_PERSISTENT_CACHED_REPOS)
                } catch (_: Exception) {
                    // Persistent cache failure must never fail analysis
                }
            }

            return ReleaseFetchOutcome.Success(analyzed)
        } catch (_: IOException) {
            if (persisted != null && persistedRawReleases != null) {
                val fallback = buildAnalyzedFromRaw(
                    identifier = parseCanonicalIdentifier(persisted.canonicalFullName, identifier),
                    canonicalFullName = persisted.canonicalFullName,
                    ownerAvatarUrl = persisted.ownerAvatarUrl,
                    rawReleases = persistedRawReleases,
                    hasMore = persisted.hasMoreReleases,
                    etag = persisted.etag,
                    deviceProfile = deviceProfile,
                )
                storeInMemoryCache(cacheKey, fallback, System.currentTimeMillis())
                return ReleaseFetchOutcome.Success(fallback)
            }
            return ReleaseFetchOutcome.Failure("Unable to reach GitHub. Check your internet connection and try again.")
        } catch (e: Exception) {
            return ReleaseFetchOutcome.Failure("Unexpected error while analyzing repository: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    override suspend fun fetchReleasesPage(
        identifier: RepoIdentifier,
        page: Int,
    ): Result<ReleasesPageResult> = withContext(Dispatchers.IO) {
        val repoKey = "${identifier.owner.lowercase()}/${identifier.repo.lowercase()}"
        val pKey = pageCacheKey(repoKey, page)
        val now = System.currentTimeMillis()

        val cachedEntry = synchronized(pageCache) { pageCache.get(pKey) }
        if (cachedEntry != null && (now - cachedEntry.cachedAtMs) <= CACHE_TTL_MS) {
            return@withContext Result.success(cachedEntry.result)
        }

        while (true) {
            val newDeferred = CompletableDeferred<Result<ReleasesPageResult>>()
            val existing = inFlightPages.putIfAbsent(pKey, newDeferred)
            if (existing != null) {
                try {
                    return@withContext existing.await()
                } catch (_: CancellationException) {
                    continue
                }
            }

            try {
                val result = performFetchReleasesPage(identifier, page, pKey, cachedEntry)
                newDeferred.complete(result)
                return@withContext result
            } catch (c: CancellationException) {
                newDeferred.cancel(c)
                throw c
            } catch (t: Throwable) {
                val failure = Result.failure<ReleasesPageResult>(t)
                newDeferred.complete(failure)
                return@withContext failure
            } finally {
                inFlightPages.remove(pKey, newDeferred)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(IllegalStateException("Unable to load release page"))
    }

    private suspend fun performFetchReleasesPage(
        identifier: RepoIdentifier,
        page: Int,
        pKey: String,
        cachedEntry: CachedPageEntry?,
    ): Result<ReleasesPageResult> {
        return try {
            val apiService = apiServiceProvider()
            val response = apiService.getReleases(
                owner = identifier.owner,
                repo = identifier.repo,
                perPage = RELEASES_PAGE_SIZE,
                page = page,
                ifNoneMatch = cachedEntry?.etag,
            )

            if (response.code() == HTTP_NOT_MODIFIED && cachedEntry != null) {
                val refreshedAt = System.currentTimeMillis()
                storePageInMemoryCache(
                    pageKey = pKey,
                    result = cachedEntry.result,
                    etag = response.headers()["ETag"] ?: cachedEntry.etag,
                    timestamp = refreshedAt,
                )
                return Result.success(cachedEntry.result)
            }

            if (!response.isSuccessful) {
                return Result.failure(IllegalStateException(mapHttpError(response.code(), identifier)))
            }

            val dtos = response.body().orEmpty().filter { !it.draft }
            val linkHeader = response.headers()["Link"]
            val hasMore = linkHeader?.contains("rel=\"next\"") ?: (dtos.size >= RELEASES_PAGE_SIZE)
            val responseEtag = response.headers()["ETag"]
            val mapped = withContext(Dispatchers.Default) {
                dtos.map { it.toRawRelease() }
            }
            val result = ReleasesPageResult(releases = mapped, hasMoreReleases = hasMore)
            storePageInMemoryCache(pKey, result, responseEtag, System.currentTimeMillis())
            Result.success(result)
        } catch (_: IOException) {
            if (cachedEntry != null) {
                Result.success(cachedEntry.result)
            } else {
                Result.failure(IOException("Unable to reach GitHub. Check your internet connection and try again."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun updateCachedRepository(repository: AnalyzedRepository) {
        val key = repository.canonicalFullName.lowercase()
        storeInMemoryCache(key, repository, System.currentTimeMillis())
    }

    private suspend fun buildAnalyzedFromRaw(
        identifier: RepoIdentifier,
        canonicalFullName: String,
        ownerAvatarUrl: String?,
        rawReleases: List<RawRelease>,
        hasMore: Boolean,
        etag: String?,
        deviceProfile: DeviceProfile,
    ): AnalyzedRepository = withContext(Dispatchers.Default) {
        val latestIndex = AssetClassifier.findLatestReleaseIndex(rawReleases)
        val latestRaw = rawReleases[latestIndex]
        val latestClassified = AssetClassifier.classifySingleRelease(
            raw = latestRaw,
            isLatest = true,
            deviceProfile = deviceProfile,
        )
        AnalyzedRepository(
            identifier = identifier,
            canonicalFullName = canonicalFullName,
            ownerAvatarUrl = ownerAvatarUrl,
            rawReleases = rawReleases,
            classifiedCache = mapOf(latestRaw.id to latestClassified),
            latestReleaseIndex = latestIndex,
            currentPage = 1,
            hasMoreReleases = hasMore,
            etag = etag,
        )
    }

    private fun storeInMemoryCache(cacheKey: String, repository: AnalyzedRepository, timestampMs: Long) {
        val entry = CachedRepoEntry(repository, timestampMs)
        synchronized(memoryCache) {
            memoryCache.put(cacheKey, entry)
            memoryCache.put(repository.canonicalFullName.lowercase(), entry)
        }
    }

    private fun storePageInMemoryCache(
        pageKey: String,
        result: ReleasesPageResult,
        etag: String?,
        timestamp: Long,
    ) {
        synchronized(pageCache) {
            pageCache.put(pageKey, CachedPageEntry(result, etag, timestamp))
        }
    }

    private fun pageCacheKey(repoKey: String, page: Int): String = "$repoKey#$page"

    private fun parseCanonicalIdentifier(fullName: String, fallback: RepoIdentifier): RepoIdentifier {
        val parts = fullName.split('/')
        return if (parts.size == 2) {
            RepoIdentifier(owner = parts[0], repo = parts[1])
        } else {
            fallback
        }
    }

    private fun GitHubReleaseDto.toRawRelease(): RawRelease = RawRelease(
        id = id,
        tagName = tagName,
        name = name,
        publishedAt = publishedAt,
        isPrerelease = prerelease,
        body = body,
        assets = assets.map { asset ->
            RawReleaseAsset(
                id = asset.id,
                name = asset.name,
                downloadUrl = asset.browserDownloadUrl,
                sizeBytes = asset.size,
                downloadCount = asset.downloadCount,
                contentType = asset.contentType,
            )
        },
    )

    private fun mapHttpError(code: Int, identifier: RepoIdentifier): String = when (code) {
        404 -> "Repository \"${identifier.fullName}\" was not found or is private."
        403, 429 -> "GitHub API rate limit reached. Wait a few minutes and try again."
        in 500..599 -> "GitHub is temporarily unavailable (HTTP $code). Please retry shortly."
        else -> "GitHub request failed (HTTP $code)."
    }

    companion object {
        const val RELEASES_PAGE_SIZE = 10
        private const val MAX_CACHED_REPOS = 12
        private const val MAX_CACHED_PAGES = 30
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val HTTP_NOT_MODIFIED = 304
    }
}
