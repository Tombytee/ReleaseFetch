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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tombytee.releasefetch.R
import com.tombytee.releasefetch.domain.DeviceProfile
import com.tombytee.releasefetch.domain.DownloadItemState
import com.tombytee.releasefetch.ui.theme.LocalReleaseColors
import com.tombytee.releasefetch.ui.theme.MonoFontFamily
import kotlinx.coroutines.flow.StateFlow

private val ChipPillShape = RoundedCornerShape(8.dp)
private val IconBoxShape = RoundedCornerShape(10.dp)

@Composable
fun TopBrandBar(
    activeDownloadCountFlow: StateFlow<Int>,
    downloadsListFlow: StateFlow<List<DownloadItemState>>,
    onOpenDownloads: () -> Unit,
    onBrandClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCheckingUpdatesFlow: StateFlow<Boolean>? = null,
    onCheckUpdates: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onBrandClick)
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onCheckUpdates != null) {
                CheckUpdatesHeaderButton(
                    isCheckingUpdatesFlow = isCheckingUpdatesFlow,
                    onCheckUpdates = onCheckUpdates,
                )
            }

            DownloadsBadgeButton(
                activeDownloadCountFlow = activeDownloadCountFlow,
                downloadsListFlow = downloadsListFlow,
                onOpenDownloads = onOpenDownloads,
            )
        }
    }
}

@Composable
private fun CheckUpdatesHeaderButton(
    isCheckingUpdatesFlow: StateFlow<Boolean>?,
    onCheckUpdates: () -> Unit,
) {
    val isChecking = if (isCheckingUpdatesFlow != null) {
        isCheckingUpdatesFlow.collectAsStateWithLifecycle().value
    } else {
        false
    }

    IconButton(
        onClick = onCheckUpdates,
        enabled = !isChecking,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("check_updates_button"),
    ) {
        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.SystemUpdate,
                contentDescription = stringResource(R.string.action_check_updates),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadsBadgeButton(
    activeDownloadCountFlow: StateFlow<Int>,
    downloadsListFlow: StateFlow<List<DownloadItemState>>,
    onOpenDownloads: () -> Unit,
) {
    val activeDownloadCount by activeDownloadCountFlow.collectAsStateWithLifecycle()
    val downloadsList by downloadsListFlow.collectAsStateWithLifecycle()
    val totalDownloadCount = downloadsList.size

    FilledTonalIconButton(
        onClick = onOpenDownloads,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("downloads_drawer_button"),
    ) {
        BadgedBox(
            badge = {
                if (activeDownloadCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(activeDownloadCount.toString())
                    }
                } else if (totalDownloadCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ) {
                        Text(totalDownloadCount.toString())
                    }
                }
            },
        ) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = stringResource(R.string.downloads_drawer_title),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceSpecsBanner(
    deviceProfile: DeviceProfile,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val semanticColors = LocalReleaseColors.current
    val deviceDescription = stringResource(R.string.cd_device_chip)
    val cardShape = MaterialTheme.shapes.medium

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, cardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = deviceDescription }
            .testTag("device_profile_banner"),
    ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(IconBoxShape)
                        .background(semanticColors.recommendedContainer)
                        .padding(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhoneAndroid,
                        contentDescription = null,
                        tint = semanticColors.recommendedAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.this_device_title) + " • " + deviceProfile.deviceModel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = deviceProfile.summaryLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse device specs" else "Expand device specs",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Supported CPU ABIs (priority order):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        deviceProfile.supportedAbis.forEachIndexed { idx, abi ->
                            val bgColor = if (idx == 0) {
                                semanticColors.recommendedContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                            val textColor = if (idx == 0) {
                                semanticColors.onRecommendedContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = if (idx == 0) "$abi (Primary)" else abi,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = MonoFontFamily,
                                color = textColor,
                                modifier = Modifier
                                    .background(bgColor, ChipPillShape)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
    }
}

@Composable
fun RepoAnalyzerInputCard(
    urlInputFlow: StateFlow<String>,
    isLoadingFlow: StateFlow<Boolean>,
    onUrlInputChanged: (String) -> Unit,
    onPasteClicked: () -> Unit,
    onClearClicked: () -> Unit,
    onAnalyzeClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Collect urlInput and isLoading locally so keystrokes/load states never recompose the release list
    val urlInput by urlInputFlow.collectAsStateWithLifecycle()
    val isLoading by isLoadingFlow.collectAsStateWithLifecycle()
    val cardShape = MaterialTheme.shapes.large

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, cardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlInputChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("repo_url_input"),
                label = { Text(stringResource(R.string.repo_input_label)) },
                placeholder = {
                    Text(
                        text = stringResource(R.string.repo_input_placeholder),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingIcon = {
                    if (urlInput.isNotEmpty() && !isLoading) {
                        IconButton(
                            onClick = onClearClicked,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("clear_url_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_clear),
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (!isLoading) onAnalyzeClicked()
                    },
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = onPasteClicked,
                    enabled = !isLoading,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("paste_url_button"),
                    label = { Text(stringResource(R.string.action_paste)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = onAnalyzeClicked,
                    enabled = !isLoading,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("analyze_button"),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_analyzing),
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.action_analyze),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
    }
}
