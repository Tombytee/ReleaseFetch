package com.tombytee.releasefetch

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tombytee.releasefetch.data.download.DownloadController
import com.tombytee.releasefetch.data.local.AppDatabase
import com.tombytee.releasefetch.data.local.RepoHistoryRepository
import com.tombytee.releasefetch.data.remote.ReleaseAnalyzerDataSource
import com.tombytee.releasefetch.data.remote.ReleaseFetchOutcome
import com.tombytee.releasefetch.domain.ClassifiedAsset
import com.tombytee.releasefetch.domain.DeviceProfile
import com.tombytee.releasefetch.domain.DownloadItemState
import com.tombytee.releasefetch.domain.RepoIdentifier
import com.tombytee.releasefetch.ui.ReleaseAnalyzerViewModel
import com.tombytee.releasefetch.ui.ReleaseFetchScreen
import com.tombytee.releasefetch.ui.components.AboutDeveloperBottomSheet
import com.tombytee.releasefetch.ui.components.DEVELOPER_AVATAR_URL
import com.tombytee.releasefetch.ui.theme.ReleaseFetchTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AboutDeveloperSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var historyRepository: RepoHistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        historyRepository = RepoHistoryRepository(database.savedRepoDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `top bar button opens About Developer sheet with creator details`() {
        val fakeDataSource = object : ReleaseAnalyzerDataSource {
            override suspend fun analyzeRepository(
                identifier: RepoIdentifier,
                deviceProfile: DeviceProfile,
            ): ReleaseFetchOutcome = ReleaseFetchOutcome.Failure("unused")
        }
        val fakeDownloader = object : DownloadController {
            override val downloadsByAssetId: StateFlow<Map<Long, DownloadItemState>> = MutableStateFlow(emptyMap())
            override fun startDownload(asset: ClassifiedAsset, repoFullName: String) = Result.success(1L)
            override fun openSystemDownloads() = true
            override fun dismissDownloadRecord(assetId: Long) {}
        }
        val viewModel = ReleaseAnalyzerViewModel(
            releaseRepository = fakeDataSource,
            historyRepository = historyRepository,
            downloadController = fakeDownloader,
        )

        composeTestRule.setContent {
            ReleaseFetchTheme(dynamicColor = false) {
                ReleaseFetchScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithTag("about_developer_button").assertIsDisplayed().performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            ShadowLooper.shadowMainLooper().idleFor(25L, TimeUnit.MILLISECONDS)
            composeTestRule.mainClock.advanceTimeBy(100L)
            composeTestRule.onAllNodes(hasTestTag("developer_name_text")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.mainClock.advanceTimeBy(500L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("about_developer_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("developer_avatar_frame").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Subham Kumar Prusty").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Built with ❤️ by Subham").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Neighbor's Eternal Struggle 🇮🇳💀").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `social media pill chips dispatch exact developer URLs and handle errors gracefully`() {
        assertEquals(
            "https://i.ibb.co/mtsMgL6/Whats-App-Image-2026-11-14-at-5-40-19-PM.jpg",
            DEVELOPER_AVATAR_URL,
        )

        val openedUrls = mutableListOf<String>()
        val errorMessages = mutableListOf<String>()
        var failOpen = false

        composeTestRule.setContent {
            ReleaseFetchTheme(dynamicColor = false) {
                AboutDeveloperBottomSheet(
                    onDismissSheet = {},
                    onOpenUrlError = { errorMessages += it },
                    onOpenUrl = { url ->
                        if (failOpen) {
                            false
                        } else {
                            openedUrls += url
                            true
                        }
                    },
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(hasTestTag("social_link_threads")).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("social_link_github").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("social_link_instagram").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("social_link_threads").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("social_link_youtube").performScrollTo().performClick()

        assertEquals(
            listOf(
                "https://github.com/Tombytee",
                "https://www.instagram.com/im.xubhh/",
                "https://www.threads.com/@tombytee",
                "https://youtube.com/@tom-bytee?si=9vpZM-UZWOAN9vTO",
            ),
            openedUrls,
        )

        failOpen = true
        composeTestRule.onNodeWithTag("social_link_github").performScrollTo().performClick()
        assertEquals(1, errorMessages.size)
    }
}
