@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StatsViewModel
import com.lostf1sh.pixelplayeross.utils.formatListeningDurationCompact
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
fun StatsScreen(
    onSongClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onShowMoreHotSongs: () -> Unit,
    statsViewModel: StatsViewModel = hiltViewModel()
) {
    val uiState by statsViewModel.uiState.collectAsStateWithLifecycle()
    val summary = uiState.summary
    var songSortMetric by rememberSaveable { mutableStateOf(SongSortMetric.DURATION) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.presentation_batch_g_stats_title)) },
                actions = {
                    FilledIconButton(
                        modifier = Modifier.padding(end = 12.dp),
                        onClick = statsViewModel::requestStatsRefresh,
                        enabled = !uiState.isLoading && !uiState.isRefreshing,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.presentation_batch_g_stats_cd_refresh)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            RangeTabsHeader(
                ranges = uiState.availableRanges,
                selected = uiState.selectedRange,
                onRangeSelected = statsViewModel::onRangeSelected
            )
            PeriodSelector(
                period = uiState.selectedPeriod,
                onShift = statsViewModel::onPeriodShift,
                onReset = statsViewModel::onPeriodReset
            )

            if (uiState.isLoading && summary == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ContainedLoadingIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item(key = "metrics") {
                        MetricCardsGrid(summary = summary)
                    }
                    item(key = "timeline") {
                        TimelineChartCard(summary = summary)
                    }
                    item(key = "hot_songs") {
                        HotSongsCard(
                            summary = summary,
                            sortMetric = songSortMetric,
                            onSortMetricChange = { songSortMetric = it },
                            onSongClick = onSongClick,
                            onShowMore = onShowMoreHotSongs
                        )
                    }
                    item(key = "top_artists") {
                        TopArtistsCard(summary = summary, onArtistClick = onArtistClick)
                    }
                    item(key = "top_albums") {
                        TopAlbumsCard(summary = summary, onAlbumClick = onAlbumClick)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Range tabs
// ---------------------------------------------------------------------------

@Composable
private fun RangeTabsHeader(
    ranges: ImmutableList<StatsTimeRange>,
    selected: StatsTimeRange,
    onRangeSelected: (StatsTimeRange) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ranges.forEach { range ->
            val isSelected = range == selected
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                onClick = { onRangeSelected(range) }
            ) {
                Text(
                    text = stringResource(range.shortNameRes()),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Period selector
// ---------------------------------------------------------------------------

@Composable
private fun PeriodSelector(
    period: StatsPeriod,
    onShift: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (period.range == StatsTimeRange.ALL) return
    val nowMillis = remember { System.currentTimeMillis() }
    val label = remember(period) { periodLabel(period, nowMillis) }
    val offset = period.periodsFromCurrent(nowMillis)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { onShift(-1) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.NavigateBefore,
                    contentDescription = stringResource(R.string.presentation_batch_g_stats_period_previous)
                )
            }
            TextButton(onClick = onReset, enabled = offset != 0) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = { onShift(1) }, enabled = offset > 0) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.NavigateNext,
                    contentDescription = stringResource(R.string.presentation_batch_g_stats_period_next)
                )
            }
        }
    }
}

private fun periodLabel(period: StatsPeriod, nowMillis: Long): String {
    val zoneId = ZoneId.systemDefault()
    val start = period.startDate(nowMillis, zoneId)
    val end = period.endDateExclusive(nowMillis, zoneId)
    return when (period.range) {
        StatsTimeRange.DAY -> formatDate(start, "yyyy年M月d日")
        StatsTimeRange.WEEK -> {
            val lastDay = end?.minusDays(1)
            "${formatDate(start, "M月d日")} – ${formatDate(lastDay, "M月d日")}"
        }
        StatsTimeRange.MONTH -> formatDate(start, "yyyy年M月")
        StatsTimeRange.YEAR -> formatDate(start, "yyyy年")
        StatsTimeRange.ALL -> ""
    }
}

