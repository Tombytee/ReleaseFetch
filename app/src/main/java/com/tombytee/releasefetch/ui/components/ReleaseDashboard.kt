package com.tombytee.releasefetch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tombytee.releasefetch.R
import com.tombytee.releasefetch.domain.ClassifiedAsset
import com.tombytee.releasefetch.domain.ClassifiedRelease
import com.tombytee.releasefetch.domain.DownloadItemState
import com.tombytee.releasefetch.domain.DownloadStatus
import com.tombytee.releasefetch.domain.PlatformCategory
import com.tombytee.releasefetch.domain.ReleaseVersionSummary
import com.tombytee.releasefetch.ui.AnalysisState
import com.tombytee.releasefetch.ui.theme.LocalReleaseColors
import com.tombytee.releasefetch.ui.theme.MonoFontFamily
import kotlinx.coroutines.flow.distinctUntilChanged

private val PillShape = RoundedCornerShape(6.dp)
private val VerifiedBadgeShape = RoundedCornerShape(999.dp)
private val AssetIconShape = RoundedCornerShape(10.dp)

fun LazyListScope.releaseDashboardItems(
    state: AnalysisState.Ready,
    isPinned: Boolean,
    downloadLookup: (Long) -> DownloadItemState?,
    onSelectReleaseIndex: (Int) -> Unit,
    onSelectPlatform: (PlatformCategory) -> Unit,
    onToggleReleaseNotes: () -> Unit,
    onTogglePin: () -> Unit,
    onDownloadAsset: (ClassifiedAsset) -> Unit,
    onOpenSystemDownloads: () -> Unit,
    onLoadMoreReleases: () -> Unit,
    onRevealMoreAssets: () -> Unit,
) {
    val repository = state.repository
    val release = state.activeRelease

    item(key = "release_header", contentType = "release_header") {
        RepoReleaseHeaderCard(
            canonicalFullName = repository.canonicalFullName,
            owner = repository.identifier.owner,
            avatarUrl = repository.ownerAvatarUrl,
            currentRelease = release,
            versionSummaries = repository.versionSummaries,
            selectedIndex = state.selectedReleaseIndex,
            hasMoreReleases = repository.hasMoreReleases,
            isLoadingMoreReleases = state.isLoadingMoreReleases,
            isPinned = isPinned,
            showReleaseNotes = state.showReleaseNotes,
            onSelectReleaseIndex = onSelectReleaseIndex,
            onLoadMoreReleases = onLoadMoreReleases,
            onTogglePin = onTogglePin,
            onToggleReleaseNotes = onToggleReleaseNotes,
        )
    }

    release.recommendedAsset?.let { recommended ->
        item(key = "recommended_${release.id}_${recommended.id}", contentType = "recommended_card") {
            RecommendedDeviceAssetCard(
                asset = recommended,
                downloadLookup = downloadLookup,
                onDownload = { onDownloadAsset(recommended) },
                onOpenSystemDownloads = onOpenSystemDownloads,
            )
        }
    }

    if (release.assets.isEmpty()) {
        item(key = "empty_assets_${release.id}", contentType = "empty_assets") {
            val emptyShape = MaterialTheme.shapes.medium
            Text(
                text = stringResource(R.string.no_assets_in_release),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, emptyShape)
                    .padding(16.dp),
            )
        }
        return
    }

    item(key = "platform_filter", contentType = "platform_filter") {
        PlatformFilterBar(
            release = release,
            selectedPlatform = state.selectedPlatform,
            onSelectPlatform = onSelectPlatform,
        )
    }

    if (state.selectedPlatform == PlatformCategory.ALL) {
        var remaining = state.visibleAssetLimit
        for ((platform, assetsInPlatform) in release.groupedAssets) {
            if (remaining <= 0) break
            val slice = if (assetsInPlatform.size <= remaining) {
                assetsInPlatform
            } else {
                assetsInPlatform.subList(0, remaining)
            }
            if (slice.isEmpty()) continue
            remaining -= slice.size

            item(
                key = "section_${release.id}_${platform.name}",
                contentType = "platform_section_header",
            ) {
                PlatformSectionHeader(
                    platform = platform,
                    count = assetsInPlatform.size,
                )
            }

            items(
                items = slice,
                key = { asset -> "asset_${release.id}_${asset.id}" },
                contentType = { "asset_download_card" },
            ) { asset ->
                AssetDownloadCard(
                    asset = asset,
                    downloadLookup = downloadLookup,
                    onDownload = { onDownloadAsset(asset) },
                    onOpenSystemDownloads = onOpenSystemDownloads,
                )
            }
        }
    } else {
        val filteredAssets = release.groupedAssets[state.selectedPlatform].orEmpty()
        val slice = if (filteredAssets.size <= state.visibleAssetLimit) {
            filteredAssets
        } else {
            filteredAssets.subList(0, state.visibleAssetLimit)
        }
        items(
            items = slice,
            key = { asset -> "asset_${release.id}_${asset.id}" },
            contentType = { "asset_download_card" },
        ) { asset ->
            AssetDownloadCard(
                asset = asset,
                downloadLookup = downloadLookup,
                onDownload = { onDownloadAsset(asset) },
                onOpenSystemDownloads = onOpenSystemDownloads,
            )
        }
    }

    if (state.hasMoreAssetsToReveal) {
        item(
            key = "load_more_assets_${release.id}_${state.selectedPlatform.name}",
            contentType = "asset_load_more",
        ) {
            OutlinedButton(
                onClick = onRevealMoreAssets,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("load_more_assets_button"),
            ) {
                Text("Show more assets")
            }
        }
    }
}

