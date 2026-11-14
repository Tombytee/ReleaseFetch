package com.tombytee.releasefetch.data.download

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tombytee.releasefetch.domain.ClassifiedAsset
import com.tombytee.releasefetch.domain.DownloadItemState
import com.tombytee.releasefetch.domain.DownloadStatus
import kotlinx.coroutines.flow.StateFlow

interface DownloadController {
    val downloadsByAssetId: StateFlow<Map<Long, DownloadItemState>>
    fun startDownload(asset: ClassifiedAsset, repoFullName: String): Result<Long>
    fun cancelDownload(assetId: Long) {}
    fun openSystemDownloads(): Boolean
    fun dismissDownloadRecord(assetId: Long)
}

/**
 * Coordinates background asset downloads through [ReleaseDownloadService] and exposes live
 * download progress from [DownloadStateStore].
 */
class ReleaseDownloadManager(
    context: Context,
) : DownloadController {
    private val appContext = context.applicationContext

    override val downloadsByAssetId: StateFlow<Map<Long, DownloadItemState>> =
        DownloadStateStore.downloadsByAssetId

    override fun startDownload(asset: ClassifiedAsset, repoFullName: String): Result<Long> {
        val url = asset.downloadUrl.trim()
        if (!url.startsWith("https://", ignoreCase = true) &&
            !url.startsWith("http://", ignoreCase = true)
        ) {
            return Result.failure(IllegalArgumentException("Unsupported download URL for ${asset.name}."))
        }

        return try {
            val downloadId = DownloadStateStore.nextDownloadId()
            val queuedState = DownloadItemState(
                downloadId = downloadId,
                assetId = asset.id,
                fileName = asset.name,
                repoFullName = repoFullName,
                totalBytes = asset.sizeBytes,
                downloadedBytes = 0L,
                status = DownloadStatus.QUEUED,
                progressPercent = 0,
                isIndeterminate = asset.sizeBytes <= 0L,
            )
            DownloadStateStore.upsert(queuedState)

            val serviceIntent = ReleaseDownloadService.createStartIntent(
                context = appContext,
                downloadId = downloadId,
                assetId = asset.id,
                fileName = asset.name,
                downloadUrl = url,
                repoFullName = repoFullName,
                totalBytes = asset.sizeBytes,
            )
            ContextCompat.startForegroundService(appContext, serviceIntent)
            Result.success(downloadId)
        } catch (e: Exception) {
            DownloadStateStore.update(asset.id) { current ->
                current.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.localizedMessage ?: "Unable to start download service.",
                )
            }
            Result.failure(e)
        }
    }

    override fun cancelDownload(assetId: Long) {
        val existing = DownloadStateStore.get(assetId)
        val isActive = existing != null && (
            existing.status == DownloadStatus.QUEUED ||
                existing.status == DownloadStatus.RUNNING ||
                existing.status == DownloadStatus.PAUSED
            )
        if (isActive) {
            try {
                val cancelIntent = ReleaseDownloadService.createCancelIntent(appContext, assetId)
                appContext.startService(cancelIntent)
            } catch (_: Exception) {
            }
        }
        DownloadStateStore.remove(assetId)
    }

    override fun openSystemDownloads(): Boolean {
        return try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun dismissDownloadRecord(assetId: Long) {
        val existing = DownloadStateStore.get(assetId)
        if (existing != null && (
                existing.status == DownloadStatus.QUEUED ||
                    existing.status == DownloadStatus.RUNNING ||
                    existing.status == DownloadStatus.PAUSED
                )
        ) {
            cancelDownload(assetId)
        } else {
            DownloadStateStore.remove(assetId)
        }
    }
}