private fun formatDate(date: LocalDate?, pattern: String): String {
    if (date == null) return ""
    return date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

// ---------------------------------------------------------------------------
// Metric cards
// ---------------------------------------------------------------------------

@Composable
private fun MetricCardsGrid(
    summary: PlaybackStatsRepository.PlaybackStatsSummary?,
    modifier: Modifier = Modifier
) {
    val hasData = (summary?.totalDurationMs ?: 0L) > 0 || (summary?.totalPlayCount ?: 0) > 0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                title = stringResource(R.string.presentation_batch_g_stats_metric_plays),
                value = if (hasData) "${summary?.totalPlayCount ?: 0}" else "--",
                icon = Icons.Outlined.PlayCircleOutline,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = stringResource(R.string.presentation_batch_g_stats_metric_duration),
                value = if (hasData) formatListeningDurationCompact(summary?.totalDurationMs ?: 0L) else "--",
                icon = Icons.Outlined.Hearing,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                title = stringResource(R.string.presentation_batch_g_stats_metric_songs),
                value = if (hasData) "${summary?.uniqueSongs ?: 0}" else "--",
                icon = Icons.Outlined.LibraryMusic,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = stringResource(R.string.presentation_batch_g_stats_metric_artists),
                value = if (hasData) "${summary?.topArtists?.size ?: 0}" else "--",
                icon = Icons.Outlined.Face,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = contentColor.copy(alpha = 0.74f)
        )
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.8f)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Timeline chart
// ---------------------------------------------------------------------------

@Composable
private fun TimelineChartCard(
    summary: PlaybackStatsRepository.PlaybackStatsSummary?,
    modifier: Modifier = Modifier
) {
    val timeline = remember(summary) { summary?.timeline.orEmpty().toImmutableList() }
    val hasTimeline = timeline.isNotEmpty() && timeline.any { it.totalDurationMs > 0L || it.playCount > 0 }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.presentation_batch_g_stats_section_listening_timeline),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!hasTimeline) {
                StatsEmptyState(
                    icon = Icons.Outlined.PlayCircleOutline,
                    title = stringResource(R.string.presentation_batch_g_stats_empty_no_timeline_title),
                    subtitle = stringResource(R.string.presentation_batch_g_stats_empty_no_timeline_subtitle)
                )
            } else {
                SimpleBarChart(timeline)
            }
        }
    }
}

@Composable
private fun SimpleBarChart(
    timeline: ImmutableList<PlaybackStatsRepository.TimelineEntry>,
    modifier: Modifier = Modifier
) {
    val maxDuration = timeline.maxOfOrNull { it.totalDurationMs }?.coerceAtLeast(1L) ?: 1L
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        timeline.forEach { entry ->
            val fraction = (entry.totalDurationMs.toFloat() / maxDuration.toFloat()).coerceIn(0f, 1f)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((90.dp * fraction).coerceAtLeast(if (entry.totalDurationMs > 0L) 4.dp else 2.dp))
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hot songs
// ---------------------------------------------------------------------------

internal enum class SongSortMetric {
    PLAYS,
    DURATION
}

/** How many hot songs the stats screen shows before the "show more" button appears. */
private const val HOT_SONGS_VISIBLE_COUNT = 15

@Composable
private fun HotSongsCard(
    summary: PlaybackStatsRepository.PlaybackStatsSummary?,
    sortMetric: SongSortMetric,
    onSortMetricChange: (SongSortMetric) -> Unit,
    onSongClick: (String) -> Unit,
    onShowMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val songs = remember(summary, sortMetric) {
        val all = summary?.songs.orEmpty()
        when (sortMetric) {
            SongSortMetric.PLAYS -> all.sortedByDescending { it.playCount }
            SongSortMetric.DURATION -> all.sortedByDescending { it.totalDurationMs }
        }.toImmutableList()
    }
    val visibleSongs = remember(songs) { songs.take(HOT_SONGS_VISIBLE_COUNT) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.presentation_batch_g_stats_hot_songs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SortToggleButton(
                    label = stringResource(R.string.presentation_batch_g_stats_sort_plays),
                    selected = sortMetric == SongSortMetric.PLAYS,
                    onClick = { onSortMetricChange(SongSortMetric.PLAYS) }
                )
                SortToggleButton(
                    label = stringResource(R.string.presentation_batch_g_stats_sort_duration),
                    selected = sortMetric == SongSortMetric.DURATION,
                    onClick = { onSortMetricChange(SongSortMetric.DURATION) }
                )
            }
        }

        if (songs.isEmpty()) {
            StatsEmptyState(
                icon = Icons.Outlined.MusicNote,
                title = stringResource(R.string.presentation_batch_g_stats_empty_no_tracks_title),
                subtitle = stringResource(R.string.presentation_batch_g_stats_empty_no_tracks_subtitle)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                visibleSongs.forEach { song ->
                    SongRow(
                        title = song.title,
                        artist = song.artist,
                        albumArtUri = song.albumArtUri,
                        playCount = song.playCount,
                        totalDurationMs = song.totalDurationMs,
                        onClick = { onSongClick(song.songId) }
                    )
                }
            }
            if (songs.size > HOT_SONGS_VISIBLE_COUNT) {
                FilledTonalButton(
                    onClick = onShowMore,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            R.string.presentation_batch_g_stats_hot_songs_show_more,
                            songs.size
                        )
                    )
                }
            }
        }
    }
}

