package com.tombytee.releasefetch.data.update

import androidx.compose.runtime.Immutable
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.net.URI
import java.util.Locale

const val UPDATE_MANIFEST_URL =
    "https://raw.githubusercontent.com/Tombytee/ReleaseFetch-Update/refs/heads/main/update.json"

@JsonClass(generateAdapter = true)
data class UpdateManifestDto(
    @param:Json(name = "versionName") val versionName: String? = null,
    @param:Json(name = "versionCode") val versionCode: Long? = null,
    @param:Json(name = "apkUrl") val apkUrl: String? = null,
    @param:Json(name = "releaseNotes") val releaseNotes: String? = null,
)

@Immutable
data class UpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val releaseNotes: String,
)

enum class ManifestInvalidReason {
    MALFORMED_JSON,
    INVALID_VERSION,
    INVALID_URL,
    PLACEHOLDER_URL,
}

sealed interface ManifestValidationResult {
    data class Valid(val manifest: UpdateManifest) : ManifestValidationResult
    data class Invalid(
        val reason: ManifestInvalidReason,
        val detail: String,
    ) : ManifestValidationResult
}

data class PersistedUpdateDownload(
    val downloadId: Long,
    val targetVersionCode: Long,
    val targetVersionName: String,
    val apkUrl: String,
    val localFilePath: String,
    val releaseNotes: String,
)

@Immutable
sealed interface UpdateStep {
    @Immutable
    data class Downloading(
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val isPausedOrWaiting: Boolean = false,
    ) : UpdateStep

    @Immutable
    data object PermissionRequired : UpdateStep

    @Immutable
    data class ReadyToInstall(
        val wasCancelledOrFailed: Boolean = false,
    ) : UpdateStep

    @Immutable
    data object Installing : UpdateStep

    @Immutable
    data class DownloadError(
        val message: String,
    ) : UpdateStep
}

@Immutable
sealed interface AppUpdateUiState {
    @Immutable
    data object UpToDate : AppUpdateUiState

    @Immutable
    data class UpdateRequired(
        val installedVersionName: String,
        val installedVersionCode: Long,
        val manifest: UpdateManifest,
        val step: UpdateStep,
    ) : AppUpdateUiState
}

sealed interface UpdateLaunchEffect {
    data object OpenUnknownAppsSettings : UpdateLaunchEffect
    data class LaunchPackageInstaller(val localFilePath: String) : UpdateLaunchEffect
}

object UpdateManifestValidator {
    private val moshi: Moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(UpdateManifestDto::class.java)

    private val placeholderHosts = setOf(
        "example.com",
        "www.example.com",
        "example.org",
        "www.example.org",
        "example.net",
        "www.example.net",
        "localhost",
        "127.0.0.1",
        "0.0.0.0",
    )

    private val placeholderTokens = listOf(
        "placeholder",
        "your_apk",
        "your-apk",
        "your_url",
        "your-url",
        "your_username",
        "your-username",
        "your_repo",
        "your-repo",
        "changeme",
        "replace_me",
        "replace-me",
        "insert_url",
        "insert-url",
        "todo",
        "<",
        ">",
        "{",
        "}",
    )

    fun parseAndValidate(rawJson: String): ManifestValidationResult {
        val dto = try {
            adapter.fromJson(rawJson)
        } catch (e: Exception) {
            return ManifestValidationResult.Invalid(
                reason = ManifestInvalidReason.MALFORMED_JSON,
                detail = e.message ?: "Failed to parse update JSON",
            )
        } ?: return ManifestValidationResult.Invalid(
            reason = ManifestInvalidReason.MALFORMED_JSON,
            detail = "Update manifest is empty",
        )

        val versionName = dto.versionName?.trim().orEmpty()
        val versionCode = dto.versionCode
        if (versionName.isEmpty() || versionCode == null || versionCode <= 0L) {
            return ManifestValidationResult.Invalid(
                reason = ManifestInvalidReason.INVALID_VERSION,
                detail = "Manifest versionName or versionCode is invalid",
            )
        }

        val apkUrl = dto.apkUrl?.trim().orEmpty()
        val urlValidation = validateApkUrl(apkUrl)
        if (urlValidation != null) {
            return urlValidation
        }

        val releaseNotes = dto.releaseNotes?.trim().orEmpty().ifEmpty {
            "Bug fixes, stability improvements, and performance enhancements."
        }

        return ManifestValidationResult.Valid(
            UpdateManifest(
                versionName = versionName,
                versionCode = versionCode,
                apkUrl = apkUrl,
                releaseNotes = releaseNotes,
            )
        )
    }

    fun validateApkUrl(rawUrl: String): ManifestValidationResult.Invalid? {
        if (rawUrl.isBlank()) {
            return ManifestValidationResult.Invalid(
                reason = ManifestInvalidReason.PLACEHOLDER_URL,
                detail = "Manifest apkUrl is blank",
            )
        }

        val lower = rawUrl.lowercase(Locale.US)
        if (placeholderTokens.any { token -> lower.contains(token) }) {
            return ManifestValidationResult.Invalid(
                reason = ManifestInvalidReason.PLACEHOLDER_URL,
                detail = "Manifest apkUrl still contains a placeholder",
            )
        }

        val parsed = try {
            URI(rawUrl)
        } catch (e: Exception) {
            return ManifestValidationResult.Invalid(
                reason = ManifestInvalidReason.INVALID_URL,
                detail = "Manifest apkUrl is malformed",
            )
        }

        val scheme = parsed.scheme?.lowercase(Locale.US)
        val host = parsed.host?.lowercase(Locale.US).orEmpty()
        if (scheme != "https" || host.isBlank()) {
            return ManifestValidationResult.Invalid(
                reason = ManifestInvalidReason.INVALID_URL,
                detail = "Manifest apkUrl must be a valid HTTPS URL",
            )
        }

        if (host in placeholderHosts || host.endsWith(".example.com")) {
            return ManifestValidationResult.Invalid(
                reason = ManifestInvalidReason.PLACEHOLDER_URL,
                detail = "Manifest apkUrl points to a placeholder host",
            )
        }

        return null
    }
}
