package com.tombytee.releasefetch.data.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.tombytee.releasefetch.domain.DownloadItemState
import com.tombytee.releasefetch.domain.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground Service (`dataSync`) responsible for executing background APK and release asset
 * downloads, tracking byte-level progress, and posting progress/completion notifications.
 */
class ReleaseDownloadService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope by lazy { CoroutineScope(serviceJob + ioDispatcher) }
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private var isForegroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReleaseDownloadNotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopIfIdle(startId)
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START_DOWNLOAD -> {
                val assetId = intent.getLongExtra(EXTRA_ASSET_ID, -1L)
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
                val downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL).orEmpty()
                val repoFullName = intent.getStringExtra(EXTRA_REPO_FULL_NAME).orEmpty()
                val totalBytes = intent.getLongExtra(EXTRA_TOTAL_BYTES, 0L)
                val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                    .takeIf { it > 0L } ?: DownloadStateStore.nextDownloadId()

                // Must promote to foreground immediately upon startForegroundService()
                promoteToForeground(fallbackTitle = fileName.ifBlank { null })

                if (assetId == -1L || fileName.isBlank() || downloadUrl.isBlank()) {
                    stopIfIdle(startId)
                    return START_NOT_STICKY
                }

                enqueueAssetDownload(
                    downloadId = downloadId,
                    assetId = assetId,
                    fileName = fileName,
                    downloadUrl = downloadUrl,
                    repoFullName = repoFullName,
                    expectedTotalBytes = totalBytes,
                )
            }

            ACTION_CANCEL_DOWNLOAD -> {
                val assetId = intent.getLongExtra(EXTRA_ASSET_ID, -1L)
                if (assetId != -1L) {
                    cancelAssetDownload(assetId)
                }
                refreshNotificationOrStop(startId)
            }

            ACTION_CANCEL_ALL -> {
                val keys = activeJobs.keys.toList()
                for (assetId in keys) {
                    cancelAssetDownload(assetId)
                }
                refreshNotificationOrStop(startId)
            }

            else -> {
                stopIfIdle(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync FGS timeout compliance: stop active transfers & stopSelf promptly
        val activeIds = activeJobs.keys.toList()
        for (assetId in activeIds) {
            activeJobs.remove(assetId)?.cancel()
            DownloadStateStore.update(assetId) { existing ->
                existing.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = "Background download time limit reached. Tap Retry to resume.",
                )
            }
            DownloadStateStore.get(assetId)?.let { failed ->
                ReleaseDownloadNotificationHelper.postFailureNotification(
                    context = applicationContext,
                    item = failed,
                    reason = "Background time limit reached",
                )
            }
        }
        stopForegroundCleanly()
        stopSelf(startId)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(fallbackTitle: String? = null) {
        val activeItems = DownloadStateStore.activeItems()
        val notification = ReleaseDownloadNotificationHelper.buildProgressNotification(
            context = applicationContext,
            activeItems = activeItems,
            fallbackTitle = fallbackTitle,
        )
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            ReleaseDownloadNotificationHelper.FOREGROUND_NOTIFICATION_ID,
            notification,
            fgsType,
        )
        isForegroundStarted = true
    }

    private fun enqueueAssetDownload(
        downloadId: Long,
        assetId: Long,
        fileName: String,
        downloadUrl: String,
        repoFullName: String,
        expectedTotalBytes: Long,
    ) {
        // Cancel any duplicate download for the same asset
        activeJobs.remove(assetId)?.cancel()

        val initial = DownloadItemState(
            downloadId = downloadId,
            assetId = assetId,
            fileName = fileName,
            repoFullName = repoFullName,
            totalBytes = expectedTotalBytes,
            downloadedBytes = 0L,
            status = DownloadStatus.QUEUED,
            progressPercent = 0,
            isIndeterminate = expectedTotalBytes <= 0L,
        )
        DownloadStateStore.upsert(initial)
        ReleaseDownloadNotificationHelper.updateProgressNotification(
            context = applicationContext,
            activeItems = DownloadStateStore.activeItems(),
        )

        val job = serviceScope.launch {
            var destination: DownloadDestination? = null
            try {
                downloadSemaphore.withPermit {
                    DownloadStateStore.update(assetId) { current ->
                        current.copy(
                            status = DownloadStatus.RUNNING,
                            errorMessage = null,
                        )
                    }
                    ReleaseDownloadNotificationHelper.updateProgressNotification(
                        context = applicationContext,
                        activeItems = DownloadStateStore.activeItems(),
                    )

                    val opened = storageFactory().openDestination(applicationContext, fileName)
                    destination = opened

                    var lastNotifiedPercent = -1
                    var lastNotifiedAtMs = 0L

                    val result = transportFactory().downloadToStream(
                        url = downloadUrl,
                        expectedTotalBytes = expectedTotalBytes,
                        outputStream = opened.outputStream,
                    ) { downloaded, total ->
                        val effectiveTotal = if (total > 0L) total else expectedTotalBytes
                        val indeterminate = effectiveTotal <= 0L
                        val percent = if (!indeterminate) {
                            ((downloaded * 100L) / effectiveTotal).toInt().coerceIn(0, 99)
                        } else {
                            0
                        }

                        DownloadStateStore.update(assetId) { current ->
                            current.copy(
                                downloadedBytes = downloaded,
                                totalBytes = if (effectiveTotal > 0L) effectiveTotal else current.totalBytes,
                                status = DownloadStatus.RUNNING,
                                progressPercent = percent,
                                isIndeterminate = indeterminate,
                            )
                        }

                        val now = System.currentTimeMillis()
                        if (percent != lastNotifiedPercent &&
                            (now - lastNotifiedAtMs >= NOTIFICATION_UPDATE_INTERVAL_MS || percent - lastNotifiedPercent >= 5)
                        ) {
                            lastNotifiedPercent = percent
                            lastNotifiedAtMs = now
                            ReleaseDownloadNotificationHelper.updateProgressNotification(
                                context = applicationContext,
                                activeItems = DownloadStateStore.activeItems(),
                            )
                        }
                    }

                    opened.commit()

                    val completedItem = DownloadStateStore.get(assetId)?.copy(
                        downloadedBytes = result.downloadedBytes,
                        totalBytes = result.totalBytes,
                        status = DownloadStatus.SUCCESSFUL,
                        progressPercent = 100,
                        localUri = opened.uriString,
                        errorMessage = null,
                        isIndeterminate = false,
                    ) ?: DownloadItemState(
                        downloadId = downloadId,
                        assetId = assetId,
                        fileName = fileName,
                        repoFullName = repoFullName,
                        totalBytes = result.totalBytes,
                        downloadedBytes = result.downloadedBytes,
                        status = DownloadStatus.SUCCESSFUL,
                        progressPercent = 100,
                        localUri = opened.uriString,
                    )

                    ReleaseDownloadNotificationHelper.postCompletionNotification(
                        context = applicationContext,
                        item = completedItem,
                    )
                    DownloadStateStore.upsert(completedItem)
                }
            } catch (e: CancellationException) {
                destination?.abort()
                DownloadStateStore.remove(assetId)
                throw e
            } catch (e: Exception) {
                destination?.abort()
                val reason = e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Download error"
                val failedItem = DownloadStateStore.get(assetId)?.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = reason,
                    isIndeterminate = false,
                ) ?: DownloadItemState(
                    downloadId = downloadId,
                    assetId = assetId,
                    fileName = fileName,
                    repoFullName = repoFullName,
                    totalBytes = expectedTotalBytes,
                    downloadedBytes = 0L,
                    status = DownloadStatus.FAILED,
                    progressPercent = 0,
                    errorMessage = reason,
                )
                ReleaseDownloadNotificationHelper.postFailureNotification(
                    context = applicationContext,
                    item = failedItem,
                    reason = reason,
                )
                DownloadStateStore.upsert(failedItem)
            } finally {
                activeJobs.remove(assetId)
                refreshNotificationOrStop(startId = null)
            }
        }

        activeJobs[assetId] = job
    }

    private fun cancelAssetDownload(assetId: Long) {
        val job = activeJobs.remove(assetId)
        job?.cancel()
        DownloadStateStore.remove(assetId)
    }

    private fun refreshNotificationOrStop(startId: Int?) {
        val remainingActive = DownloadStateStore.activeItems()
        if (activeJobs.isEmpty() && remainingActive.isEmpty()) {
            stopForegroundCleanly()
            if (startId != null) {
                stopSelf(startId)
            } else {
                stopSelf()
            }
        } else if (isForegroundStarted) {
            ReleaseDownloadNotificationHelper.updateProgressNotification(
                context = applicationContext,
                activeItems = remainingActive,
            )
        }
    }

    private fun stopIfIdle(startId: Int) {
        if (activeJobs.isEmpty()) {
            stopForegroundCleanly()
            stopSelf(startId)
        }
    }

    private fun stopForegroundCleanly() {
        if (isForegroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
    }

    companion object {
        const val ACTION_START_DOWNLOAD = "com.tombytee.releasefetch.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.tombytee.releasefetch.action.CANCEL_DOWNLOAD"
        const val ACTION_CANCEL_ALL = "com.tombytee.releasefetch.action.CANCEL_ALL"

        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_ASSET_ID = "extra_asset_id"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_DOWNLOAD_URL = "extra_download_url"
        const val EXTRA_REPO_FULL_NAME = "extra_repo_full_name"
        const val EXTRA_TOTAL_BYTES = "extra_total_bytes"

        private const val MAX_CONCURRENT_DOWNLOADS = 2
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 400L

        @Volatile
        internal var transportFactory: () -> DownloadTransport = { OkHttpDownloadTransport() }

        @Volatile
        internal var storageFactory: () -> DownloadStorage = { PlatformDownloadStorage }

        @Volatile
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        fun createStartIntent(
            context: Context,
            downloadId: Long,
            assetId: Long,
            fileName: String,
            downloadUrl: String,
            repoFullName: String,
            totalBytes: Long,
        ): Intent = Intent(context, ReleaseDownloadService::class.java).apply {
            action = ACTION_START_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            putExtra(EXTRA_ASSET_ID, assetId)
            putExtra(EXTRA_FILE_NAME, fileName)
            putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
            putExtra(EXTRA_REPO_FULL_NAME, repoFullName)
            putExtra(EXTRA_TOTAL_BYTES, totalBytes)
        }

        fun createCancelIntent(context: Context, assetId: Long): Intent =
            Intent(context, ReleaseDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_ASSET_ID, assetId)
            }

        fun createCancelAllIntent(context: Context): Intent =
            Intent(context, ReleaseDownloadService::class.java).apply {
                action = ACTION_CANCEL_ALL
            }
    }
}
