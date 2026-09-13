package com.lostf1sh.pixelplayeross.presentation.components.subcomps

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lostf1sh.pixelplayeross.R

/**
 * Player toggle row slot dedicated to the five star rating.
 *
 * - Collapsed: an outlined star, or a filled star with the current score inside once the song is
 *   rated. Tapping expands the slot in place.
 * - Expanded: five stars plus a collapse affordance on the right. Tapping star N rates the song N
 *   and collapses again (tapping the star that already holds the rating clears it); only the
 *   collapse control dismisses without changing the rating.
 */
@Composable
fun RatingSegment(
    modifier: Modifier = Modifier,
    rating: Int,
    expanded: Boolean,
    rowCorners: Dp,
    activeColor: Color,
    activeContentColor: Color,
    ratedColor: Color,
    ratedContentColor: Color,
    inactiveColor: Color,
    inactiveContentColor: Color,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onRatingSelected: (Int) -> Unit
) {
    val targetBgColor = when {
        expanded -> activeColor
        rating > 0 -> ratedColor
        else -> inactiveColor
    }
    val contentColor = when {
        expanded -> activeContentColor
        rating > 0 -> ratedContentColor
        else -> inactiveContentColor
    }
    val bgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(durationMillis = 250),
        label = "RatingSegmentBg"
    )
    val cornerRadius by animateDpAsState(
        // A pill radius would bite into the first and last star once the slot is wide, so the
        // expanded bar keeps a plain rounded rectangle instead.
        targetValue = when {
            expanded -> 20.dp
            rating > 0 -> rowCorners
            else -> 8.dp
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "RatingSegmentCorner"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(cornerRadius))
            .background(bgColor)
            .clickable(enabled = !expanded) { onExpand() },
        contentAlignment = Alignment.Center
    ) {
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Pack stars + collapse control as one group so the chevron sits next to the last star.
                RatingStars(
                    rating = rating,
                    onRatingChange = { stars ->
                        onRatingSelected(stars)
                        onCollapse()
                    },
                    horizontalArrangement = Arrangement.Start,
                    starSize = 28.dp,
                    selectedTint = activeContentColor,
                    unselectedTint = activeContentColor.copy(alpha = 0.45f)
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = stringResource(R.string.player_rating_collapse_cd),
                    tint = activeContentColor,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onCollapse() }
                        .padding(6.dp)
                )
            }
        } else {
            val contentDescription = if (rating > 0) {
                stringResource(R.string.player_rating_value_cd, rating) +
                    ", " +
                    stringResource(R.string.player_rating_tap_hint)
            } else {
                stringResource(R.string.player_rating_cd_unrated)
            }
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (rating > 0) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = contentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(26.dp)
                )
                if (rating > 0) {
                    Text(
                        text = rating.toString(),
                        color = bgColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
