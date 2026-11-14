package com.tombytee.releasefetch.data.remote

import com.tombytee.releasefetch.BuildConfig
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
import java.util.concurrent.TimeUnit

interface GitHubApiService {
    @GET("repos/{owner}/{repo}")
    suspend fun getRepository(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): Response<GitHubRepoDto>

    @GET("repos/{owner}/{repo}/releases")
    suspend fun getReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 10,
        @Query("page") page: Int = 1,
        @Header("If-None-Match") ifNoneMatch: String? = null,
    ): Response<List<GitHubReleaseDto>>
}

object NetworkModule {
    private const val BASE_URL = "https://api.github.com/"
    private const val HTTP_CACHE_SIZE_BYTES = 10L * 1024L * 1024L

    @Volatile
    private var cacheDirectory: File? = null

    fun configureCacheDirectory(appCacheDir: File) {
        cacheDirectory = File(appCacheDir, "github_http_cache")
    }

    val gitHubApiService: GitHubApiService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "ReleaseFetch-Android/1.0")
                    .build()
                chain.proceed(request)
            }

        cacheDirectory?.let { dir ->
            builder.cache(Cache(dir, HTTP_CACHE_SIZE_BYTES))
        }

        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(logging)
        }

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(builder.build())
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(GitHubApiService::class.java)
    }

    fun createGitHubApiService(): GitHubApiService = gitHubApiService
}