@Composable
internal fun SortToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        onClick = onClick
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
internal fun SongRow(
    title: String,
    artist: String,
    albumArtUri: String?,
    playCount: Int,
    totalDurationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    StatRankRow(
        title = title,
        subtitle = if (artist.isNotBlank()) {
            "$artist · ×$playCount"
        } else {
            "×$playCount"
        },
        trailing = formatListeningDurationCompact(totalDurationMs),
        onClick = onClick,
        leading = {
            SmartImage(
                model = albumArtUri,
                contentDescription = title,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp)
            )
        },
        modifier = modifier
    )
}

/**
 * One ranking row, shaped like a library row: flat surface, library spacing, 10dp corners.
 * The artist/album lists on the stats screen and the hot songs detail screen all share it.
 */
@Composable
internal fun StatRankRow(
    title: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (leading != null) {
                leading()
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Top artists
// ---------------------------------------------------------------------------

@Composable
private fun TopArtistsCard(
    summary: PlaybackStatsRepository.PlaybackStatsSummary?,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val artists = summary?.topArtists.orEmpty()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.presentation_batch_g_stats_section_top_artists),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        if (artists.isEmpty()) {
            StatsEmptyState(
                icon = Icons.Outlined.MusicNote,
                title = stringResource(R.string.presentation_batch_g_stats_empty_no_artists_title),
                subtitle = stringResource(R.string.presentation_batch_g_stats_empty_no_artists_subtitle)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                artists.forEach { artist ->
                    StatRankRow(
                        title = artist.artist,
                        subtitle = stringResource(
                            R.string.presentation_batch_g_stats_plays_tracks,
                            artist.playCount,
                            artist.uniqueSongs
                        ),
                        trailing = formatListeningDurationCompact(artist.totalDurationMs),
                        onClick = { onArtistClick(artist.artist) }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Top albums
// ---------------------------------------------------------------------------

@Composable
private fun TopAlbumsCard(
    summary: PlaybackStatsRepository.PlaybackStatsSummary?,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val albums = summary?.topAlbums.orEmpty()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.presentation_batch_g_stats_section_top_albums),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        if (albums.isEmpty()) {
            StatsEmptyState(
                icon = Icons.Outlined.Album,
                title = stringResource(R.string.presentation_batch_g_stats_empty_no_albums_title),
                subtitle = stringResource(R.string.presentation_batch_g_stats_empty_no_albums_subtitle)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                albums.forEach { album ->
                    StatRankRow(
                        title = album.album,
                        subtitle = stringResource(
                            R.string.presentation_batch_g_stats_plays_tracks,
                            album.playCount,
                            album.uniqueSongs
                        ),
                        trailing = formatListeningDurationCompact(album.totalDurationMs),
                        onClick = { onAlbumClick(album.album) }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

@Composable
internal fun StatsEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// String helpers
// ---------------------------------------------------------------------------

@androidx.annotation.StringRes
private fun StatsTimeRange.shortNameRes(): Int = when (this) {
    StatsTimeRange.DAY -> R.string.presentation_batch_g_stats_range_day_short
    StatsTimeRange.WEEK -> R.string.presentation_batch_g_stats_range_week_short
    StatsTimeRange.MONTH -> R.string.presentation_batch_g_stats_range_month_short
    StatsTimeRange.YEAR -> R.string.presentation_batch_g_stats_range_year_short
    StatsTimeRange.ALL -> R.string.presentation_batch_g_stats_range_all_short
}
