package com.tombytee.releasefetch.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

data class InstalledAppVersion(
    val versionCode: Long,
    val versionName: String,
)

enum class DownloadQueryStatus {
    PENDING,
    RUNNING,
    PAUSED,
    SUCCESSFUL,
    FAILED,
}

data class DownloadManagerSnapshot(
    val downloadId: Long,
    val status: DownloadQueryStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val failureReason: String? = null,
    val localFilePath: String,
)

data class EnqueuedUpdateDownload(
    val downloadId: Long,
    val localFilePath: String,
)

interface UpdateSystemGateway {
    fun getInstalledAppVersion(): InstalledAppVersion
    fun canRequestPackageInstalls(): Boolean
    fun enqueueUpdateDownload(manifest: UpdateManifest): EnqueuedUpdateDownload
    fun queryDownloadStatus(downloadId: Long, expectedFilePath: String): DownloadManagerSnapshot?
    fun doesApkExist(localFilePath: String): Boolean
    fun removeDownloadAndFile(downloadId: Long, localFilePath: String)
    fun createUnknownAppSourcesIntent(): Intent
    fun createInstallApkIntent(localFilePath: String): Intent?
}

class AndroidUpdateSystemGateway(
    context: Context,
) : UpdateSystemGateway {

    private val appContext = context.applicationContext
    private val downloadManager: DownloadManager? by lazy {
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    }

    override fun getInstalledAppVersion(): InstalledAppVersion {
        return try {
            val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            InstalledAppVersion(
                versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                versionName = packageInfo.versionName ?: "1.0",
            )
        } catch (_: Exception) {
            InstalledAppVersion(versionCode = 1L, versionName = "1.0")
        }
    }

    override fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    override fun enqueueUpdateDownload(manifest: UpdateManifest): EnqueuedUpdateDownload {
        val manager = downloadManager
            ?: throw IllegalStateException("Android DownloadManager is unavailable on this device")

        val relativeSubPath = "updates/releasefetch-v${manifest.versionCode}.apk"
        val downloadsDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val targetFile = if (downloadsDir != null) {
            File(downloadsDir, relativeSubPath)
        } else {
            File(appContext.filesDir, relativeSubPath)
        }
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(manifest.apkUrl))
            .setTitle("ReleaseFetch ${manifest.versionName}")
            .setDescription("Downloading mandatory app update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        if (downloadsDir != null) {
            request.setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                relativeSubPath,
            )
        } else {
            request.setDestinationUri(Uri.fromFile(targetFile))
        }

        val downloadId = manager.enqueue(request)
        return EnqueuedUpdateDownload(
            downloadId = downloadId,
            localFilePath = targetFile.absolutePath,
        )
    }

    override fun queryDownloadStatus(
        downloadId: Long,
        expectedFilePath: String,
    ): DownloadManagerSnapshot? {
        val manager = downloadManager ?: return null
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = try {
            manager.query(query)
        } catch (_: Exception) {
            return null
        } ?: return null

        cursor.use { c ->
            if (!c.moveToFirst()) return null

            val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val bytesCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val reasonCol = c.getColumnIndex(DownloadManager.COLUMN_REASON)
            val localUriCol = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

            val rawStatus = if (statusCol >= 0) c.getInt(statusCol) else DownloadManager.STATUS_FAILED
            val bytesDownloaded = if (bytesCol >= 0) c.getLong(bytesCol).coerceAtLeast(0L) else 0L
            val totalBytes = if (totalCol >= 0) c.getLong(totalCol) else -1L
            val rawReason = if (reasonCol >= 0) c.getInt(reasonCol) else 0
            val localUriString = if (localUriCol >= 0) c.getString(localUriCol) else null

            val resolvedPath = resolveFilePath(localUriString, expectedFilePath)

            val mappedStatus = when (rawStatus) {
                DownloadManager.STATUS_PENDING -> DownloadQueryStatus.PENDING
                DownloadManager.STATUS_RUNNING -> DownloadQueryStatus.RUNNING
                DownloadManager.STATUS_PAUSED -> DownloadQueryStatus.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> DownloadQueryStatus.SUCCESSFUL
                else -> DownloadQueryStatus.FAILED
            }

            val failureReason = if (mappedStatus == DownloadQueryStatus.FAILED) {
                mapDownloadFailureReason(rawReason)
            } else {
                null
            }

            return DownloadManagerSnapshot(
                downloadId = downloadId,
                status = mappedStatus,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                failureReason = failureReason,
                localFilePath = resolvedPath,
            )
        }
    }

    override fun doesApkExist(localFilePath: String): Boolean {
        val file = File(localFilePath)
        return file.exists() && file.isFile && file.length() > 0L
    }

    override fun removeDownloadAndFile(downloadId: Long, localFilePath: String) {
        if (downloadId > 0L) {
            try {
                downloadManager?.remove(downloadId)
            } catch (_: Exception) {
            }
        }
        try {
            val file = File(localFilePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    override fun createUnknownAppSourcesIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${appContext.packageName}")
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    override fun createInstallApkIntent(localFilePath: String): Intent? {
        val apkFile = File(localFilePath)
        if (!apkFile.exists() || apkFile.length() <= 0L) return null

        val contentUri = try {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile,
            )
        } catch (_: Exception) {
            return null
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    private fun resolveFilePath(localUriString: String?, fallbackPath: String): String {
        if (localUriString.isNullOrBlank()) return fallbackPath
        return try {
            val uri = Uri.parse(localUriString)
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                uri.path!!
            } else {
                fallbackPath
            }
        } catch (_: Exception) {
            fallbackPath
        }
    }

    private fun mapDownloadFailureReason(reason: Int): String {
        return when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "Download could not be resumed. Please retry."
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage is unavailable for downloading the update."
            DownloadManager.ERROR_FILE_ERROR -> "Could not write update APK to storage."
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "Network error while downloading the update APK."
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free storage space to download the update."
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Update download link redirected too many times."
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Server rejected the APK download request."
            else -> "Update download failed (code $reason). Please retry."
        }
    }
}
