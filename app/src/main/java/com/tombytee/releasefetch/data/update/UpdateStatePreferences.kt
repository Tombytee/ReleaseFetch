package com.tombytee.releasefetch.data.update

import android.content.Context
import android.content.SharedPreferences

interface UpdateStateStore {
    fun readPersistedDownload(): PersistedUpdateDownload?
    fun savePersistedDownload(record: PersistedUpdateDownload)
    fun clearPersistedDownload()
}

class UpdateStatePreferences(
    context: Context,
) : UpdateStateStore {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun readPersistedDownload(): PersistedUpdateDownload? {
        val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        val versionCode = prefs.getLong(KEY_TARGET_VERSION_CODE, -1L)
        val versionName = prefs.getString(KEY_TARGET_VERSION_NAME, null)
        val apkUrl = prefs.getString(KEY_APK_URL, null)
        val localFilePath = prefs.getString(KEY_LOCAL_FILE_PATH, null)
        val releaseNotes = prefs.getString(KEY_RELEASE_NOTES, "") ?: ""

        if (
            downloadId <= 0L ||
            versionCode <= 0L ||
            versionName.isNullOrBlank() ||
            apkUrl.isNullOrBlank() ||
            localFilePath.isNullOrBlank()
        ) {
            return null
        }

        return PersistedUpdateDownload(
            downloadId = downloadId,
            targetVersionCode = versionCode,
            targetVersionName = versionName,
            apkUrl = apkUrl,
            localFilePath = localFilePath,
            releaseNotes = releaseNotes,
        )
    }

    override fun savePersistedDownload(record: PersistedUpdateDownload) {
        prefs.edit()
            .putLong(KEY_DOWNLOAD_ID, record.downloadId)
            .putLong(KEY_TARGET_VERSION_CODE, record.targetVersionCode)
            .putString(KEY_TARGET_VERSION_NAME, record.targetVersionName)
            .putString(KEY_APK_URL, record.apkUrl)
            .putString(KEY_LOCAL_FILE_PATH, record.localFilePath)
            .putString(KEY_RELEASE_NOTES, record.releaseNotes)
            .apply()
    }

    override fun clearPersistedDownload() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "releasefetch_app_update_prefs"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_TARGET_VERSION_CODE = "target_version_code"
        const val KEY_TARGET_VERSION_NAME = "target_version_name"
        const val KEY_APK_URL = "apk_url"
        const val KEY_LOCAL_FILE_PATH = "local_file_path"
        const val KEY_RELEASE_NOTES = "release_notes"
    }
}
