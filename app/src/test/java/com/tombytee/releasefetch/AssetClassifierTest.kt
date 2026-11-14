package com.tombytee.releasefetch

import com.tombytee.releasefetch.domain.AssetClassifier
import com.tombytee.releasefetch.domain.DeviceProfile
import com.tombytee.releasefetch.domain.PlatformCategory
import com.tombytee.releasefetch.domain.RawRelease
import com.tombytee.releasefetch.domain.RawReleaseAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetClassifierTest {

    private val arm64Device = DeviceProfile(
        primaryAbi = "arm64-v8a",
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
        androidVersion = "15",
        sdkInt = 35,
        deviceModel = "Pixel 9",
    )

    private val x86Device = DeviceProfile(
        primaryAbi = "x86_64",
        supportedAbis = listOf("x86_64", "x86"),
        androidVersion = "14",
        sdkInt = 34,
        deviceModel = "Emulator",
    )

    @Test
    fun `categorizes multiplatform assets and picks primary arm64 apk as recommended`() {
        val release = RawRelease(
            id = 100L,
            tagName = "v2.4.0",
            name = "Release 2.4.0",
            publishedAt = "2026-10-15T12:00:00Z",
            isPrerelease = false,
            body = "Changelog",
            assets = listOf(
                RawReleaseAsset(1L, "app-armeabi-v7a-release.apk", "https://example.com/v7a.apk", 12_000_000, 100, null),
                RawReleaseAsset(2L, "app-arm64-v8a-release.apk", "https://example.com/arm64.apk", 13_000_000, 500, null),
                RawReleaseAsset(3L, "app-universal-release.apk", "https://example.com/univ.apk", 22_000_000, 300, null),
                RawReleaseAsset(4L, "app-x86_64-release.apk", "https://example.com/x64.apk", 13_500_000, 40, null),
                RawReleaseAsset(5L, "AppSetup-2.4.0.exe", "https://example.com/win.exe", 45_000_000, 800, null),
                RawReleaseAsset(6L, "App-2.4.0.dmg", "https://example.com/mac.dmg", 50_000_000, 450, null),
                RawReleaseAsset(7L, "App-2.4.0.AppImage", "https://example.com/linux.AppImage", 48_000_000, 210, null),
                RawReleaseAsset(8L, "App-2.4.0.ipa", "https://example.com/ios.ipa", 30_000_000, 120, null),
                RawReleaseAsset(9L, "checksums.sha256", "https://example.com/sha", 1024, 90, null),
            ),
        )

        val classified = AssetClassifier.classifySingleRelease(release, isLatest = true, deviceProfile = arm64Device)

        assertNotNull(classified.recommendedAsset)
        assertEquals("app-arm64-v8a-release.apk", classified.recommendedAsset?.name)
        assertTrue(classified.recommendedAsset?.isRecommendedForDevice == true)

        val x86Apk = classified.assets.first { it.name == "app-x86_64-release.apk" }
        assertFalse(x86Apk.isCompatibleWithDevice)

        assertEquals(PlatformCategory.WINDOWS, classified.assets.first { it.name == "AppSetup-2.4.0.exe" }.platform)
        assertEquals(PlatformCategory.MACOS, classified.assets.first { it.name == "App-2.4.0.dmg" }.platform)
        assertEquals(PlatformCategory.LINUX, classified.assets.first { it.name == "App-2.4.0.AppImage" }.platform)
        assertEquals(PlatformCategory.IOS, classified.assets.first { it.name == "App-2.4.0.ipa" }.platform)
        assertEquals(PlatformCategory.OTHER, classified.assets.first { it.name == "checksums.sha256" }.platform)
    }

    @Test
    fun `x86_64 device picks x86_64 apk and rejects arm64 apk`() {
        val release = RawRelease(
            id = 200L,
            tagName = "v1.0.0",
            name = "1.0.0",
            publishedAt = "2026-10-01T09:00:00Z",
            isPrerelease = false,
            body = null,
            assets = listOf(
                RawReleaseAsset(11L, "client-arm64-v8a.apk", "https://example.com/a64.apk", 10_000_000, 50, null),
                RawReleaseAsset(12L, "client-x86_64.apk", "https://example.com/x64.apk", 10_500_000, 20, null),
            ),
        )

        val classified = AssetClassifier.classifySingleRelease(release, isLatest = true, deviceProfile = x86Device)
        assertEquals("client-x86_64.apk", classified.recommendedAsset?.name)
        assertFalse(classified.assets.first { it.name == "client-arm64-v8a.apk" }.isCompatibleWithDevice)
    }
}
