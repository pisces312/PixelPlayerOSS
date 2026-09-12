package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Home screen entry point for AI playlist generation.
 *
 * The card itself is the "describe it yourself" entry. When [onSerendipityClick] is supplied the
 * actions move to their own row below the title, so both capsules fit side by side:
 *
 * ```
 * AI Playlist
 * Describe the music you want to hear
 *             [wand Serendipity!] [sparkle Describe]
 * ```
 *
 * Serendipity takes the magic wand while the ordinary entry keeps the sparkle: the wand is what
 * makes "zero input" visually distinct, and the sparkle is already the AI family icon (article
 * header, recent mixes, settings category), so giving it to the special action would blur the two.
 *
 * When no provider is configured the subtitle says so and the caller routes to AI settings
 * instead of opening the generation sheet.
 */
@Composable
fun AiGenerateEntryCard(
        configured: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        onSerendipityClick: (() -> Unit)? = null
) {
    val shape =
            AbsoluteSmoothCornerShape(
                    cornerRadiusTL = 26.dp,
                    smoothnessAsPercentTL = 60,
                    cornerRadiusTR = 26.dp,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusBL = 26.dp,
                    smoothnessAsPercentBL = 60,
                    cornerRadiusBR = 26.dp,
                    smoothnessAsPercentBR = 60
            )

    val onCard = MaterialTheme.colorScheme.onPrimary
    val hasSecondAction = onSerendipityClick != null

    Surface(onClick = onClick, shape = shape, modifier = modifier.fillMaxWidth()) {
        Box(
                modifier =
                        Modifier.background(
                                        Brush.horizontalGradient(
                                                listOf(
                                                        MaterialTheme.colorScheme.primary,
                                                        MaterialTheme.colorScheme.tertiary
                                                )
                                        )
                                )
                                // Two rows need room: title block, 12dp gap, button row.
                                .heightIn(min = if (hasSecondAction) 100.dp else 76.dp)
                                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = onCard,
                            modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                                text = stringResource(R.string.ai_playlist_entry_title),
                                style =
                                        MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold
                                        ),
                                color = onCard
                        )
                        Text(
                                text =
                                        stringResource(
                                                if (configured) R.string.ai_playlist_entry_subtitle
                                                else R.string.ai_playlist_entry_unconfigured
                                        ),
                                style = MaterialTheme.typography.bodySmall,
                                color = onCard.copy(alpha = 0.85f)
                        )
                    }
                    // Without a second action the pill stays inline; with one it moves to the row
                    // below, which is what frees the width the subtitle needs to stay on one line.
                    if (!hasSecondAction) {
                        Spacer(modifier = Modifier.width(12.dp))
                        ActionPill(
                                text = stringResource(R.string.ai_playlist_entry_action),
                                color = onCard.copy(alpha = 0.18f)
                        )
                    }
                }
                if (onSerendipityClick != null) {
                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        ActionPill(
                                text = stringResource(R.string.ai_serendipity_button),
                                icon = Icons.Rounded.AutoFixHigh,
                                // A touch brighter than the plain pill so the special action reads
                                // as the more prominent of the two.
                                color = onCard.copy(alpha = 0.24f),
                                onClick = onSerendipityClick
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Not clickable on its own: the whole card already opens the describe sheet.
                        ActionPill(
                                text = stringResource(R.string.ai_playlist_entry_action),
                                icon = Icons.Rounded.AutoAwesome,
                                color = onCard.copy(alpha = 0.18f)
                        )
                    }
                }
            }
        }
    }
}

/** Capsule used for the card actions; a null [onClick] renders a plain label pill. */
@Composable
private fun ActionPill(
        text: String,
        color: Color,
        icon: ImageVector? = null,
        onClick: (() -> Unit)? = null
) {
    val content: @Composable () -> Unit = {
        Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) {
                Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp)
                )
            }
            Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }

    if (onClick != null) {
        Surface(onClick = onClick, shape = CircleShape, color = color) { content() }
    } else {
        Surface(shape = CircleShape, color = color) { content() }
    }
}
