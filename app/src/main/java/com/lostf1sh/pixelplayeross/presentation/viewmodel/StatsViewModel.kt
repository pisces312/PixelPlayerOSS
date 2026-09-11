package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository
import com.lostf1sh.pixelplayeross.data.stats.PlaybackStatsRepository.PlaybackStatsSummary
import com.lostf1sh.pixelplayeross.data.stats.StatsPeriod
import com.lostf1sh.pixelplayeross.data.stats.StatsTimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val musicRepository: MusicRepository,
    private val listeningStatsTracker: ListeningStatsTracker
) : ViewModel() {

    data class StatsUiState(
        val selectedRange: StatsTimeRange = StatsTimeRange.DAY,
        val selectedPeriod: StatsPeriod = StatsPeriod(StatsTimeRange.DAY),
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val summary: PlaybackStatsSummary? = null,
        val availableRanges: ImmutableList<StatsTimeRange> = StatsTimeRange.entries.toImmutableList()
    )

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    private val _weeklyOverview = MutableStateFlow<PlaybackStatsSummary?>(null)
    val weeklyOverview: StateFlow<PlaybackStatsSummary?> = _weeklyOverview.asStateFlow()

    @Volatile
    private var cachedSongs: List<Song>? = null

    /** Cancelled on every range change so a slow load cannot overwrite a newer selection. */
    private var rangeJob: Job? = null

    init {
        observeStatsRefreshFlow()
        viewModelScope.launch {
            // Flush the in-memory session so data accumulated since the last track change /
            // service destroy shows up on first render.
            listeningStatsTracker.flushCurrentSession()
            refreshRange(
                period = StatsPeriod.current(StatsTimeRange.DAY),
                showLoading = true
            )
        }
    }

    fun onRangeSelected(range: StatsTimeRange) {
        if (range == _uiState.value.selectedRange && !_uiState.value.isLoading) {
            return
        }
        val period = StatsPeriod.current(range)
        refreshRange(
            period = period,
            showLoading = true,
            updateWeeklyOverview = range == StatsTimeRange.WEEK && period.anchorMillis == null
        )
    }

    fun onPeriodShift(steps: Int) {
        val current = _uiState.value.selectedPeriod
        val shifted = current.shift(steps, System.currentTimeMillis())
        if (shifted == current) return
        refreshRange(
            period = shifted,
            showLoading = true,
            updateWeeklyOverview = shifted.range == StatsTimeRange.WEEK && shifted.anchorMillis == null
        )
    }

    fun onPeriodReset() {
        val current = _uiState.value.selectedPeriod
        val reset = StatsPeriod.current(current.range)
        if (reset == current) return
        refreshRange(
            period = reset,
            showLoading = true,
            updateWeeklyOverview = reset.range == StatsTimeRange.WEEK
        )
    }

    fun refreshWeeklyOverview() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val songs = loadSongs()
                    playbackStatsRepository.loadSummary(StatsTimeRange.WEEK, songs)
                }
            }.onSuccess { summary ->
                _weeklyOverview.value = summary
            }.onFailure { throwable ->
                Timber.e(throwable, "Failed to load weekly stats overview")
                _weeklyOverview.value = null
            }
        }
    }

    private fun refreshRange(
        period: StatsPeriod,
        showLoading: Boolean = true,
        updateWeeklyOverview: Boolean = false
    ) {
        rangeJob?.cancel()
        rangeJob = viewModelScope.launch {
            if (showLoading) {
                _uiState.update { it.copy(isLoading = true, isRefreshing = false, selectedRange = period.range, selectedPeriod = period) }
            } else {
                _uiState.update { it.copy(isRefreshing = true, selectedRange = period.range, selectedPeriod = period) }
            }
            val summary = runCatching {
                withContext(Dispatchers.IO) {
                    val songs = loadSongs()
                    playbackStatsRepository.loadSummary(period, songs)
                }
            }
            summary.getOrNull()?.let { loaded ->
                if (updateWeeklyOverview) {
                    _weeklyOverview.value = loaded
                }
            }
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    isRefreshing = false,
                    summary = summary.getOrNull(),
                    selectedRange = period.range,
                    selectedPeriod = period
                )
            }
            summary.exceptionOrNull()?.let { Timber.e(it, "Failed to load stats for range %s", period) }
        }
    }

    private fun observeStatsRefreshFlow() {
        viewModelScope.launch {
            playbackStatsRepository.refreshFlow
                .drop(1)
                .collectLatest {
                    val selectedPeriod = _uiState.value.selectedPeriod
                    refreshRange(
                        period = selectedPeriod,
                        showLoading = false,
                        updateWeeklyOverview = selectedPeriod.range == StatsTimeRange.WEEK && selectedPeriod.anchorMillis == null
                    )
                    if (selectedPeriod.range != StatsTimeRange.WEEK || selectedPeriod.anchorMillis != null) {
                        refreshWeeklyOverview()
                    }
                }
        }
    }

    fun resolveArtistId(name: String): Long? {
        return cachedSongs?.firstOrNull { it.displayArtist.equals(name, ignoreCase = true) }?.artistId
    }

    fun resolveAlbumId(name: String): Long? {
        return cachedSongs?.firstOrNull { it.album.equals(name, ignoreCase = true) }?.albumId
    }

    fun requestStatsRefresh() {
        playbackStatsRepository.requestRefresh()
    }

    fun forceRegenerateStats() {
        cachedSongs = null
        playbackStatsRepository.requestRefresh()
    }

    private suspend fun loadSongs(): List<Song> {
        cachedSongs?.let { existing ->
            if (existing.isNotEmpty()) return existing
        }
        val songs = musicRepository.getAllSongsOnce()
        cachedSongs = songs
        return songs
    }
}
