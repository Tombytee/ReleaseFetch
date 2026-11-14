package com.tombytee.releasefetch.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DownloadTransferResult(
    val downloadedBytes: Long,
    val totalBytes: Long,
)

interface DownloadTransport {
    suspend fun downloadToStream(
        url: String,
        expectedTotalBytes: Long,
        outputStream: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransferResult
}

interface DownloadDestination {
    val outputStream: OutputStream
    val uriString: String?
    fun commit()
    fun abort()
}

interface DownloadStorage {
    fun openDestination(context: Context, rawFileName: String): DownloadDestination
}

class OkHttpDownloadTransport(
    private val client: OkHttpClient = defaultDownloadClient,
) : DownloadTransport {

    override suspend fun downloadToStream(
        url: String,
        expectedTotalBytes: Long,
        outputStream: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransferResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ReleaseFetch-Android/1.0")
            .get()
            .build()

        val call = client.newCall(request)
        val cancelRegistration = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                call.cancel()
            }
        }

        try {
            val response = call.execute()
            response.use { res ->
                if (!res.isSuccessful) {
                    throw IOException("HTTP ${res.code} while downloading release asset")
                }
                val body = res.body ?: throw IOException("Empty response body from server")
                val headerLength = body.contentLength()
                val resolvedTotal = when {
                    headerLength > 0L -> headerLength
                    expectedTotalBytes > 0L -> expectedTotalBytes
                    else -> -1L
                }

                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                var downloaded = 0L
                var lastDispatchTimeMs = 0L
                var lastDispatchedBytes = 0L

                onProgress(0L, resolvedTotal)

                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (read == 0) continue

                        outputStream.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        val bytesDelta = downloaded - lastDispatchedBytes
                        if (now - lastDispatchTimeMs >= PROGRESS_INTERVAL_MS ||
                            bytesDelta >= PROGRESS_BYTES_THRESHOLD
                        ) {
                            lastDispatchTimeMs = now
                            lastDispatchedBytes = downloaded
                            onProgress(downloaded, resolvedTotal)
                        }
                    }
                    outputStream.flush()
                }

                val finalTotal = if (resolvedTotal > 0L) maxOf(resolvedTotal, downloaded) else downloaded
                onProgress(downloaded, finalTotal)
                DownloadTransferResult(downloadedBytes = downloaded, totalBytes = finalTotal)
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            cancelRegistration?.dispose()
        }
    }

    companion object {
        private const val STREAM_BUFFER_BYTES = 32 * 1024
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val PROGRESS_BYTES_THRESHOLD = 256L * 1024L

        val defaultDownloadClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}

object PlatformDownloadStorage : DownloadStorage {

    override fun openDestination(context: Context, rawFileName: String): DownloadDestination {
        val safeName = sanitizeFileName(rawFileName)
        val mimeType = resolveMimeType(safeName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/ReleaseFetch",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val insertedUri = try {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            } catch (_: Exception) {
                null
            }

            if (insertedUri != null) {
                val stream = try {
                    resolver.openOutputStream(insertedUri)
                } catch (_: Exception) {
                    null
                }
                if (stream != null) {
                    return MediaStoreDownloadDestination(
                        context = context.applicationContext,
                        uri = insertedUri,
                        outputStream = stream,
                    )
                } else {
                    try {
                        resolver.delete(insertedUri, null, null)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val targetFile = createUniqueFile(downloadsDir, safeName)
        return FileDownloadDestination(
            file = targetFile,
            outputStream = FileOutputStream(targetFile),
        )
    }

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "release_asset.bin" }
    }

    fun resolveMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext == "apk") return "application/vnd.android.package-archive"
        if (ext == "aab") return "application/octet-stream"
        val systemMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return systemMime ?: "application/octet-stream"
    }

    private fun createUniqueFile(dir: File, desiredName: String): File {
        var candidate = File(dir, desiredName)
        if (!candidate.exists()) return candidate

        val dotIndex = desiredName.lastIndexOf('.')
        val stem = if (dotIndex > 0) desiredName.substring(0, dotIndex) else desiredName
        val ext = if (dotIndex > 0) desiredName.substring(dotIndex) else ""

        var counter = 1
        while (candidate.exists() && counter <= 999) {
            candidate = File(dir, "$stem-$counter$ext")
            counter++
        }
        return candidate
    }
}

private class MediaStoreDownloadDestination(
    private val context: Context,
    private val uri: Uri,
    override val outputStream: OutputStream,
) : DownloadDestination {
    override val uriString: String = uri.toString()

    override fun commit() {
        try {
            outputStream.close()
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            try {
                context.contentResolver.update(uri, values, null, null)
            } catch (_: Exception) {
            }
        }
    }

    override fun abort() {
        try {
            outputStream.close()
        } catch (_: Exception) {
        }
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }
}

private class FileDownloadDestination(
    private val file: File,
    override val outputStream: OutputStream,
) : DownloadDestination {
    override val uriString: String = Uri.fromFile(file).toString()

    override fun commit() {
        try {
            outputStream.flush()
        } catch (_: Exception) {
        }
        try {
            outputStream.close()
        } catch (_: Exception) {
        }
    }

    override fun abort() {
        try {
            outputStream.close()
        } catch (_: Exception) {
        }
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }
}
