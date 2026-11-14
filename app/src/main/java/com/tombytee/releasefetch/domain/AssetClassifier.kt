package com.tombytee.releasefetch.domain

import java.util.Locale

object AssetClassifier {

    private const val MAX_RELEASE_NOTES_CHARS = 4_000

    private enum class ApkAbiKind(val canonicalAbi: String) {
        ARM64_V8A("arm64-v8a"),
        ARMEABI_V7A("armeabi-v7a"),
        X86_64("x86_64"),
        X86("x86"),
        UNIVERSAL("universal"),
        UNKNOWN("unknown"),
    }

    private val multiPartExtensions = arrayOf(".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst")
    private val checksumExtensions = setOf(
        "sha256", "sha512", "sha1", "md5", "asc", "sig", "minisig", "txt", "json", "yml", "yaml", "blockmap",
    )
    private val androidArchiveTokens = setOf("android", "apk")
    private val iosArchiveTokens = setOf("ios", "iphone", "ipad", "iphoneos")
    private val windowsArchiveTokens = setOf("win", "win32", "win64", "windows", "mingw", "msvc")
    private val macArchiveTokens = setOf("mac", "macos", "osx", "darwin")
    private val linuxArchiveTokens = setOf("linux", "ubuntu", "debian", "fedora", "archlinux", "musl", "gnu")
    private val arm64Tokens = setOf("arm64", "aarch64", "v8a")
    private val arm32Tokens = setOf("armeabi", "armv7", "armv7a", "v7a")
    private val x64Tokens = setOf("amd64", "x64")
    private val x86Tokens = setOf("x86", "i686", "i386")
    private val universalTokens = setOf("universal", "fat", "noarch", "all")
    private val debugTokens = setOf("debug", "unsigned")
    private val wearTokens = setOf("wear", "watch", "wearos")
    private val tvTokens = setOf("tv", "androidtv", "leanback")

    fun findLatestReleaseIndex(rawReleases: List<RawRelease>): Int {
        val stableIndex = rawReleases.indexOfFirst { !it.isPrerelease }
        return if (stableIndex >= 0) stableIndex else 0
    }

    fun classifyReleases(
        rawReleases: List<RawRelease>,
        deviceProfile: DeviceProfile,
    ): List<ClassifiedRelease> {
        val latestStableIndex = findLatestReleaseIndex(rawReleases)
        return rawReleases.mapIndexed { index, raw ->
            classifySingleRelease(
                raw = raw,
                isLatest = index == latestStableIndex,
                deviceProfile = deviceProfile,
            )
        }
    }

    fun classifySingleRelease(
        raw: RawRelease,
        isLatest: Boolean,
        deviceProfile: DeviceProfile,
    ): ClassifiedRelease {
        val preliminary = raw.assets.map { asset ->
            classifyAsset(asset, deviceProfile)
        }

        val bestAndroidApk = preliminary
            .filter {
                it.platform == PlatformCategory.ANDROID &&
                    it.fileExtension == "apk" &&
                    it.isCompatibleWithDevice
            }
            .maxWithOrNull(
                compareBy<ClassifiedAsset> { it.compatibilityScore }
                    .thenBy { -it.name.length },
            )

        val finalizedAssets = preliminary.map { asset ->
            if (bestAndroidApk != null && asset.id == bestAndroidApk.id) {
                asset.copy(isRecommendedForDevice = true)
            } else {
                asset
            }
        }.sortedWith(
            compareByDescending<ClassifiedAsset> { it.isRecommendedForDevice }
                .thenBy { platformSortOrder(it.platform) }
                .thenByDescending { it.isCompatibleWithDevice }
                .thenByDescending { it.compatibilityScore }
                .thenBy { it.name.lowercase(Locale.ROOT) },
        )

        val recommended = finalizedAssets.firstOrNull { it.isRecommendedForDevice }

        val counts = LinkedHashMap<PlatformCategory, Int>(PlatformCategory.entries.size)
        counts[PlatformCategory.ALL] = finalizedAssets.size
        val grouped = LinkedHashMap<PlatformCategory, MutableList<ClassifiedAsset>>()
        for (asset in finalizedAssets) {
            counts[asset.platform] = (counts[asset.platform] ?: 0) + 1
            grouped.getOrPut(asset.platform) { ArrayList() }.add(asset)
        }

        val availableCategories = PlatformCategory.entries.filter { category ->
            category == PlatformCategory.ALL || (counts[category] ?: 0) > 0
        }

        val trimmedNotes = raw.body.orEmpty().trim().let { notes ->
            if (notes.length > MAX_RELEASE_NOTES_CHARS) {
                notes.substring(0, MAX_RELEASE_NOTES_CHARS).trimEnd() + "\n\n… (notes truncated for performance)"
            } else {
                notes
            }
        }

        return ClassifiedRelease(
            id = raw.id,
            tagName = raw.tagName,
            displayTitle = raw.name?.takeIf { it.isNotBlank() } ?: raw.tagName,
            publishedDateLabel = formatPublishedDate(raw.publishedAt),
            isPrerelease = raw.isPrerelease,
            isLatest = isLatest,
            releaseNotes = trimmedNotes,
            assets = finalizedAssets,
            recommendedAsset = recommended,
            platformCounts = counts,
            groupedAssets = grouped,
            availableCategories = availableCategories,
        )
    }

