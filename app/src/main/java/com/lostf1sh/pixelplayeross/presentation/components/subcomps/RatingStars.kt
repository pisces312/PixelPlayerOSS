package com.lostf1sh.pixelplayeross.presentation.components.subcomps

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R

/**
 * Five star rating control. Tapping star N sets the rating to N; tapping the star that already
 * holds the rating clears it back to 0, so no separate "clear" affordance is needed.
 */
@Composable
fun RatingStars(
    rating: Int,
    onRatingChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    starCount: Int = 5,
    starSize: Dp = 36.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    selectedTint: Color = MaterialTheme.colorScheme.tertiary,
    unselectedTint: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (star in 1..starCount) {
            val selected = star <= rating
            val scale by animateFloatAsState(
                targetValue = if (selected) 1f else 0.88f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "RatingStarScale"
            )
            Icon(
                imageVector = if (selected) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = stringResource(R.string.song_info_cd_rate_stars, star),
                tint = if (selected) selectedTint else unselectedTint,
                modifier = Modifier
                    .size(starSize)
                    .clickable {
                        haptics.performHapticFeedback(
                            if (star == rating) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn
                        )
                        onRatingChange(if (star == rating) 0 else star)
                    }
                    .padding(4.dp)
                    .scale(scale)
            )
        }
    }
}
