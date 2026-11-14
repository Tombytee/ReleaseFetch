package com.tombytee.releasefetch.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GitHubOwnerDto(
    @param:Json(name = "login") val login: String,
    @param:Json(name = "avatar_url") val avatarUrl: String? = null,
)

@JsonClass(generateAdapter = true)
data class GitHubRepoDto(
    @param:Json(name = "id") val id: Long,
    @param:Json(name = "name") val name: String,
    @param:Json(name = "full_name") val fullName: String,
    @param:Json(name = "owner") val owner: GitHubOwnerDto,
)

@JsonClass(generateAdapter = true)
data class GitHubAssetDto(
    @param:Json(name = "id") val id: Long,
    @param:Json(name = "name") val name: String,
    @param:Json(name = "browser_download_url") val browserDownloadUrl: String,
    @param:Json(name = "size") val size: Long,
    @param:Json(name = "download_count") val downloadCount: Int = 0,
    @param:Json(name = "content_type") val contentType: String? = null,
)

@JsonClass(generateAdapter = true)
data class GitHubReleaseDto(
    @param:Json(name = "id") val id: Long,
    @param:Json(name = "tag_name") val tagName: String,
    @param:Json(name = "name") val name: String? = null,
    @param:Json(name = "draft") val draft: Boolean = false,
    @param:Json(name = "prerelease") val prerelease: Boolean = false,
    @param:Json(name = "published_at") val publishedAt: String? = null,
    @param:Json(name = "body") val body: String? = null,
    @param:Json(name = "assets") val assets: List<GitHubAssetDto> = emptyList(),
)