    private fun classifyAsset(
        asset: RawReleaseAsset,
        device: DeviceProfile,
    ): ClassifiedAsset {
        val lower = asset.name.lowercase(Locale.ROOT)
        val ext = extractExtension(lower)
        val tokens = tokenize(lower)
        val platform = detectPlatform(lower, ext, tokens)
        val archLabel = detectArchitectureLabel(lower, tokens, platform)
        val sizeLabel = formatFileSize(asset.sizeBytes)

        val extBadge = ".${ext.uppercase(Locale.ROOT)}"
        val metaLabel = "$sizeLabel • ${asset.downloadCount} downloads"
        val recommendedMetaLabel = "$sizeLabel • APK"

        if (platform == PlatformCategory.ANDROID && ext == "apk") {
            val abiEvaluation = evaluateApkCompatibility(lower, tokens, device)
            return ClassifiedAsset(
                id = asset.id,
                name = asset.name,
                downloadUrl = asset.downloadUrl,
                sizeBytes = asset.sizeBytes,
                sizeLabel = sizeLabel,
                downloadCount = asset.downloadCount,
                platform = platform,
                fileExtension = ext,
                archLabel = archLabel ?: abiEvaluation.archDisplay,
                isCompatibleWithDevice = abiEvaluation.isCompatible,
                isRecommendedForDevice = false,
                compatibilityNote = abiEvaluation.note,
                compatibilityScore = abiEvaluation.score,
                extensionBadgeLabel = extBadge,
                metaLabel = metaLabel,
                recommendedMetaLabel = recommendedMetaLabel,
            )
        }

        val genericAndroidNote = when {
            platform == PlatformCategory.ANDROID && ext == "aab" ->
                "Android App Bundle (Play Store format; APK recommended for direct install)"
            platform == PlatformCategory.ANDROID ->
                "Android package ($ext)"
            else -> null
        }

        return ClassifiedAsset(
            id = asset.id,
            name = asset.name,
            downloadUrl = asset.downloadUrl,
            sizeBytes = asset.sizeBytes,
            sizeLabel = sizeLabel,
            downloadCount = asset.downloadCount,
            platform = platform,
            fileExtension = ext,
            archLabel = archLabel,
            isCompatibleWithDevice = platform == PlatformCategory.ANDROID,
            isRecommendedForDevice = false,
            compatibilityNote = genericAndroidNote,
            compatibilityScore = if (platform == PlatformCategory.ANDROID) 10 else 0,
            extensionBadgeLabel = extBadge,
            metaLabel = metaLabel,
            recommendedMetaLabel = recommendedMetaLabel,
        )
    }

    private fun extractExtension(lowerName: String): String {
        for (multi in multiPartExtensions) {
            if (lowerName.endsWith(multi)) {
                return multi.removePrefix(".")
            }
        }
        val dotIndex = lowerName.lastIndexOf('.')
        return if (dotIndex > 0 && dotIndex < lowerName.length - 1) {
            lowerName.substring(dotIndex + 1)
        } else {
            "bin"
        }
    }

    private fun detectPlatform(lowerName: String, ext: String, tokens: Set<String>): PlatformCategory {
        return when (ext) {
            "apk", "aab", "apks", "xapk" -> PlatformCategory.ANDROID
            "ipa" -> PlatformCategory.IOS
            "exe", "msi", "msix", "appx", "appxbundle" -> PlatformCategory.WINDOWS
            "dmg", "pkg" -> PlatformCategory.MACOS
            "appimage", "deb", "rpm", "snap", "flatpak", "pacman" -> PlatformCategory.LINUX
            else -> classifyArchiveByKeywords(lowerName, ext, tokens)
        }
    }

    private fun classifyArchiveByKeywords(
        lowerName: String,
        ext: String,
        tokens: Set<String>,
    ): PlatformCategory {
        if (ext in checksumExtensions) {
            return PlatformCategory.OTHER
        }

        return when {
            tokens.any { it in androidArchiveTokens } -> PlatformCategory.ANDROID
            tokens.any { it in iosArchiveTokens } -> PlatformCategory.IOS
            tokens.any { it in windowsArchiveTokens } || lowerName.contains("windows") -> PlatformCategory.WINDOWS
            tokens.any { it in macArchiveTokens } || lowerName.contains("apple-darwin") -> PlatformCategory.MACOS
            tokens.any { it in linuxArchiveTokens } || lowerName.contains("unknown-linux") -> PlatformCategory.LINUX
            else -> PlatformCategory.OTHER
        }
    }

    private fun detectArchitectureLabel(
        lowerName: String,
        tokens: Set<String>,
        platform: PlatformCategory,
    ): String? {
        return when {
            lowerName.contains("arm64-v8a") || tokens.any { it in arm64Tokens } -> "arm64-v8a"
            lowerName.contains("armeabi-v7a") || tokens.any { it in arm32Tokens } -> "armeabi-v7a"
            lowerName.contains("x86_64") || tokens.any { it in x64Tokens } -> "x86_64"
            tokens.any { it in x86Tokens } -> "x86"
            tokens.contains("universal") || tokens.contains("fat") -> "Universal"
            platform == PlatformCategory.WINDOWS && tokens.contains("win64") -> "x86_64"
            platform == PlatformCategory.WINDOWS && tokens.contains("win32") -> "x86"
            else -> null
        }
    }

