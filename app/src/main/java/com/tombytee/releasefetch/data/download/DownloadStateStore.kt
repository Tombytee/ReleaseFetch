package com.tombytee.releasefetch.data.download

import com.tombytee.releasefetch.domain.DownloadItemState
import com.tombytee.releasefetch.domain.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe process-wide registry holding live download states shared between
 * [ReleaseDownloadService] and UI controllers.
 */
object DownloadStateStore {
    private val idGenerator = AtomicLong(System.currentTimeMillis())
    private val _downloadsByAssetId = MutableStateFlow<Map<Long, DownloadItemState>>(emptyMap())
    val downloadsByAssetId: StateFlow<Map<Long, DownloadItemState>> = _downloadsByAssetId.asStateFlow()

    fun nextDownloadId(): Long = idGenerator.incrementAndGet()

    fun get(assetId: Long): DownloadItemState? = _downloadsByAssetId.value[assetId]

    fun upsert(item: DownloadItemState) {
        _downloadsByAssetId.update { current ->
            current + (item.assetId to item)
        }
    }

    fun update(assetId: Long, transform: (DownloadItemState) -> DownloadItemState) {
        _downloadsByAssetId.update { current ->
            val existing = current[assetId] ?: return@update current
            val updated = transform(existing)
            if (updated == existing) current else current + (assetId to updated)
        }
    }

    fun remove(assetId: Long) {
        _downloadsByAssetId.update { current ->
            if (assetId in current) current - assetId else current
        }
    }

    fun activeItems(): List<DownloadItemState> {
        return _downloadsByAssetId.value.values.filter {
            it.status == DownloadStatus.QUEUED ||
                it.status == DownloadStatus.RUNNING ||
                it.status == DownloadStatus.PAUSED
        }
    }

    internal fun clearAll() {
        _downloadsByAssetId.value = emptyMap()
    }
}
