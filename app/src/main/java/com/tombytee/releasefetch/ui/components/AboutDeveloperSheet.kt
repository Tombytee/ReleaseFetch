package com.tombytee.releasefetch.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.tombytee.releasefetch.R
import com.tombytee.releasefetch.ui.theme.LocalReleaseColors
import com.tombytee.releasefetch.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val DEVELOPER_AVATAR_URL =
    "https://i.ibb.co/mtsMgL6/Whats-App-Image-2026-11-14-at-5-40-19-PM.jpg"

enum class SocialPlatform {
    GITHUB,
    INSTAGRAM,
    THREADS,
    YOUTUBE,
}

@Immutable
data class DeveloperSocialLink(
    val platform: SocialPlatform,
    val label: String,
    val handle: String,
    val url: String,
    val testTag: String,
)

object DeveloperProfileData {
    val socialLinks: List<DeveloperSocialLink> = listOf(
        DeveloperSocialLink(
            platform = SocialPlatform.GITHUB,
            label = "GitHub",
            handle = "@Tombytee",
            url = "https://github.com/Tombytee",
            testTag = "social_link_github",
        ),
        DeveloperSocialLink(
            platform = SocialPlatform.INSTAGRAM,
            label = "Instagram",
            handle = "@im.xubhh",
            url = "https://www.instagram.com/im.xubhh/",
            testTag = "social_link_instagram",
        ),
        DeveloperSocialLink(
            platform = SocialPlatform.THREADS,
            label = "Threads",
            handle = "@tombytee",
            url = "https://www.threads.com/@tombytee",
            testTag = "social_link_threads",
        ),
        DeveloperSocialLink(
            platform = SocialPlatform.YOUTUBE,
            label = "YouTube",
            handle = "@tom-bytee",
            url = "https://youtube.com/@tom-bytee?si=9vpZM-UZWOAN9vTO",
            testTag = "social_link_youtube",
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AboutDeveloperBottomSheet(
    onDismissSheet: () -> Unit,
    onOpenUrlError: (String) -> Unit,
    onOpenUrl: ((String) -> Boolean)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val fallbackErrorTemplate = stringResource(R.string.error_open_external_link)

    val avatarProgress = remember { Animatable(0f) }
    val identityProgress = remember { Animatable(0f) }
    val socialProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        val enterSpec = spring<Float>(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )
        launch { avatarProgress.animateTo(1f, enterSpec) }
        launch {
            delay(45L)
            identityProgress.animateTo(1f, enterSpec)
        }
        launch {
            delay(90L)
            socialProgress.animateTo(1f, enterSpec)
        }
    }

    val openLink: (DeveloperSocialLink) -> Unit = remember(context, onOpenUrl, onOpenUrlError, fallbackErrorTemplate) {
        { link ->
            val handled = if (onOpenUrl != null) {
                onOpenUrl(link.url)
            } else {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.isSuccess
            }
            if (!handled) {
                onOpenUrlError(String.format(fallbackErrorTemplate, link.label))
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissSheet,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .testTag("about_developer_sheet"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Sheet Title Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.about_developer_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                RoundedCornerShape(50),
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Indie Creator",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = MonoFontFamily,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                // Creator Hero Card with expressive gradient backdrop & interactive pulse
                CreatorHeroCard(
                    avatarEntrance = avatarProgress.value,
                    identityEntrance = identityProgress.value,
                )

                // Social Links Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val p = socialProgress.value
                            alpha = p.coerceIn(0f, 1f)
                            translationY = (1f - p) * 28f
                        },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_connect_heading),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DeveloperProfileData.socialLinks.forEach { link ->
                            SocialPillChip(
                                link = link,
                                onClick = { openLink(link) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorHeroCard(
    avatarEntrance: Float,
    identityEntrance: Float,
) {
    val semanticColors = LocalReleaseColors.current
    val cardShape = MaterialTheme.shapes.large
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var isHighlighted by remember { mutableStateOf(false) }

    val cardScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.98f
            isHighlighted -> 1.015f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "creator_card_scale",
    )
    val cardElevation by animateDpAsState(
        targetValue = if (isHighlighted) 8.dp else 2.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "creator_card_elevation",
    )

    val cardBrush = Brush.linearGradient(
        colors = listOf(
            semanticColors.heroGradientStart,
            MaterialTheme.colorScheme.surface,
            semanticColors.heroGradientEnd,
        ),
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            },
        shape = cardShape,
        shadowElevation = cardElevation,
        tonalElevation = if (isHighlighted) 4.dp else 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (isHighlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBrush)
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = ripple(),
                        onClick = { isHighlighted = !isHighlighted },
                    )
                    .testTag("developer_avatar_frame")
                    .graphicsLayer {
                        val p = avatarEntrance
                        alpha = p.coerceIn(0f, 1f)
                        val s = 0.92f + (0.08f * p)
                        scaleX = s
                        scaleY = s
                    },
            ) {
                AnimatedGradientAvatar(
                    avatarUrl = DEVELOPER_AVATAR_URL,
                    isBoosted = isHighlighted,
                )
            }

            Column(
                modifier = Modifier.graphicsLayer {
                    val p = identityEntrance
                    alpha = p.coerceIn(0f, 1f)
                    translationY = (1f - p) * 20f
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.developer_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("developer_name_text"),
                )

                // Expressive credit pill
                Row(
                    modifier = Modifier
                        .background(
                            color = semanticColors.recommendedContainer,
                            shape = RoundedCornerShape(50),
                        )
                        .border(
                            width = 1.dp,
                            color = semanticColors.recommendedAccent.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(50),
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = semanticColors.recommendedAccent,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = stringResource(R.string.developer_credit),
                        style = MaterialTheme.typography.labelLarge,
                        color = semanticColors.onRecommendedContainer,
                        modifier = Modifier.testTag("developer_credit_text"),
                    )
                }

                Text(
                    text = stringResource(R.string.developer_role_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = stringResource(R.string.developer_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("developer_subtitle_text"),
                )
            }
        }
    }
}

@Composable
private fun AnimatedGradientAvatar(
    avatarUrl: String,
    isBoosted: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val semanticColors = LocalReleaseColors.current
    val context = LocalContext.current

    val infiniteTransition = rememberInfiniteTransition(label = "avatar_border_transition")
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "avatar_ring_rotation",
    )
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "avatar_glow_pulse",
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.24f,
        targetValue = 0.50f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "avatar_glow_alpha",
    )

    val ringColors = remember(colorScheme, semanticColors) {
        listOf(
            colorScheme.primary,
            colorScheme.tertiary,
            semanticColors.recommendedAccent,
            colorScheme.secondary,
            colorScheme.primaryContainer,
            colorScheme.primary,
        )
    }

    var isImageLoaded by remember { mutableStateOf(false) }

    val imageRequest = remember(avatarUrl, context) {
        ImageRequest.Builder(context)
            .data(avatarUrl)
            .crossfade(true)
            .build()
    }

    Box(
        modifier = modifier.size(114.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Subtle breathing glow halo behind the ring (drawn in graphics/canvas layer)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val boostScale = if (isBoosted) 1.05f else 1f
                    scaleX = glowPulse * boostScale
                    scaleY = glowPulse * boostScale
                }
                .drawBehind {
                    val radius = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                colorScheme.primary.copy(alpha = glowAlpha),
                                colorScheme.tertiary.copy(alpha = glowAlpha * 0.55f),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                    )
                },
        )

        // Rotating Material 3 gradient border ring
        Canvas(
            modifier = Modifier.size(100.dp),
        ) {
            val strokeWidth = 4.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2f
            rotate(degrees = ringRotation) {
                drawCircle(
                    brush = Brush.sweepGradient(colors = ringColors, center = Offset(size.width / 2f, size.height / 2f)),
                    radius = radius,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }

        // Inner crisp separation surface + circular avatar image with monogram fallback
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(colorScheme.surface)
                .padding(3.dp)
                .clip(CircleShape)
                .background(colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (!isImageLoaded) {
                Text(
                    text = "SK",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onPrimaryContainer,
                )
            }

            AsyncImage(
                model = imageRequest,
                contentDescription = stringResource(R.string.cd_developer_avatar),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    isImageLoaded = state is AsyncImagePainter.State.Success
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        }
    }
}

@Composable
private fun SocialPillChip(
    link: DeveloperSocialLink,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val semanticColors = LocalReleaseColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "social_pill_scale_${link.platform.name}",
    )

    val accentColor = when (link.platform) {
        SocialPlatform.GITHUB -> colorScheme.primary
        SocialPlatform.INSTAGRAM -> colorScheme.tertiary
        SocialPlatform.THREADS -> semanticColors.iosBadge
        SocialPlatform.YOUTUBE -> colorScheme.error
    }

    val iconVector = remember(link.platform) {
        when (link.platform) {
            SocialPlatform.GITHUB -> GitHubVectorIcon
            SocialPlatform.INSTAGRAM -> InstagramVectorIcon
            SocialPlatform.THREADS -> ThreadsVectorIcon
            SocialPlatform.YOUTUBE -> YouTubeVectorIcon
        }
    }

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
            )
            .testTag(link.testTag),
        shape = RoundedCornerShape(50),
        color = colorScheme.surfaceVariant.copy(alpha = 0.65f),
        tonalElevation = if (isPressed) 4.dp else 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = accentColor.copy(alpha = 0.42f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
            }

            Column {
                Text(
                    text = link.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = link.handle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(2.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private val GitHubVectorIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "GitHubMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            moveTo(12f, 2f)
            curveTo(6.475f, 2f, 2f, 6.475f, 2f, 12f)
            curveTo(2f, 16.425f, 4.8625f, 20.1625f, 8.8375f, 21.4875f)
            curveTo(9.3375f, 21.575f, 9.525f, 21.275f, 9.525f, 21.0125f)
            curveTo(9.525f, 20.775f, 9.5125f, 19.9875f, 9.5125f, 19.15f)
            curveTo(7f, 19.6125f, 6.35f, 18.5375f, 6.15f, 17.975f)
            curveTo(6.0375f, 17.6875f, 5.55f, 16.8f, 5.125f, 16.5625f)
            curveTo(4.775f, 16.375f, 4.275f, 15.9125f, 5.1125f, 15.9f)
            curveTo(5.9f, 15.8875f, 6.4625f, 16.625f, 6.65f, 16.925f)
            curveTo(7.55f, 18.4375f, 8.9875f, 18.0125f, 9.5625f, 17.75f)
            curveTo(9.65f, 17.1f, 9.9125f, 16.6625f, 10.2f, 16.4125f)
            curveTo(7.975f, 16.1625f, 5.65f, 15.3f, 5.65f, 11.475f)
            curveTo(5.65f, 10.3875f, 6.0375f, 9.4875f, 6.675f, 8.7875f)
            curveTo(6.575f, 8.5375f, 6.225f, 7.5125f, 6.775f, 6.1375f)
            curveTo(6.775f, 6.1375f, 7.6125f, 5.875f, 9.525f, 7.1625f)
            curveTo(10.325f, 6.9375f, 11.175f, 6.825f, 12.025f, 6.825f)
            curveTo(12.875f, 6.825f, 13.725f, 6.9375f, 14.525f, 7.1625f)
            curveTo(16.4375f, 5.8625f, 17.275f, 6.1375f, 17.275f, 6.1375f)
            curveTo(17.825f, 7.5125f, 17.475f, 8.5375f, 17.375f, 8.7875f)
            curveTo(18.0125f, 9.4875f, 18.4f, 10.375f, 18.4f, 11.475f)
            curveTo(18.4f, 15.3125f, 16.0625f, 16.1625f, 13.8375f, 16.4125f)
            curveTo(14.2f, 16.725f, 14.5125f, 17.325f, 14.5125f, 18.2625f)
            curveTo(14.5125f, 19.6f, 14.5f, 20.675f, 14.5f, 21.0125f)
            curveTo(14.5f, 21.275f, 14.6875f, 21.5875f, 15.1875f, 21.4875f)
            curveTo(19.1375f, 20.1625f, 22f, 16.4125f, 22f, 12f)
            curveTo(22f, 6.475f, 17.525f, 2f, 12f, 2f)
            close()
        }
    }.build()
}

private val InstagramVectorIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "InstagramMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Outer rounded square frame
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(7f, 3f)
            lineTo(17f, 3f)
            arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 21f, 7f)
            lineTo(21f, 17f)
            arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17f, 21f)
            lineTo(7f, 21f)
            arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 17f)
            lineTo(3f, 7f)
            arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7f, 3f)
            close()
        }
        // Center camera lens circle
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 8f)
            arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 8f)
            close()
        }
        // Top-right flash dot
        path(
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
        ) {
            moveTo(17.5f, 6.5f)
            arcTo(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.49f, 6.5f)
            close()
        }
    }.build()
}

