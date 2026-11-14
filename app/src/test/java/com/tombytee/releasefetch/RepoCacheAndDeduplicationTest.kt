package com.tombytee.releasefetch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tombytee.releasefetch.data.local.AppDatabase
import com.tombytee.releasefetch.data.local.CachedRepoPageEntity
import com.tombytee.releasefetch.data.remote.GitHubApiService
import com.tombytee.releasefetch.data.remote.GitHubAssetDto
import com.tombytee.releasefetch.data.remote.GitHubOwnerDto
import com.tombytee.releasefetch.data.remote.GitHubReleaseDto
import com.tombytee.releasefetch.data.remote.GitHubReleaseRepository
import com.tombytee.releasefetch.data.remote.GitHubRepoDto
import com.tombytee.releasefetch.data.remote.ReleaseFetchOutcome
import com.tombytee.releasefetch.domain.AssetClassifier
import com.tombytee.releasefetch.domain.DeviceProfile
import com.tombytee.releasefetch.domain.RawRelease
import com.tombytee.releasefetch.domain.RawReleaseAsset
import com.tombytee.releasefetch.domain.RepoIdentifier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepoCacheAndDeduplicationTest {

    private lateinit var database: AppDatabase

    private val testDevice = DeviceProfile(
        primaryAbi = "arm64-v8a",
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        androidVersion = "15",
        sdkInt = 35,
        deviceModel = "Test Phone",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `etag 304 not modified reuses persisted Room metadata without re-downloading releases`() = runBlocking {
        val repoCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)
        var lastIfNoneMatch: String? = null

        val fakeApi = object : GitHubApiService {
            override suspend fun getRepository(owner: String, repo: String): Response<GitHubRepoDto> {
                repoCalls.incrementAndGet()
                return Response.success(
                    GitHubRepoDto(
                        id = 1L,
                        name = repo,
                        fullName = "$owner/$repo",
                        owner = GitHubOwnerDto(login = owner, avatarUrl = null),
                    )
                )
            }

            override suspend fun getReleases(
                owner: String,
                repo: String,
                perPage: Int,
                page: Int,
                ifNoneMatch: String?,
            ): Response<List<GitHubReleaseDto>> {
                val count = releaseCalls.incrementAndGet()
                lastIfNoneMatch = ifNoneMatch
                return if (count == 1) {
                    Response.success(
                        listOf(
                            GitHubReleaseDto(
                                id = 500L,
                                tagName = "v5.0.0",
                                name = "v5.0.0",
                                draft = false,
                                prerelease = false,
                                publishedAt = "2026-11-10T00:00:00Z",
                                body = "Release notes",
                                assets = listOf(
                                    GitHubAssetDto(
                                        id = 501L,
                                        name = "app-arm64-v8a.apk",
                                        browserDownloadUrl = "https://example.com/app.apk",
                                        size = 12_000_000L,
                                        downloadCount = 42,
                                        contentType = "application/vnd.android.package-archive",
                                    )
                                ),
                            )
                        ),
                        Headers.headersOf("ETag", "W/\"etag-v5\""),
                    )
                } else {
                    Response.error(
                        "".toResponseBody("application/json".toMediaType()),
                        okhttp3.Response.Builder()
                            .code(304)
                            .message("Not Modified")
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .header("ETag", "W/\"etag-v5\"")
                            .request(
                                okhttp3.Request.Builder()
                                    .url("https://api.github.com/repos/$owner/$repo/releases")
                                    .build()
                            )
                            .build()
                    )
                }
            }
        }

        // First instance fetches and stores ETag + releases in Room
        val repo1 = GitHubReleaseRepository(
            apiServiceProvider = { fakeApi },
            repoCacheDaoProvider = { database.repoCacheDao() },
        )
        val id = RepoIdentifier("acme", "cached-app")
        val first = repo1.analyzeRepository(id, testDevice)
        assertTrue(first is ReleaseFetchOutcome.Success)
        assertEquals(1, releaseCalls.get())

        // Simulate expired Room TTL so a conditional If-None-Match request is issued by a new repository instance
        val cacheDao = database.repoCacheDao()
        cacheDao.touchTimestamp("acme/cached-app", 1L)

        val repo2 = GitHubReleaseRepository(
            apiServiceProvider = { fakeApi },
            repoCacheDaoProvider = { cacheDao },
        )
        val second = repo2.analyzeRepository(id, testDevice)
        assertTrue(second is ReleaseFetchOutcome.Success)
        val analyzed = (second as ReleaseFetchOutcome.Success).repository
        assertEquals("v5.0.0", analyzed.rawReleases.first().tagName)
        assertEquals("W/\"etag-v5\"", lastIfNoneMatch)
        assertEquals(2, releaseCalls.get())
    }

    @Test
    fun `concurrent requests for same repository and page coalesce into single API call`() = runBlocking {
        val repoCalls = AtomicInteger(0)
        val page1Calls = AtomicInteger(0)
        val page2Calls = AtomicInteger(0)

        val slowFakeApi = object : GitHubApiService {
            override suspend fun getRepository(owner: String, repo: String): Response<GitHubRepoDto> {
                repoCalls.incrementAndGet()
                delay(40)
                return Response.success(
                    GitHubRepoDto(1L, repo, "$owner/$repo", GitHubOwnerDto(owner, null))
                )
            }

            override suspend fun getReleases(
                owner: String,
                repo: String,
                perPage: Int,
                page: Int,
                ifNoneMatch: String?,
            ): Response<List<GitHubReleaseDto>> {
                if (page == 1) page1Calls.incrementAndGet() else page2Calls.incrementAndGet()
                delay(40)
                return Response.success(
                    listOf(
                        GitHubReleaseDto(
                            id = page.toLong() * 100L,
                            tagName = "v$page.0.0",
                            name = "v$page.0.0",
                            draft = false,
                            prerelease = false,
                            publishedAt = "2026-11-10T00:00:00Z",
                            body = null,
                            assets = listOf(
                                GitHubAssetDto(
                                    id = page.toLong() * 1000L,
                                    name = "build-v$page-arm64-v8a.apk",
                                    browserDownloadUrl = "https://example.com/$page.apk",
                                    size = 10_000_000L,
                                    downloadCount = 5,
                                    contentType = null,
                                )
                            ),
                        )
                    )
                )
            }
        }

        val repository = GitHubReleaseRepository(slowFakeApi)
        val id = RepoIdentifier("acme", "dedup-app")

        val analyses = (1..4).map {
            async { repository.analyzeRepository(id, testDevice) }
        }.awaitAll()
        assertTrue(analyses.all { it is ReleaseFetchOutcome.Success })
        assertEquals(1, repoCalls.get())
        assertEquals(1, page1Calls.get())

        val pages = (1..4).map {
            async { repository.fetchReleasesPage(id, page = 2) }
        }.awaitAll()
        assertTrue(pages.all { it.isSuccess })
        assertEquals(1, page2Calls.get())
    }

    @Test
    fun `persistent Room cache prunes older entries cleanly at bounded limit`() = runBlocking {
        val dao = database.repoCacheDao()
        for (i in 1..25) {
            dao.upsert(
                CachedRepoPageEntity(
                    repoKey = "org/repo-$i",
                    canonicalFullName = "org/repo-$i",
                    ownerAvatarUrl = null,
                    etag = null,
                    releasesJson = "{\"releases\":[]}",
                    hasMoreReleases = false,
                    cachedAtMs = i.toLong() * 1000L,
                )
            )
        }
        dao.pruneOldEntries(maxEntries = 20)

        // Oldest 5 entries (1..5) should be evicted; newest (25) remains
        assertEquals(null, dao.getByRepoKey("org/repo-1"))
        assertEquals(null, dao.getByRepoKey("org/repo-5"))
        assertTrue(dao.getByRepoKey("org/repo-6") != null)
        assertTrue(dao.getByRepoKey("org/repo-25") != null)
    }

    @Test
    fun `classifying 100 assets precomputes labels cleanly without errors`() {
        val assets = (1..100).map { idx ->
            RawReleaseAsset(
                id = idx.toLong(),
                name = when {
                    idx == 1 -> "release-arm64-v8a.apk"
                    idx <= 40 -> "release-variant-$idx-armeabi-v7a.apk"
                    idx <= 70 -> "release-win-$idx.exe"
                    else -> "release-linux-$idx.AppImage"
                },
                downloadUrl = "https://example.com/asset-$idx",
                sizeBytes = idx.toLong() * 1_000_000L,
                downloadCount = idx * 10,
                contentType = null,
            )
        }
        val raw = RawRelease(
            id = 999L,
            tagName = "v10.0.0",
            name = "v10.0.0",
            publishedAt = "2026-11-12T12:00:00Z",
            isPrerelease = false,
            body = "x".repeat(6000),
            assets = assets,
        )

        val classified = AssetClassifier.classifySingleRelease(
            raw = raw,
            isLatest = true,
            deviceProfile = testDevice,
        )

        assertEquals(100, classified.assets.size)
        assertEquals("release-arm64-v8a.apk", classified.recommendedAsset?.name)
        assertEquals(".APK", classified.recommendedAsset?.extensionBadgeLabel)
        assertTrue(classified.releaseNotes.length < 4_200)
    }
}
