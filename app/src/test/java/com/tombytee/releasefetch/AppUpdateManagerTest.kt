package com.tombytee.releasefetch

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tombytee.releasefetch.data.update.AppUpdateManager
import com.tombytee.releasefetch.data.update.AppUpdateUiState
import com.tombytee.releasefetch.data.update.DownloadManagerSnapshot
import com.tombytee.releasefetch.data.update.DownloadQueryStatus
import com.tombytee.releasefetch.data.update.EnqueuedUpdateDownload
import com.tombytee.releasefetch.data.update.InstalledAppVersion
import com.tombytee.releasefetch.data.update.ManifestInvalidReason
import com.tombytee.releasefetch.data.update.ManifestValidationResult
import com.tombytee.releasefetch.data.update.PersistedUpdateDownload
import com.tombytee.releasefetch.data.update.UpdateLaunchEffect
import com.tombytee.releasefetch.data.update.UpdateManifest
import com.tombytee.releasefetch.data.update.UpdateManifestFetcher
import com.tombytee.releasefetch.data.update.UpdateManifestValidator
import com.tombytee.releasefetch.data.update.UpdateStateStore
import com.tombytee.releasefetch.data.update.UpdateStep
import com.tombytee.releasefetch.data.update.UpdateSystemGateway
import com.tombytee.releasefetch.ui.components.MandatoryUpdateScreen
import com.tombytee.releasefetch.ui.theme.ReleaseFetchTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateManagerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `manifest validator accepts valid manifest and rejects placeholder or malformed urls`() {
        val validJson = """
            {
              "versionName": "2.1.0",
              "versionCode": 5,
              "apkUrl": "https://github.com/Tombytee/ReleaseFetch-Update/releases/download/v2.1.0/releasefetch.apk",
              "releaseNotes": "Performance boosts and bug fixes."
            }
        """.trimIndent()
        val valid = UpdateManifestValidator.parseAndValidate(validJson)
        assertTrue(valid is ManifestValidationResult.Valid)
        val manifest = (valid as ManifestValidationResult.Valid).manifest
        assertEquals("2.1.0", manifest.versionName)
        assertEquals(5L, manifest.versionCode)

        val placeholderUrls = listOf(
            "https://example.com/app.apk",
            "https://github.com/Tombytee/ReleaseFetch-Update/releases/download/v1.0/PLACEHOLDER.apk",
            "https://github.com/<your-owner>/<your-repo>/app.apk",
            "",
        )
        for (url in placeholderUrls) {
            val result = UpdateManifestValidator.parseAndValidate(
                """{"versionName":"2.0","versionCode":2,"apkUrl":"$url","releaseNotes":"test"}""",
            )
            assertTrue("Expected Invalid for $url", result is ManifestValidationResult.Invalid)
            assertEquals(
                ManifestInvalidReason.PLACEHOLDER_URL,
                (result as ManifestValidationResult.Invalid).reason,
            )
        }

        val httpResult = UpdateManifestValidator.parseAndValidate(
            """{"versionName":"2.0","versionCode":2,"apkUrl":"http://github.com/app.apk","releaseNotes":"test"}""",
        )
        assertEquals(
            ManifestInvalidReason.INVALID_URL,
            (httpResult as ManifestValidationResult.Invalid).reason,
        )

        val badJson = UpdateManifestValidator.parseAndValidate("{not valid json")
        assertEquals(
            ManifestInvalidReason.MALFORMED_JSON,
            (badJson as ManifestValidationResult.Invalid).reason,
        )
    }

    @Test
    fun `automatic errors fail silently while manual check emits snackbar and keeps app unlocked`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateway = FakeUpdateGateway(installedVersionCode = 1L, installedVersionName = "1.0")
        val store = InMemoryUpdateStateStore()
        var fetcherResult: Result<String> = Result.failure(IOException("Offline"))

        val manager = AppUpdateManager(
            gateway = gateway,
            stateStore = store,
            manifestFetcher = UpdateManifestFetcher { fetcherResult },
            ioDispatcher = dispatcher,
            pollIntervalMs = 50L,
            scope = TestScope(dispatcher),
        )

        val snackbars = mutableListOf<String>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.snackbarEvents.collect { snackbars += it }
        }

        // Automatic foreground check fails silently
        manager.onAppForegrounded()
        advanceUntilIdle()
        assertEquals(AppUpdateUiState.UpToDate, manager.uiState.value)
        assertTrue(snackbars.isEmpty())

        // Manual check surfaces non-blocking snackbar on network error
        manager.checkForUpdatesManually()
        advanceUntilIdle()
        assertEquals(AppUpdateUiState.UpToDate, manager.uiState.value)
        assertEquals(1, snackbars.size)
        assertTrue(snackbars.first().contains("Could not check for updates"))

        // Manual check surfaces non-blocking snackbar on placeholder URL
        fetcherResult = Result.success(
            """{"versionName":"2.0","versionCode":2,"apkUrl":"https://example.com/placeholder.apk","releaseNotes":"notes"}""",
        )
        manager.checkForUpdatesManually()
        advanceUntilIdle()
        assertEquals(AppUpdateUiState.UpToDate, manager.uiState.value)
        assertEquals(2, snackbars.size)
        assertTrue(snackbars.last().contains("not published yet"))

        collectJob.cancel()
    }

    @Test
    fun `mandatory update prevents duplicate downloads and guides unknown sources to installer and cleans up on upgrade`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateway = FakeUpdateGateway(
            installedVersionCode = 1L,
            installedVersionName = "1.0",
            canInstallUnknownApps = false,
        )
        val store = InMemoryUpdateStateStore()
        val manifestJson = """
            {
              "versionName": "2.0.0",
              "versionCode": 2,
              "apkUrl": "https://github.com/Tombytee/ReleaseFetch-Update/releases/download/v2.0.0/ReleaseFetch.apk",
              "releaseNotes": "- Mandatory security update\n- Faster release parsing"
            }
        """.trimIndent()

        val manager = AppUpdateManager(
            gateway = gateway,
            stateStore = store,
            manifestFetcher = UpdateManifestFetcher { Result.success(manifestJson) },
            ioDispatcher = dispatcher,
            pollIntervalMs = 25L,
            scope = TestScope(dispatcher),
        )

        val effects = mutableListOf<UpdateLaunchEffect>()
        val effectJob = backgroundScope.launch(dispatcher) {
            manager.launchEffects.collect { effects += it }
        }

        // Initial launch triggers download
        manager.onAppForegrounded()
        testScheduler.advanceTimeBy(10L)
        testScheduler.runCurrent()

        assertEquals(1, gateway.enqueueCount)
        assertTrue(manager.uiState.value is AppUpdateUiState.UpdateRequired)
        assertTrue((manager.uiState.value as AppUpdateUiState.UpdateRequired).step is UpdateStep.Downloading)

        // Returning to foreground again for the same version must NOT enqueue a second download
        manager.onAppForegrounded()
        testScheduler.advanceTimeBy(10L)
        testScheduler.runCurrent()
        assertEquals(1, gateway.enqueueCount)

        // Download finishes -> missing unknown apps permission -> emits OpenUnknownAppsSettings
        gateway.completeActiveDownload()
        testScheduler.advanceTimeBy(40L)
        testScheduler.runCurrent()

        val permState = manager.uiState.value as AppUpdateUiState.UpdateRequired
        assertEquals(UpdateStep.PermissionRequired, permState.step)
        assertEquals(listOf(UpdateLaunchEffect.OpenUnknownAppsSettings), effects)

        // User enables permission in Android settings and returns -> auto-continues into PackageInstaller
        gateway.canInstallUnknownApps = true
        manager.onAppResumed()
        testScheduler.runCurrent()

        val installingState = manager.uiState.value as AppUpdateUiState.UpdateRequired
        assertEquals(UpdateStep.Installing, installingState.step)
        assertTrue(effects.last() is UpdateLaunchEffect.LaunchPackageInstaller)

        // User cancels installer -> stays locked in ReadyToInstall(wasCancelledOrFailed = true)
        manager.onInstallerResultReturned()
        testScheduler.runCurrent()

        val cancelledState = manager.uiState.value as AppUpdateUiState.UpdateRequired
        assertEquals(UpdateStep.ReadyToInstall(wasCancelledOrFailed = true), cancelledState.step)

        // After user installs new APK (versionCode becomes 2), foreground check cleans up APK & prefs and unlocks
        gateway.installedVersionCode = 2L
        gateway.installedVersionName = "2.0.0"
        manager.onAppForegrounded()
        advanceUntilIdle()

        assertEquals(AppUpdateUiState.UpToDate, manager.uiState.value)
        assertNull(store.readPersistedDownload())
        assertTrue(gateway.removedDownloadIds.contains(101L))
        assertFalse(gateway.apkExistsOnDisk)

        effectJob.cancel()
    }

    @Test
    fun `mandatory update screen locks back navigation and invokes retry callbacks`() {
        val gateway = FakeUpdateGateway(installedVersionCode = 1L, installedVersionName = "1.0")
        var retryClicks = 0
        var installClicks = 0

        val state = AppUpdateUiState.UpdateRequired(
            installedVersionName = "1.0",
            installedVersionCode = 1L,
            manifest = UpdateManifest(
                versionName = "2.2.0",
                versionCode = 4L,
                apkUrl = "https://github.com/Tombytee/ReleaseFetch-Update/releases/download/v2.2.0/app.apk",
                releaseNotes = "Critical fix for asset downloads.",
            ),
            step = UpdateStep.ReadyToInstall(wasCancelledOrFailed = true),
        )

        composeTestRule.setContent {
            ReleaseFetchTheme(dynamicColor = false) {
                MandatoryUpdateScreen(
                    state = state,
                    launchEffects = kotlinx.coroutines.flow.emptyFlow(),
                    systemGateway = gateway,
                    onAllowUnknownSourcesClicked = {},
                    onInstallClicked = { installClicks++ },
                    onInstallerResultReturned = {},
                    onUnknownSourcesResultReturned = {},
                    onRetryDownloadClicked = { retryClicks++ },
                )
            }
        }

        composeTestRule.onNodeWithTag("mandatory_update_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("update_release_notes").assertIsDisplayed()
        composeTestRule.onNodeWithText("v2.2.0 (4)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("update_install_button")
            .performScrollTo()
            .performClick()
        assertEquals(1, installClicks)

        // Triggering system back dispatcher must NOT close or finish the Activity
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        assertFalse(composeTestRule.activity.isFinishing)
        composeTestRule.onNodeWithTag("mandatory_update_screen").assertIsDisplayed()
    }

    private class InMemoryUpdateStateStore : UpdateStateStore {
        private var record: PersistedUpdateDownload? = null

        override fun readPersistedDownload(): PersistedUpdateDownload? = record

        override fun savePersistedDownload(record: PersistedUpdateDownload) {
            this.record = record
        }

        override fun clearPersistedDownload() {
            this.record = null
        }
    }

    private class FakeUpdateGateway(
        var installedVersionCode: Long,
        var installedVersionName: String,
        var canInstallUnknownApps: Boolean = true,
    ) : UpdateSystemGateway {
        var enqueueCount = 0
        var apkExistsOnDisk = false
        val removedDownloadIds = mutableListOf<Long>()
        private var snapshot: DownloadManagerSnapshot? = null
        private var nextDownloadId = 101L

        override fun getInstalledAppVersion(): InstalledAppVersion =
            InstalledAppVersion(installedVersionCode, installedVersionName)

        override fun canRequestPackageInstalls(): Boolean = canInstallUnknownApps

        override fun enqueueUpdateDownload(manifest: UpdateManifest): EnqueuedUpdateDownload {
            enqueueCount++
            val id = nextDownloadId++
            val path = "/tmp/releasefetch-v${manifest.versionCode}.apk"
            snapshot = DownloadManagerSnapshot(
                downloadId = id,
                status = DownloadQueryStatus.RUNNING,
                bytesDownloaded = 5_000_000L,
                totalBytes = 10_000_000L,
                localFilePath = path,
            )
            apkExistsOnDisk = false
            return EnqueuedUpdateDownload(id, path)
        }

        fun completeActiveDownload() {
            val current = snapshot ?: return
            apkExistsOnDisk = true
            snapshot = current.copy(
                status = DownloadQueryStatus.SUCCESSFUL,
                bytesDownloaded = current.totalBytes,
            )
        }

        override fun queryDownloadStatus(downloadId: Long, expectedFilePath: String): DownloadManagerSnapshot? {
            return snapshot?.takeIf { it.downloadId == downloadId }
        }

        override fun doesApkExist(localFilePath: String): Boolean = apkExistsOnDisk

        override fun removeDownloadAndFile(downloadId: Long, localFilePath: String) {
            removedDownloadIds += downloadId
            if (snapshot?.downloadId == downloadId) {
                snapshot = null
            }
            apkExistsOnDisk = false
        }

        override fun createUnknownAppSourcesIntent(): Intent = Intent("fake.ACTION_UNKNOWN_SOURCES")

        override fun createInstallApkIntent(localFilePath: String): Intent? {
            return if (apkExistsOnDisk) Intent("fake.ACTION_INSTALL") else null
        }
    }
}