    private data class AbiEvaluation(
        val isCompatible: Boolean,
        val score: Int,
        val note: String,
        val archDisplay: String?,
    )

    private fun evaluateApkCompatibility(
        lowerName: String,
        tokens: Set<String>,
        device: DeviceProfile,
    ): AbiEvaluation {
        val detectedAbi = detectApkAbiKind(lowerName, tokens)
        val primaryAbi = device.primaryAbi.lowercase(Locale.ROOT)
        val supported = device.supportedAbis

        var score = 0
        var isCompatible = true
        val reasonParts = ArrayList<String>(2)

        when (detectedAbi) {
            ApkAbiKind.UNIVERSAL -> {
                score = 82
                reasonParts += "Universal build compatible with ${device.primaryAbi}"
            }
            ApkAbiKind.UNKNOWN -> {
                score = 68
                reasonParts += "Standard APK compatible with ${device.primaryAbi}"
            }
            else -> {
                val abiString = detectedAbi.canonicalAbi
                val abiIndex = supported.indexOfFirst { it.equals(abiString, ignoreCase = true) }
                when {
                    abiIndex == 0 -> {
                        score = 110
                        reasonParts += "Matches primary ${device.primaryAbi} CPU architecture"
                    }
                    abiIndex > 0 -> {
                        score = 72
                        reasonParts += "Compatible via secondary $abiString support (64-bit preferred)"
                    }
                    else -> {
                        isCompatible = false
                        score = -100
                        reasonParts += "Built for $abiString — incompatible with $primaryAbi"
                    }
                }
            }
        }

        if (tokens.any { it in debugTokens }) {
            score -= 25
            if (isCompatible) reasonParts += "debug/unsigned variant"
        }
        if (tokens.any { it in wearTokens }) {
            score -= 35
            if (isCompatible) reasonParts += "Wear OS variant"
        }
        if (tokens.any { it in tvTokens }) {
            score -= 35
            if (isCompatible) reasonParts += "Android TV variant"
        }

        val archDisplay = when (detectedAbi) {
            ApkAbiKind.UNKNOWN -> null
            ApkAbiKind.UNIVERSAL -> "Universal"
            else -> detectedAbi.canonicalAbi
        }

        return AbiEvaluation(
            isCompatible = isCompatible,
            score = score,
            note = reasonParts.joinToString(" • "),
            archDisplay = archDisplay,
        )
    }

    private fun detectApkAbiKind(lowerName: String, tokens: Set<String>): ApkAbiKind {
        return when {
            lowerName.contains("arm64-v8a") || lowerName.contains("arm64_v8a") ||
                tokens.any { it in arm64Tokens } -> ApkAbiKind.ARM64_V8A
            lowerName.contains("armeabi-v7a") || lowerName.contains("armeabi_v7a") ||
                tokens.any { it in arm32Tokens } -> ApkAbiKind.ARMEABI_V7A
            lowerName.contains("x86_64") || lowerName.contains("x86-64") ||
                tokens.any { it in x64Tokens } -> ApkAbiKind.X86_64
            tokens.any { it in x86Tokens } -> ApkAbiKind.X86
            tokens.any { it in universalTokens } -> ApkAbiKind.UNIVERSAL
            else -> ApkAbiKind.UNKNOWN
        }
    }

    private fun tokenize(lowerName: String): Set<String> {
        val lastDot = lowerName.lastIndexOf('.')
        val end = if (lastDot > 0) lastDot else lowerName.length
        val tokens = HashSet<String>(8)
        var tokenStart = -1
        for (i in 0 until end) {
            val ch = lowerName[i]
            val isDelimiter = ch == '_' || ch == '-' || ch == '.' || ch == '+' || ch == ' '
            if (isDelimiter) {
                if (tokenStart >= 0 && i > tokenStart) {
                    tokens.add(lowerName.substring(tokenStart, i))
                }
                tokenStart = -1
            } else if (tokenStart < 0) {
                tokenStart = i
            }
        }
        if (tokenStart >= 0 && end > tokenStart) {
            tokens.add(lowerName.substring(tokenStart, end))
        }
        return tokens
    }

    private fun platformSortOrder(platform: PlatformCategory): Int = when (platform) {
        PlatformCategory.ANDROID -> 0
        PlatformCategory.WINDOWS -> 1
        PlatformCategory.MACOS -> 2
        PlatformCategory.LINUX -> 3
        PlatformCategory.IOS -> 4
        PlatformCategory.OTHER -> 5
        PlatformCategory.ALL -> 6
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${bytes} B"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    fun formatPublishedDate(isoTimestamp: String?): String {
        if (isoTimestamp.isNullOrBlank()) return "Release date unknown"
        return isoTimestamp.substringBefore('T')
    }
}
