package com.tombytee.releasefetch.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tombytee.releasefetch.R
import com.tombytee.releasefetch.data.update.AppUpdateUiState
import com.tombytee.releasefetch.data.update.UpdateLaunchEffect
import com.tombytee.releasefetch.data.update.UpdateStep
import com.tombytee.releasefetch.data.update.UpdateSystemGateway
import com.tombytee.releasefetch.domain.AssetClassifier
import com.tombytee.releasefetch.ui.theme.LocalReleaseColors
import com.tombytee.releasefetch.ui.theme.MonoFontFamily
import kotlinx.coroutines.flow.Flow

private val UpdatePillShape = RoundedCornerShape(10.dp)
private val UpdateBadgeShape = RoundedCornerShape(14.dp)

@Composable
fun MandatoryUpdateScreen(
    state: AppUpdateUiState.UpdateRequired,
    launchEffects: Flow<UpdateLaunchEffect>,
    systemGateway: UpdateSystemGateway,
    onAllowUnknownSourcesClicked: () -> Unit,
    onInstallClicked: () -> Unit,
    onInstallerResultReturned: () -> Unit,
    onUnknownSourcesResultReturned: () -> Unit,
    onRetryDownloadClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Strictly lock back navigation while a mandatory update is pending
    BackHandler(enabled = true) { }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        onUnknownSourcesResultReturned()
    }

    val installerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        onInstallerResultReturned()
    }

    LaunchedEffect(launchEffects, systemGateway) {
        launchEffects.collect { effect ->
            when (effect) {
                UpdateLaunchEffect.OpenUnknownAppsSettings -> {
                    val intent = systemGateway.createUnknownAppSourcesIntent()
                    runCatching { unknownSourcesLauncher.launch(intent) }
                }

                is UpdateLaunchEffect.LaunchPackageInstaller -> {
                    val intent = systemGateway.createInstallApkIntent(effect.localFilePath)
                    if (intent != null) {
                        runCatching { installerLauncher.launch(intent) }
                    }
                }
            }
        }
    }

    val semanticColors = LocalReleaseColors.current
    val cardShape = MaterialTheme.shapes.large
    val innerShape = MaterialTheme.shapes.medium

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("mandatory_update_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val isCompactHeight = maxHeight < 560.dp
            val outerScrollModifier = if (isCompactHeight) {
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
            } else {
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
            }

            Column(
                modifier = outerScrollModifier,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Header card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, cardShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(UpdateBadgeShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SystemUpdate,
                            contentDescription = stringResource(R.string.update_required_title),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.update_required_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.update_required_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Version comparison bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, innerShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, innerShape)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant, UpdatePillShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "Installed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "v${state.installedVersionName} (${state.installedVersionCode})",
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = MonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(semanticColors.recommendedContainer, UpdatePillShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "Required",
                            style = MaterialTheme.typography.labelSmall,
                            color = semanticColors.onRecommendedContainer,
                        )
                        Text(
                            text = "v${state.manifest.versionName} (${state.manifest.versionCode})",
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = semanticColors.onRecommendedContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("update_target_version"),
                        )
                    }
                }

                // Scrollable Release Notes card
                val notesCardModifier = if (isCompactHeight) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                }
                Column(
                    modifier = notesCardModifier
                        .background(MaterialTheme.colorScheme.surface, cardShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.update_whats_new),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "v${state.manifest.versionName}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = MonoFontFamily,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }

                    val notesBodyModifier = if (isCompactHeight) {
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp, max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    }
                    Box(modifier = notesBodyModifier) {
                        Text(
                            text = state.manifest.releaseNotes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("update_release_notes"),
                        )
                    }
                }

                // Bottom action & live status section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, cardShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (val step = state.step) {
                        is UpdateStep.Downloading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = if (step.isPausedOrWaiting) {
                                        stringResource(R.string.update_status_paused)
                                    } else {
                                        stringResource(R.string.update_status_downloading)
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                if (step.progressPercent >= 0) {
                                    Text(
                                        text = "${step.progressPercent}%",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }

                            if (step.progressPercent >= 0) {
                                LinearProgressIndicator(
                                    progress = { step.progressPercent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .testTag("update_download_progress"),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .testTag("update_download_progress"),
                                )
                            }

                            val progressDetail = buildString {
                                append(AssetClassifier.formatFileSize(step.downloadedBytes))
                                if (step.totalBytes > 0L) {
                                    append(" / ")
                                    append(AssetClassifier.formatFileSize(step.totalBytes))
                                }
                            }
                            Text(
                                text = progressDetail,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = MonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is UpdateStep.PermissionRequired -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(semanticColors.warningContainer, innerShape)
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = semanticColors.warningAccent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = stringResource(R.string.update_status_permission_needed),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = semanticColors.onWarningContainer,
                                    )
                                    Text(
                                        text = stringResource(R.string.update_permission_explanation),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = semanticColors.onWarningContainer,
                                    )
                                }
                            }

                            Button(
                                onClick = onAllowUnknownSourcesClicked,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("update_allow_permission_button"),
                                shape = innerShape,
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Security,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.update_action_allow_install),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        is UpdateStep.ReadyToInstall -> {
                            if (step.wasCancelledOrFailed) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(semanticColors.warningContainer, innerShape)
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = semanticColors.warningAccent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.update_install_cancelled_note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = semanticColors.onWarningContainer,
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(semanticColors.recommendedContainer, innerShape)
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = semanticColors.recommendedAccent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.update_status_ready_install),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = semanticColors.onRecommendedContainer,
                                    )
                                }
                            }

                            Button(
                                onClick = onInstallClicked,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("update_install_button"),
                                shape = innerShape,
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.InstallMobile,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (step.wasCancelledOrFailed) {
                                        stringResource(R.string.update_action_retry_install)
                                    } else {
                                        stringResource(R.string.update_action_install)
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        is UpdateStep.Installing -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                )
                                Text(
                                    text = stringResource(R.string.update_status_installing),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            Button(
                                onClick = onInstallClicked,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("update_install_button"),
                                shape = innerShape,
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.InstallMobile,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.update_action_install),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        is UpdateStep.DownloadError -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.errorContainer, innerShape)
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = stringResource(R.string.update_download_failed_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Text(
                                        text = step.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }

                            Button(
                                onClick = onRetryDownloadClicked,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("update_retry_download_button"),
                                shape = innerShape,
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.update_action_retry_download),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
