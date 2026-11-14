package com.tombytee.releasefetch.data.download

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tombytee.releasefetch.MainActivity
import com.tombytee.releasefetch.R
import com.tombytee.releasefetch.domain.AssetClassifier
import com.tombytee.releasefetch.domain.DownloadItemState
import com.tombytee.releasefetch.domain.DownloadStatus

object ReleaseDownloadNotificationHelper {
    const val CHANNEL_PROGRESS_ID = "releasefetch_download_progress"
    const val CHANNEL_STATUS_ID = "releasefetch_download_status"
    const val FOREGROUND_NOTIFICATION_ID = 1001
    private const val STATUS_NOTIFICATION_BASE_ID = 2000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val progressChannel = NotificationChannel(
            CHANNEL_PROGRESS_ID,
            context.getString(R.string.download_channel_progress_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.download_channel_progress_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }

        val statusChannel = NotificationChannel(
            CHANNEL_STATUS_ID,
            context.getString(R.string.download_channel_status_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.download_channel_status_desc)
            setShowBadge(true)
        }

        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(statusChannel)
    }

    fun buildProgressNotification(
        context: Context,
        activeItems: List<DownloadItemState>,
        fallbackTitle: String? = null,
    ): Notification {
        ensureChannels(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(createOpenAppPendingIntent(context))

        when {
            activeItems.isEmpty() -> {
                builder
                    .setContentTitle(fallbackTitle ?: context.getString(R.string.download_notification_preparing))
                    .setContentText(context.getString(R.string.app_name))
                    .setProgress(0, 0, true)
            }

            activeItems.size == 1 -> {
                val item = activeItems.first()
                val indeterminate = item.isIndeterminate ||
                    (item.status == DownloadStatus.QUEUED && item.downloadedBytes == 0L)
                val downloadedLabel = AssetClassifier.formatFileSize(item.downloadedBytes)
                val progressText = when {
                    item.status == DownloadStatus.QUEUED -> "Queued • ${item.repoFullName}"
                    item.totalBytes > 0L -> {
                        val totalLabel = AssetClassifier.formatFileSize(item.totalBytes)
                        "${item.progressPercent}% • $downloadedLabel / $totalLabel"
                    }
                    else -> downloadedLabel
                }

                builder
                    .setContentTitle(item.fileName)
                    .setContentText(progressText)
                    .setSubText(item.repoFullName)
                    .setProgress(100, item.progressPercent.coerceIn(0, 100), indeterminate)
                    .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        context.getString(R.string.download_action_cancel),
                        createCancelAssetPendingIntent(context, item.assetId),
                    )
            }

            else -> {
                val knownTotal = activeItems.filter { it.totalBytes > 0L }
                val indeterminate = knownTotal.isEmpty()
                val aggregatePercent = if (knownTotal.isNotEmpty()) {
                    val sumDownloaded = knownTotal.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
                    val sumTotal = knownTotal.sumOf { it.totalBytes.coerceAtLeast(1L) }
                    ((sumDownloaded * 100L) / sumTotal).toInt().coerceIn(0, 99)
                } else {
                    0
                }
                val filePreview = activeItems.take(2).joinToString(", ") { it.fileName }
                val summaryText = if (activeItems.size > 2) {
                    "$filePreview +${activeItems.size - 2} more"
                } else {
                    filePreview
                }

                builder
                    .setContentTitle("Downloading ${activeItems.size} files ($aggregatePercent%)")
                    .setContentText(summaryText)
                    .setProgress(100, aggregatePercent, indeterminate)
                    .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        context.getString(R.string.download_action_cancel),
                        createCancelAllPendingIntent(context),
                    )
            }
        }

        return builder.build()
    }

    fun updateProgressNotification(
        context: Context,
        activeItems: List<DownloadItemState>,
    ) {
        if (!canPostNotifications(context)) return
        val notification = buildProgressNotification(context, activeItems)
        notifySafely(context, FOREGROUND_NOTIFICATION_ID, notification)
    }

    fun postCompletionNotification(
        context: Context,
        item: DownloadItemState,
    ) {
        if (!canPostNotifications(context)) return
        ensureChannels(context)

        val sizeText = if (item.totalBytes > 0L) {
            AssetClassifier.formatFileSize(item.totalBytes)
        } else {
            AssetClassifier.formatFileSize(item.downloadedBytes)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.download_notification_complete))
            .setContentText("${item.fileName} ($sizeText)")
            .setSubText(item.repoFullName)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(createOpenDownloadsPendingIntent(context, item.assetId))
            .build()

        notifySafely(context, statusNotificationId(item.assetId), notification)
    }

    fun postFailureNotification(
        context: Context,
        item: DownloadItemState,
        reason: String,
    ) {
        if (!canPostNotifications(context)) return
        ensureChannels(context)

        val detail = "${item.fileName} • $reason"
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.download_notification_failed))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setSubText(item.repoFullName)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(createOpenAppPendingIntent(context))
            .build()

        notifySafely(context, statusNotificationId(item.assetId), notification)
    }

    fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun statusNotificationId(assetId: Long): Int {
        return STATUS_NOTIFICATION_BASE_ID + ((assetId.hashCode() and 0x0FFFFFFF) % 1_000_000)
    }

    private fun notifySafely(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission may have been revoked between check and notify
        }
    }

    private fun createOpenAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createOpenDownloadsPendingIntent(context: Context, assetId: Long): PendingIntent {
        val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val canHandleSystemDownloads = context.packageManager
            ?.resolveActivity(downloadsIntent, 0) != null
        val targetIntent = if (canHandleSystemDownloads) {
            downloadsIntent
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        return PendingIntent.getActivity(
            context,
            statusNotificationId(assetId),
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createCancelAssetPendingIntent(context: Context, assetId: Long): PendingIntent {
        val cancelIntent = ReleaseDownloadService.createCancelIntent(context, assetId)
        val requestCode = (assetId.hashCode() and 0x0FFFFFFF)
        return PendingIntent.getService(
            context,
            requestCode,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createCancelAllPendingIntent(context: Context): PendingIntent {
        val cancelAllIntent = ReleaseDownloadService.createCancelAllIntent(context)
        return PendingIntent.getService(
            context,
            -1,
            cancelAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