@Composable
private fun RepoReleaseHeaderCard(
    canonicalFullName: String,
    owner: String,
    avatarUrl: String?,
    currentRelease: ClassifiedRelease,
    versionSummaries: List<ReleaseVersionSummary>,
    selectedIndex: Int,
    hasMoreReleases: Boolean,
    isLoadingMoreReleases: Boolean,
    isPinned: Boolean,
    showReleaseNotes: Boolean,
    onSelectReleaseIndex: (Int) -> Unit,
    onLoadMoreReleases: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleReleaseNotes: () -> Unit,
) {
    var versionSheetOpen by remember { mutableStateOf(false) }
    val cardShape = MaterialTheme.shapes.large
    val canSwitchVersions = versionSummaries.size > 1 || hasMoreReleases

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, cardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
            .padding(16.dp)
            .testTag("release_header_card"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RepoAvatar(avatarUrl = avatarUrl, ownerName = owner, sizeDp = 44)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = canonicalFullName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Published ${currentRelease.publishedDateLabel} • ${currentRelease.assets.size} file${if (currentRelease.assets.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(
                onClick = onTogglePin,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("pin_current_repo_button"),
            ) {
                Icon(
                    imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (isPinned) {
                        stringResource(R.string.action_unpin)
                    } else {
                        stringResource(R.string.action_pin)
                    },
                    tint = if (isPinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { if (canSwitchVersions) versionSheetOpen = true },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("version_switcher_button"),
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = currentRelease.tagName,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = MonoFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canSwitchVersions) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.version_switcher_label),
                    )
                }
            }

            if (currentRelease.isLatest) {
                TagPill(
                    text = stringResource(R.string.latest_badge),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else if (currentRelease.isPrerelease) {
                TagPill(
                    text = stringResource(R.string.prerelease_badge),
                    containerColor = LocalReleaseColors.current.warningContainer,
                    contentColor = LocalReleaseColors.current.onWarningContainer,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (currentRelease.releaseNotes.isNotBlank()) {
                TextButton(
                    onClick = onToggleReleaseNotes,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showReleaseNotes) {
                            stringResource(R.string.release_notes_hide)
                        } else {
                            stringResource(R.string.release_notes_show)
                        },
                    )
                }
            }
        }

        if (showReleaseNotes && currentRelease.releaseNotes.isNotBlank()) {
            Text(
                text = currentRelease.releaseNotes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .padding(12.dp),
            )
        }
    }

    if (versionSheetOpen && canSwitchVersions) {
        VersionPickerSheet(
            versionSummaries = versionSummaries,
            selectedIndex = selectedIndex,
            hasMoreReleases = hasMoreReleases,
            isLoadingMoreReleases = isLoadingMoreReleases,
            onDismiss = { versionSheetOpen = false },
            onSelectReleaseIndex = { idx ->
                versionSheetOpen = false
                onSelectReleaseIndex(idx)
            },
            onLoadMoreReleases = onLoadMoreReleases,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionPickerSheet(
    versionSummaries: List<ReleaseVersionSummary>,
    selectedIndex: Int,
    hasMoreReleases: Boolean,
    isLoadingMoreReleases: Boolean,
    onDismiss: () -> Unit,
    onSelectReleaseIndex: (Int) -> Unit,
    onLoadMoreReleases: () -> Unit,
) {
    val sheetListState = rememberLazyListState()
    val semanticColors = LocalReleaseColors.current

    // Trigger next page automatically when user scrolls near the end of loaded releases
    LaunchedEffect(sheetListState, versionSummaries.size, hasMoreReleases, isLoadingMoreReleases) {
        if (!hasMoreReleases || isLoadingMoreReleases) return@LaunchedEffect
        snapshotFlow {
            val info = sheetListState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= (info.totalItemsCount - 3).coerceAtLeast(0)
        }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd) {
                    onLoadMoreReleases()
                }
            }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.version_switcher_label),
                style = MaterialTheme.typography.titleLarge,
            )

            LazyColumn(
                state = sheetListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .testTag("version_picker_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = versionSummaries,
                    key = { _, item -> "version_${item.id}" },
                    contentType = { _, _ -> "version_row" },
                ) { idx, item ->
                    val isSelected = idx == selectedIndex
                    val rowShape = MaterialTheme.shapes.medium
                    val bgColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                    val borderColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor, rowShape)
                            .border(1.dp, borderColor, rowShape)
                            .clickable { onSelectReleaseIndex(idx) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("version_option_${item.tagName}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = item.tagName,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = MonoFontFamily,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.isLatest) {
                            TagPill(
                                text = stringResource(R.string.latest_badge),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else if (item.isPrerelease) {
                            TagPill(
                                text = stringResource(R.string.prerelease_badge),
                                containerColor = semanticColors.warningContainer,
                                contentColor = semanticColors.onWarningContainer,
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                if (hasMoreReleases || isLoadingMoreReleases) {
                    item(key = "load_older_releases_footer", contentType = "version_load_more") {
                        OutlinedButton(
                            onClick = onLoadMoreReleases,
                            enabled = !isLoadingMoreReleases,
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("load_older_releases_button"),
                        ) {
                            if (isLoadingMoreReleases) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Loading older releases…")
                            } else {
                                Text("Load older releases…")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendedDeviceAssetCard(
    asset: ClassifiedAsset,
    downloadLookup: (Long) -> DownloadItemState?,
    onDownload: () -> Unit,
    onOpenSystemDownloads: () -> Unit,
) {
    val semanticColors = LocalReleaseColors.current
    val cardShape = MaterialTheme.shapes.large
    val heroBrush = remember(semanticColors) {
        Brush.linearGradient(
            colors = listOf(
                semanticColors.heroGradientStart,
                semanticColors.heroGradientEnd,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(heroBrush, cardShape)
            .border(1.5.dp, semanticColors.recommendedAccent.copy(alpha = 0.6f), cardShape)
            .padding(18.dp)
            .testTag("recommended_asset_card"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .background(semanticColors.recommendedAccent, VerifiedBadgeShape)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Verified,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = stringResource(R.string.recommended_badge),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }

            asset.archLabel?.let { arch ->
                TagPill(
                    text = arch,
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Text(
            text = asset.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        asset.compatibilityNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = semanticColors.onRecommendedContainer,
            )
        }

        AssetDownloadFooter(
            assetId = asset.id,
            metaLabel = asset.recommendedMetaLabel,
            isPrimarySpotlight = true,
            downloadLookup = downloadLookup,
            onDownload = onDownload,
            onOpenSystemDownloads = onOpenSystemDownloads,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlatformFilterBar(
    release: ClassifiedRelease,
    selectedPlatform: PlatformCategory,
    onSelectPlatform: (PlatformCategory) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        release.availableCategories.forEach { category ->
            val count = release.platformCounts[category] ?: 0
            FilterChip(
                selected = selectedPlatform == category,
                onClick = { onSelectPlatform(category) },
                modifier = Modifier.testTag("platform_chip_${category.name}"),
                leadingIcon = if (category != PlatformCategory.ALL) {
                    {
                        Icon(
                            imageVector = platformIcon(category),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    null
                },
                label = {
                    Text("${category.displayName} ($count)")
                },
            )
        }
    }
}

@Composable
private fun PlatformSectionHeader(
    platform: PlatformCategory,
    count: Int,
) {
    val badgeColor = platformAccentColor(platform)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = platformIcon(platform),
            contentDescription = null,
            tint = badgeColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "${platform.displayName} Downloads",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "($count)",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MonoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AssetDownloadCard(
    asset: ClassifiedAsset,
    downloadLookup: (Long) -> DownloadItemState?,
    onDownload: () -> Unit,
    onOpenSystemDownloads: () -> Unit,
) {
    val semanticColors = LocalReleaseColors.current
    val accent = platformAccentColor(asset.platform)
    val cardShape = MaterialTheme.shapes.medium
    val borderColor = if (asset.isRecommendedForDevice) {
        semanticColors.recommendedAccent.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, cardShape)
            .border(1.dp, borderColor, cardShape)
            .padding(14.dp)
            .testTag("asset_card_${asset.name}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(accent.copy(alpha = 0.14f), AssetIconShape)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = platformIcon(asset.platform),
                    contentDescription = asset.platform.displayName,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = asset.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TagPill(
                        text = asset.extensionBadgeLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    asset.archLabel?.let { arch ->
                        TagPill(
                            text = arch,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }

                    if (asset.isRecommendedForDevice) {
                        TagPill(
                            text = "Recommended",
                            containerColor = semanticColors.recommendedContainer,
                            contentColor = semanticColors.onRecommendedContainer,
                        )
                    }
                }

                if (asset.platform == PlatformCategory.ANDROID && !asset.isCompatibleWithDevice && asset.compatibilityNote != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = semanticColors.warningAccent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = asset.compatibilityNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = semanticColors.warningAccent,
                        )
                    }
                } else if (asset.compatibilityNote != null && asset.fileExtension == "aab") {
                    Text(
                        text = asset.compatibilityNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        AssetDownloadFooter(
            assetId = asset.id,
            metaLabel = asset.metaLabel,
            isPrimarySpotlight = false,
            downloadLookup = downloadLookup,
            onDownload = onDownload,
            onOpenSystemDownloads = onOpenSystemDownloads,
        )
    }
}

/**
 * Reads from a SnapshotStateMap per assetId so fast flings register zero Flow/Lifecycle observers
 * and download ticks only recompose the single matching asset footer.
 */
@Composable
private fun AssetDownloadFooter(
    assetId: Long,
    metaLabel: String,
    isPrimarySpotlight: Boolean,
    downloadLookup: (Long) -> DownloadItemState?,
    onDownload: () -> Unit,
    onOpenSystemDownloads: () -> Unit,
) {
    val downloadState = downloadLookup(assetId)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = metaLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AssetDownloadActionButton(
                downloadState = downloadState,
                isPrimarySpotlight = isPrimarySpotlight,
                onDownload = onDownload,
                onOpenSystemDownloads = onOpenSystemDownloads,
            )
        }

        DownloadProgressBarIfRunning(downloadState)
    }
}

@Composable
private fun AssetDownloadActionButton(
    downloadState: DownloadItemState?,
    isPrimarySpotlight: Boolean,
    onDownload: () -> Unit,
    onOpenSystemDownloads: () -> Unit,
) {
    val semanticColors = LocalReleaseColors.current
    when (downloadState?.status) {
        DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.PAUSED -> {
            FilledTonalButton(
                onClick = onOpenSystemDownloads,
                modifier = Modifier.minimumInteractiveComponentSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                val progressText = when {
                    downloadState.status == DownloadStatus.QUEUED -> "Queued"
                    downloadState.isIndeterminate -> "…"
                    else -> "${downloadState.progressPercent}%"
                }
                Text(progressText, fontFamily = MonoFontFamily)
            }
        }

        DownloadStatus.SUCCESSFUL -> {
            FilledTonalButton(
                onClick = onOpenSystemDownloads,
                modifier = Modifier.minimumInteractiveComponentSize(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = semanticColors.recommendedContainer,
                    contentColor = semanticColors.onRecommendedContainer,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open")
            }
        }

        DownloadStatus.FAILED, DownloadStatus.IDLE, null -> {
            val label = if (downloadState?.status == DownloadStatus.FAILED) {
                stringResource(R.string.action_retry)
            } else {
                stringResource(R.string.action_download)
            }
            if (isPrimarySpotlight) {
                Button(
                    onClick = onDownload,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("download_recommended_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = semanticColors.recommendedAccent,
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(label, fontWeight = FontWeight.SemiBold)
                }
            } else {
                FilledTonalButton(
                    onClick = onDownload,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressBarIfRunning(downloadState: DownloadItemState?) {
    if (downloadState == null) return
    if (downloadState.status == DownloadStatus.QUEUED ||
        downloadState.status == DownloadStatus.RUNNING ||
        downloadState.status == DownloadStatus.PAUSED
    ) {
        if (!downloadState.isIndeterminate && downloadState.progressPercent > 0) {
            LinearProgressIndicator(
                progress = { downloadState.progressPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(VerifiedBadgeShape),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(VerifiedBadgeShape),
            )
        }
    }
}

@Composable
fun TagPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = MonoFontFamily,
        color = contentColor,
        modifier = Modifier
            .background(containerColor, PillShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun platformAccentColor(platform: PlatformCategory): Color {
    val colors = LocalReleaseColors.current
    return when (platform) {
        PlatformCategory.ANDROID -> colors.androidBadge
        PlatformCategory.WINDOWS -> colors.windowsBadge
        PlatformCategory.MACOS -> colors.macosBadge
        PlatformCategory.LINUX -> colors.linuxBadge
        PlatformCategory.IOS -> colors.iosBadge
        PlatformCategory.OTHER, PlatformCategory.ALL -> colors.otherBadge
    }
}

private fun platformIcon(platform: PlatformCategory): ImageVector = when (platform) {
    PlatformCategory.ANDROID -> Icons.Filled.Android
    PlatformCategory.WINDOWS -> Icons.Filled.Computer
    PlatformCategory.MACOS -> Icons.Filled.LaptopMac
    PlatformCategory.LINUX -> Icons.Filled.Terminal
    PlatformCategory.IOS -> Icons.Filled.PhoneIphone
    PlatformCategory.OTHER, PlatformCategory.ALL -> Icons.Filled.FolderZip
}