private val ThreadsVectorIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ThreadsMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Stylized '@' swirl mark inspired by Threads
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(15.2f, 12.2f)
            curveTo(15.2f, 14.1f, 13.7f, 15.5f, 11.8f, 15.5f)
            curveTo(10.0f, 15.5f, 8.7f, 14.2f, 8.7f, 12.4f)
            curveTo(8.7f, 10.5f, 10.1f, 9.1f, 12.0f, 9.1f)
            curveTo(13.8f, 9.1f, 15.2f, 10.4f, 15.2f, 12.2f)
            curveTo(15.2f, 15.6f, 17.9f, 15.6f, 18.8f, 13.9f)
            curveTo(19.9f, 11.8f, 19.3f, 8.1f, 16.9f, 6.0f)
            curveTo(14.5f, 3.8f, 10.2f, 3.6f, 7.4f, 5.7f)
            curveTo(4.3f, 8.0f, 3.5f, 12.5f, 5.4f, 16.0f)
            curveTo(7.2f, 19.3f, 11.6f, 20.6f, 15.3f, 19.3f)
            curveTo(16.6f, 18.8f, 17.7f, 18.0f, 18.5f, 17.0f)
        }
    }.build()
}

private val YouTubeVectorIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "YouTubeMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            // Rounded rectangle body
            moveTo(21.58f, 7.19f)
            curveTo(21.35f, 6.33f, 20.67f, 5.65f, 19.81f, 5.42f)
            curveTo(18.25f, 5f, 12f, 5f, 12f, 5f)
            curveTo(12f, 5f, 5.75f, 5f, 4.19f, 5.42f)
            curveTo(3.33f, 5.65f, 2.65f, 6.33f, 2.42f, 7.19f)
            curveTo(2f, 8.75f, 2f, 12f, 2f, 12f)
            curveTo(2f, 12f, 2f, 15.25f, 2.42f, 16.81f)
            curveTo(2.65f, 17.67f, 3.33f, 18.35f, 4.19f, 18.58f)
            curveTo(5.75f, 19f, 12f, 19f, 12f, 19f)
            curveTo(12f, 19f, 18.25f, 19f, 19.81f, 18.58f)
            curveTo(20.67f, 18.35f, 21.35f, 17.67f, 21.58f, 16.81f)
            curveTo(22f, 15.25f, 22f, 12f, 22f, 12f)
            curveTo(22f, 12f, 22f, 8.75f, 21.58f, 7.19f)
            close()
            // Inner play triangle cutout
            moveTo(10f, 15f)
            lineTo(10f, 9f)
            lineTo(15.2f, 12f)
            lineTo(10f, 15f)
            close()
        }
    }.build()
}
