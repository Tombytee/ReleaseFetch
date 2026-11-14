package com.tombytee.releasefetch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tombytee.releasefetch.R
import com.tombytee.releasefetch.domain.AssetClassifier
import com.tombytee.releasefetch.domain.DownloadItemState
import com.tombytee.releasefetch.domain.DownloadStatus
import com.tombytee.releasefetch.ui.theme.LocalReleaseColors
import com.tombytee.releasefetch.ui.theme.MonoFontFamily

private val SkeletonPillShape = RoundedCornerShape(6.dp)
private val SkeletonIconShape = RoundedCornerShape(10.dp)

/**
 * Lightweight static tonal skeleton placeholders (zero continuous shimmer overhead)
 * displayed while fetching repository releases.
 */
@Composable
fun LoadingAnalysisCard(
    repoFullName: String,
    modifier: Modifier = Modifier,
) {
    val cardShape = MaterialTheme.shapes.large
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    val subtleSkeletonColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("loading_analysis_card"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, cardShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Analyzing $repoFullName…",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Fetching releases and matching compatible binaries",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .width(86.dp)
                        .height(26.dp)
                        .background(skeletonColor, SkeletonPillShape),
                )
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(26.dp)
                        .background(subtleSkeletonColor, SkeletonPillShape),
                )
            }
        }

        // Static tonal skeleton asset rows
        repeat(3) {
            AssetSkeletonCard()
        }
    }
}

@Composable
fun AssetSkeletonCard(
    modifier: Modifier = Modifier,
) {
    val cardShape = MaterialTheme.shapes.medium
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val subtleSkeletonColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, cardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(skeletonColor, SkeletonIconShape),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(16.dp)
                        .background(skeletonColor, SkeletonPillShape),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(18.dp)
                            .background(subtleSkeletonColor, SkeletonPillShape),
                    )
                    Box(
                        modifier = Modifier
                            .width(74.dp)
                            .height(18.dp)
                            .background(subtleSkeletonColor, SkeletonPillShape),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(14.dp)
                    .background(subtleSkeletonColor, SkeletonPillShape),
            )
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .height(34.dp)
                    .background(skeletonColor, CircleShape),
            )
        }
    }
}

@Composable
fun NoReleasesStateCard(
    canonicalFullName: String,
    ownerAvatarUrl: String?,
    onAnalyzeAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cardShape = MaterialTheme.shapes.large
    val emptyArtRequest = remember(context) {
        ImageRequest.Builder(context)
            .data(R.drawable.img_empty_releases_1794586424007)
            .size(640, 360)
            .crossfade(false)
            .build()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, cardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
            .padding(24.dp)
            .testTag("no_releases_card"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AsyncImage(
            model = emptyArtRequest,
            contentDescription = stringResource(R.string.cd_empty_releases_art),
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.Crop,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RepoAvatar(
                avatarUrl = ownerAvatarUrl,
                ownerName = canonicalFullName.substringBefore('/'),
                sizeDp = 28,
            )
            Text(
                text = canonicalFullName,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = MonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.no_releases_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Text(
            text = stringResource(R.string.no_releases_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        OutlinedButton(
            onClick = onAnalyzeAnother,
            modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
            Text("Try Another Repository")
        }
    }
}

@Composable
fun ErrorAnalysisCard(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = MaterialTheme.shapes.large
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), cardShape)
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), cardShape)
            .padding(20.dp)
            .testTag("error_state_card"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Unable to Analyze Repository",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onRetry,
                modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_retry))
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
                Text("Back")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsBottomSheet(
    downloads: List<DownloadItemState>,
    onDismissSheet: () -> Unit,
    onOpenSystemDownloads: () -> Unit,
    onCancelDownload: (Long) -> Unit,
    onDismissRecord: (Long) -> Unit,
) {
    val semanticColors = LocalReleaseColors.current
    ModalBottomSheet(onDismissRequest = onDismissSheet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.downloads_drawer_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedButton(
                    onClick = onOpenSystemDownloads,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_open_downloads))
                }
            }

            if (downloads.isEmpty()) {
                Text(
                    text = "No downloads started in this session yet. Tap Download on any release asset to fetch it in the background with live progress notifications.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(
                        items = downloads,
                        key = { it.assetId },
                        contentType = { "download_item" },
                    ) { item ->
                        val isActive = item.status == DownloadStatus.QUEUED ||
                            item.status == DownloadStatus.RUNNING ||
                            item.status == DownloadStatus.PAUSED
                        val rowShape = MaterialTheme.shapes.medium
                        val subtitleText = when {
                            item.status == DownloadStatus.FAILED && !item.errorMessage.isNullOrBlank() ->
                                "${item.repoFullName} • ${item.errorMessage}"
                            isActive && item.totalBytes > 0L ->
                                "${item.repoFullName} • ${AssetClassifier.formatFileSize(item.downloadedBytes)} / ${AssetClassifier.formatFileSize(item.totalBytes)}"
                            isActive && item.downloadedBytes > 0L ->
                                "${item.repoFullName} • ${AssetClassifier.formatFileSize(item.downloadedBytes)}"
                            item.totalBytes > 0L ->
                                "${item.repoFullName} • ${AssetClassifier.formatFileSize(item.totalBytes)}"
                            else ->
                                "${item.repoFullName} • ${AssetClassifier.formatFileSize(item.downloadedBytes)}"
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), rowShape)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, rowShape)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.fileName,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = subtitleText,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = MonoFontFamily,
                                            color = if (item.status == DownloadStatus.FAILED) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    TagPill(
                                        text = when (item.status) {
                                            DownloadStatus.QUEUED -> "Queued"
                                            DownloadStatus.RUNNING -> if (item.isIndeterminate) "Downloading" else "${item.progressPercent}%"
                                            DownloadStatus.PAUSED -> "Paused"
                                            DownloadStatus.SUCCESSFUL -> "Saved"
                                            DownloadStatus.FAILED -> "Failed"
                                            DownloadStatus.IDLE -> "Idle"
                                        },
                                        containerColor = when (item.status) {
                                            DownloadStatus.SUCCESSFUL -> semanticColors.recommendedContainer
                                            DownloadStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                                            else -> MaterialTheme.colorScheme.primaryContainer
                                        },
                                        contentColor = when (item.status) {
                                            DownloadStatus.SUCCESSFUL -> semanticColors.onRecommendedContainer
                                            DownloadStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
                                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                                        },
                                    )

                                    IconButton(
                                        onClick = {
                                            if (isActive) {
                                                onCancelDownload(item.assetId)
                                            } else {
                                                onDismissRecord(item.assetId)
                                            }
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("download_sheet_action_${item.assetId}"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = if (isActive) {
                                                stringResource(R.string.download_action_cancel)
                                            } else {
                                                "Dismiss download item"
                                            },
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }

                                if (isActive) {
                                    if (item.isIndeterminate || (item.status == DownloadStatus.QUEUED && item.progressPercent == 0)) {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(5.dp)
                                                .clip(RoundedCornerShape(999.dp)),
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            progress = { item.progressPercent / 100f },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(5.dp)
                                                .clip(RoundedCornerShape(999.dp)),
                                        )
                                    }
                                }
                        }
                    }
                }
            }
        }
    }
}
