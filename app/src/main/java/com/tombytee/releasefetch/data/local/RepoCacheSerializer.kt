package com.tombytee.releasefetch.data.local

import com.tombytee.releasefetch.domain.RawRelease
import com.tombytee.releasefetch.domain.RawReleaseAsset
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

@JsonClass(generateAdapter = true)
data class CachedRawAssetDto(
    val id: Long,
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val downloadCount: Int,
    val contentType: String?,
)

@JsonClass(generateAdapter = true)
data class CachedRawReleaseDto(
    val id: Long,
    val tagName: String,
    val name: String?,
    val publishedAt: String?,
    val isPrerelease: Boolean,
    val body: String?,
    val assets: List<CachedRawAssetDto>,
)

@JsonClass(generateAdapter = true)
data class CachedRawReleasePage(
    val releases: List<CachedRawReleaseDto>,
)

object RepoCacheSerializer {
    private val adapter by lazy {
        Moshi.Builder().build().adapter(CachedRawReleasePage::class.java)
    }

    fun encode(releases: List<RawRelease>): String {
        val payload = CachedRawReleasePage(
            releases = releases.map { release ->
                CachedRawReleaseDto(
                    id = release.id,
                    tagName = release.tagName,
                    name = release.name,
                    publishedAt = release.publishedAt,
                    isPrerelease = release.isPrerelease,
                    body = release.body,
                    assets = release.assets.map { asset ->
                        CachedRawAssetDto(
                            id = asset.id,
                            name = asset.name,
                            downloadUrl = asset.downloadUrl,
                            sizeBytes = asset.sizeBytes,
                            downloadCount = asset.downloadCount,
                            contentType = asset.contentType,
                        )
                    },
                )
            },
        )
        return adapter.toJson(payload)
    }

    fun decode(json: String): List<RawRelease>? {
        return try {
            val payload = adapter.fromJson(json) ?: return null
            payload.releases.map { release ->
                RawRelease(
                    id = release.id,
                    tagName = release.tagName,
                    name = release.name,
                    publishedAt = release.publishedAt,
                    isPrerelease = release.isPrerelease,
                    body = release.body,
                    assets = release.assets.map { asset ->
                        RawReleaseAsset(
                            id = asset.id,
                            name = asset.name,
                            downloadUrl = asset.downloadUrl,
                            sizeBytes = asset.sizeBytes,
                            downloadCount = asset.downloadCount,
                            contentType = asset.contentType,
                        )
                    },
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
