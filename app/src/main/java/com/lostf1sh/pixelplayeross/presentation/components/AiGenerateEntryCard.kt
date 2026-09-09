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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Home screen entry point for AI playlist generation.
 *
 * When no provider is configured the subtitle says so and the caller routes to AI settings
 * instead of opening the generation dialog.
 */
@Composable
fun AiGenerateEntryCard(configured: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
                                .heightIn(min = 76.dp)
                                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
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
                            color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                            text =
                                    stringResource(
                                            if (configured) R.string.ai_playlist_entry_subtitle
                                            else R.string.ai_playlist_entry_unconfigured
                                    ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)
                ) {
                    Text(
                            text = stringResource(R.string.ai_playlist_entry_action),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}
