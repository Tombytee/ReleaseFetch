package com.tombytee.releasefetch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tombytee.releasefetch.R
import com.tombytee.releasefetch.data.local.SavedRepoEntity
import com.tombytee.releasefetch.ui.theme.MonoFontFamily

data class QuickExampleRepo(
    val label: String,
    val fullName: String,
    val hint: String,
)

private val popularExamples = listOf(
    QuickExampleRepo("Termux", "termux/termux-app", "Multi-ABI APKs"),
    QuickExampleRepo("AntennaPod", "AntennaPod/AntennaPod", "Android Releases"),
    QuickExampleRepo("Syncthing", "syncthing/syncthing", "Multi-OS Builds"),
    QuickExampleRepo("VLC / LocalSend", "localsend/localsend", "APK + Desktop"),
)

fun LazyListScope.savedReposItems(
    pinnedRepos: List<SavedRepoEntity>,
    recentRepos: List<SavedRepoEntity>,
    onRepoSelected: (String) -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
    onRemoveRepo: (String) -> Unit,
    onClearRecent: () -> Unit,
) {
    if (pinnedRepos.isNotEmpty()) {
        item(key = "pinned_section_header", contentType = "section_label") {
            SectionLabelRow(
                icon = Icons.Filled.PushPin,
                title = stringResource(R.string.pinned_repos_title),
            )
        }
        items(
            items = pinnedRepos,
            key = { "pinned_${it.fullName}" },
            contentType = { "saved_repo_row" },
        ) { repo ->
            SavedRepoRow(
                repo = repo,
                onSelect = { onRepoSelected(repo.fullName) },
                onTogglePin = { onTogglePin(repo.fullName, repo.isPinned) },
                onRemove = { onRemoveRepo(repo.fullName) },
            )
        }
    }

    if (recentRepos.isNotEmpty()) {
        item(key = "recent_section_header", contentType = "section_label") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionLabelRow(
                    icon = Icons.Filled.History,
                    title = stringResource(R.string.recent_repos_title),
                )
                TextButton(
                    onClick = onClearRecent,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            }
        }
        items(
            items = recentRepos,
            key = { "recent_${it.fullName}" },
            contentType = { "saved_repo_row" },
        ) { repo ->
            SavedRepoRow(
                repo = repo,
                onSelect = { onRepoSelected(repo.fullName) },
                onTogglePin = { onTogglePin(repo.fullName, repo.isPinned) },
                onRemove = { onRemoveRepo(repo.fullName) },
            )
        }
    }

    item(key = "quick_examples_section", contentType = "examples") {
        QuickExamplesSection(onRepoSelected = onRepoSelected)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickExamplesSection(
    onRepoSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabelRow(
            icon = Icons.Outlined.RocketLaunch,
            title = stringResource(R.string.quick_examples_title),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            popularExamples.forEach { example ->
                FilterChip(
                    selected = false,
                    onClick = { onRepoSelected(example.fullName) },
                    modifier = Modifier.testTag("example_chip_${example.label}"),
                    label = {
                        Column(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = example.label,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = example.hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionLabelRow(
    icon: ImageVector,
    title: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun SavedRepoRow(
    repo: SavedRepoEntity,
    onSelect: () -> Unit,
    onTogglePin: () -> Unit,
    onRemove: () -> Unit,
) {
    val cardShape = MaterialTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, cardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("saved_repo_${repo.fullName}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            RepoAvatar(
                avatarUrl = repo.avatarUrl,
                ownerName = repo.owner,
                sizeDp = 38,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = repo.fullName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = if (!repo.hasReleases) {
                    "No releases available"
                } else {
                    val tagPart = repo.latestTag ?: "Latest"
                    val filesPart = "${repo.assetCount} file${if (repo.assetCount == 1) "" else "s"}"
                    "$tagPart • $filesPart"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = onTogglePin,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("pin_repo_${repo.fullName}"),
            ) {
                Icon(
                    imageVector = if (repo.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (repo.isPinned) {
                        stringResource(R.string.action_unpin)
                    } else {
                        stringResource(R.string.action_pin)
                    },
                    tint = if (repo.isPinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("remove_repo_${repo.fullName}"),
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.action_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

@Composable
fun RepoAvatar(
    avatarUrl: String?,
    ownerName: String,
    sizeDp: Int = 42,
) {
    val initial = ownerName.firstOrNull()?.uppercaseChar()?.toString() ?: "G"
    val context = LocalContext.current
    val avatarDesc = stringResource(R.string.cd_repo_avatar)

    val imageRequest = remember(avatarUrl, sizeDp) {
        if (avatarUrl.isNullOrBlank()) {
            null
        } else {
            val sizedUrl = if (avatarUrl.contains("avatars.githubusercontent.com") && !avatarUrl.contains("s=")) {
                val sep = if (avatarUrl.contains('?')) '&' else '?'
                "${avatarUrl}${sep}s=96"
            } else {
                avatarUrl
            }
            ImageRequest.Builder(context)
                .data(sizedUrl)
                .size(128)
                .crossfade(false)
                .build()
        }
    }

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = avatarDesc,
                modifier = Modifier
                    .size(sizeDp.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
