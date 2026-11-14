package com.tombytee.releasefetch

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.tombytee.releasefetch.data.download.DownloadDestination
import com.tombytee.releasefetch.data.download.DownloadStateStore
import com.tombytee.releasefetch.data.download.DownloadStorage
import com.tombytee.releasefetch.data.download.DownloadTransferResult
import com.tombytee.releasefetch.data.download.DownloadTransport
import com.tombytee.releasefetch.data.download.ReleaseDownloadManager
import com.tombytee.releasefetch.data.download.ReleaseDownloadNotificationHelper
import com.tombytee.releasefetch.data.download.ReleaseDownloadService
import com.tombytee.releasefetch.domain.ClassifiedAsset
import com.tombytee.releasefetch.domain.DownloadStatus
import com.tombytee.releasefetch.domain.PlatformCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReleaseDownloadServiceTest {

    private lateinit var app: Application
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        DownloadStateStore.clearAll()
    }

    @After
    fun tearDown() {
        ReleaseDownloadService.transportFactory = {
            com.tombytee.releasefetch.data.download.OkHttpDownloadTransport()
        }
        ReleaseDownloadService.storageFactory = {
            com.tombytee.releasefetch.data.download.PlatformDownloadStorage
        }
        ReleaseDownloadService.ioDispatcher = Dispatchers.IO
        DownloadStateStore.clearAll()
    }

    @Test
    fun `startDownload dispatches foreground service intent and queues asset`() {
        val manager = ReleaseDownloadManager(app)
        val asset = sampleApkAsset(id = 42L, name = "releasefetch-arm64-v8a.apk", sizeBytes = 10_000_000L)

        val result = manager.startDownload(asset, "acme/demo")
        assertTrue(result.isSuccess)

        val queued = DownloadStateStore.get(42L)
        assertNotNull(queued)
        assertEquals(DownloadStatus.QUEUED, queued?.status)
        assertEquals("releasefetch-arm64-v8a.apk", queued?.fileName)

        val startedIntent = Shadows.shadowOf(app).nextStartedService
        assertNotNull(startedIntent)
        assertEquals(ReleaseDownloadService.ACTION_START_DOWNLOAD, startedIntent.action)
        assertEquals(42L, startedIntent.getLongExtra(ReleaseDownloadService.EXTRA_ASSET_ID, -1L))
    }

    @Test
    fun `service downloads apk with progress tracking and completion notification`() = runBlocking {
        val buffer = ByteArrayOutputStream()
        var committed = false

        ReleaseDownloadService.storageFactory = {
            object : DownloadStorage {
                override fun openDestination(context: Context, rawFileName: String): DownloadDestination {
                    return object : DownloadDestination {
                        override val outputStream: OutputStream = buffer
                        override val uriString: String = "content://downloads/public_downloads/99"
                        override fun commit() {
                            committed = true
                        }
                        override fun abort() {}
                    }
                }
            }
        }
        ReleaseDownloadService.transportFactory = {
            object : DownloadTransport {
                override suspend fun downloadToStream(
                    url: String,
                    expectedTotalBytes: Long,
                    outputStream: OutputStream,
                    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
                ): DownloadTransferResult {
                    val half = ByteArray(500) { 1 }
                    outputStream.write(half)
                    onProgress(500L, 1000L)
                    delay(30L)
                    outputStream.write(half)
                    onProgress(1000L, 1000L)
                    return DownloadTransferResult(downloadedBytes = 1000L, totalBytes = 1000L)
                }
            }
        }

        val intent = ReleaseDownloadService.createStartIntent(
            context = app,
            downloadId = 101L,
            assetId = 700L,
            fileName = "app-universal.apk",
            downloadUrl = "https://github.com/acme/demo/releases/download/v1.0/app-universal.apk",
            repoFullName = "acme/demo",
            totalBytes = 1000L,
        )

        val controller = Robolectric.buildService(ReleaseDownloadService::class.java, intent)
            .create()
            .startCommand(0, 1)
        val service = controller.get()

        // Wait for async download coroutine and service shutdown to finish
        waitUntil {
            DownloadStateStore.get(700L)?.status == DownloadStatus.SUCCESSFUL &&
                Shadows.shadowOf(service).isStoppedBySelf
        }

        val completed = DownloadStateStore.get(700L)
        assertNotNull(completed)
        assertEquals(DownloadStatus.SUCCESSFUL, completed?.status)
        assertEquals(100, completed?.progressPercent)
        assertEquals(1000L, completed?.downloadedBytes)
        assertEquals("content://downloads/public_downloads/99", completed?.localUri)
        assertTrue(committed)
        assertEquals(1000, buffer.size())

        val shadowNm = Shadows.shadowOf(notificationManager)
        val channels = shadowNm.notificationChannels
        assertTrue(channels.any { it.id == ReleaseDownloadNotificationHelper.CHANNEL_PROGRESS_ID })
        assertTrue(channels.any { it.id == ReleaseDownloadNotificationHelper.CHANNEL_STATUS_ID })

        val completionNotification = shadowNm.getNotification(
            ReleaseDownloadNotificationHelper.statusNotificationId(700L),
        )
        assertNotNull(completionNotification)
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `cancelling active download aborts destination and removes foreground state`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var aborted = false

        ReleaseDownloadService.storageFactory = {
            object : DownloadStorage {
                override fun openDestination(context: Context, rawFileName: String): DownloadDestination {
                    return object : DownloadDestination {
                        override val outputStream: OutputStream = ByteArrayOutputStream()
                        override val uriString: String? = null
                        override fun commit() {}
                        override fun abort() {
                            aborted = true
                        }
                    }
                }
            }
        }
        ReleaseDownloadService.transportFactory = {
            object : DownloadTransport {
                override suspend fun downloadToStream(
                    url: String,
                    expectedTotalBytes: Long,
                    outputStream: OutputStream,
                    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
                ): DownloadTransferResult {
                    onProgress(200L, 1000L)
                    gate.await()
                    return DownloadTransferResult(1000L, 1000L)
                }
            }
        }

        val startIntent = ReleaseDownloadService.createStartIntent(
            context = app,
            downloadId = 202L,
            assetId = 800L,
            fileName = "cancel-me.apk",
            downloadUrl = "https://github.com/acme/demo/releases/download/v1.0/cancel-me.apk",
            repoFullName = "acme/demo",
            totalBytes = 1000L,
        )

        val controller = Robolectric.buildService(ReleaseDownloadService::class.java, startIntent)
            .create()
            .startCommand(0, 1)
        val service = controller.get()

        waitUntil {
            DownloadStateStore.get(800L)?.status == DownloadStatus.RUNNING
        }

        val cancelIntent = ReleaseDownloadService.createCancelIntent(app, 800L)
        service.onStartCommand(cancelIntent, 0, 2)

        waitUntil {
            aborted && DownloadStateStore.get(800L) == null && Shadows.shadowOf(service).isStoppedBySelf
        }

        assertTrue(aborted)
        assertNull(DownloadStateStore.get(800L))
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `failed download updates state to FAILED and posts failure notification`() = runBlocking {
        var aborted = false

        ReleaseDownloadService.storageFactory = {
            object : DownloadStorage {
                override fun openDestination(context: Context, rawFileName: String): DownloadDestination {
                    return object : DownloadDestination {
                        override val outputStream: OutputStream = ByteArrayOutputStream()
                        override val uriString: String? = null
                        override fun commit() {}
                        override fun abort() {
                            aborted = true
                        }
                    }
                }
            }
        }
        ReleaseDownloadService.transportFactory = {
            object : DownloadTransport {
                override suspend fun downloadToStream(
                    url: String,
                    expectedTotalBytes: Long,
                    outputStream: OutputStream,
                    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
                ): DownloadTransferResult {
                    throw IOException("Network connection reset")
                }
            }
        }

        val startIntent = ReleaseDownloadService.createStartIntent(
            context = app,
            downloadId = 303L,
            assetId = 900L,
            fileName = "broken.apk",
            downloadUrl = "https://github.com/acme/demo/releases/download/v1.0/broken.apk",
            repoFullName = "acme/demo",
            totalBytes = 2048L,
        )

        val controller = Robolectric.buildService(ReleaseDownloadService::class.java, startIntent)
            .create()
            .startCommand(0, 1)
        val service = controller.get()

        waitUntil {
            DownloadStateStore.get(900L)?.status == DownloadStatus.FAILED &&
                Shadows.shadowOf(service).isStoppedBySelf
        }

        val failed = DownloadStateStore.get(900L)
        assertNotNull(failed)
        assertEquals(DownloadStatus.FAILED, failed?.status)
        assertEquals("Network connection reset", failed?.errorMessage)
        assertTrue(aborted)

        val shadowNm = Shadows.shadowOf(notificationManager)
        val failureNotification = shadowNm.getNotification(
            ReleaseDownloadNotificationHelper.statusNotificationId(900L),
        )
        assertNotNull(failureNotification)
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `onTimeout stops active downloads cleanly on Android 15 plus`() = runBlocking {
        val gate = CompletableDeferred<Unit>()

        ReleaseDownloadService.storageFactory = {
            object : DownloadStorage {
                override fun openDestination(context: Context, rawFileName: String): DownloadDestination {
                    return object : DownloadDestination {
                        override val outputStream: OutputStream = ByteArrayOutputStream()
                        override val uriString: String? = null
                        override fun commit() {}
                        override fun abort() {}
                    }
                }
            }
        }
        ReleaseDownloadService.transportFactory = {
            object : DownloadTransport {
                override suspend fun downloadToStream(
                    url: String,
                    expectedTotalBytes: Long,
                    outputStream: OutputStream,
                    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
                ): DownloadTransferResult {
                    gate.await()
                    return DownloadTransferResult(1000L, 1000L)
                }
            }
        }

        val startIntent = ReleaseDownloadService.createStartIntent(
            context = app,
            downloadId = 404L,
            assetId = 950L,
            fileName = "timeout.apk",
            downloadUrl = "https://github.com/acme/demo/releases/download/v1.0/timeout.apk",
            repoFullName = "acme/demo",
            totalBytes = 1000L,
        )

        val service = Robolectric.buildService(ReleaseDownloadService::class.java, startIntent)
            .create()
            .startCommand(0, 1)
            .get()

        waitUntil {
            DownloadStateStore.get(950L)?.status == DownloadStatus.RUNNING
        }

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertEquals(DownloadStatus.FAILED, DownloadStateStore.get(950L)?.status)
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(15L)
        }
    }

    private fun sampleApkAsset(
        id: Long,
        name: String,
        sizeBytes: Long,
    ) = ClassifiedAsset(
        id = id,
        name = name,
        downloadUrl = "https://github.com/acme/demo/releases/download/v1.0/$name",
        sizeBytes = sizeBytes,
        sizeLabel = "10.0 MB",
        downloadCount = 42,
        platform = PlatformCategory.ANDROID,
        fileExtension = "apk",
        archLabel = "arm64-v8a",
        isCompatibleWithDevice = true,
        isRecommendedForDevice = true,
        compatibilityNote = "Best match",
        compatibilityScore = 100,
    )
}
