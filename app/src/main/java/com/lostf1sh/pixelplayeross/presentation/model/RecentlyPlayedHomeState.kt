package com.lostf1sh.pixelplayeross.presentation.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.map

data class RecentlyPlayedHomeState(
    val songs: ImmutableList<RecentlyPlayedSongUiModel>,
    val queue: ImmutableList<Song>
)

@Composable
fun rememberRecentlyPlayedHomeState(
    playerViewModel: PlayerViewModel,
    maxItems: Int = 64
): RecentlyPlayedHomeState {
    val playbackHistory by playerViewModel.playbackHistory.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val recentSongIds = remember(playbackHistory) {
        collectRecentlyPlayedSongIds(
            playbackHistory = playbackHistory,
            maxItems = maxItems
        )
    }
    val sourceSongsInitialValue = remember(recentSongIds) {
        if (recentSongIds.isEmpty()) persistentListOf<Song>() else null
    }
    val sourceSongs by remember(recentSongIds, playerViewModel) {
        playerViewModel.observeSongs(recentSongIds)
            .map<List<Song>, List<Song>?> { it }
    }.collectAsStateWithLifecycle(initialValue = sourceSongsInitialValue)

    val latestSongs = remember(playbackHistory, sourceSongs) {
        val songs = sourceSongs ?: return@remember persistentListOf()
        mapRecentlyPlayedSongs(
            playbackHistory = playbackHistory,
            songs = songs,
            maxItems = maxItems
        ).toImmutableList()
    }
    var songs by rememberSaveable { mutableStateOf(latestSongs) }
    val latestSongsState = rememberUpdatedState(latestSongs)

    LaunchedEffect(latestSongs, lifecycleOwner) {
        val isVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (songs.isEmpty() || !isVisible) {
            songs = latestSongs
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                songs = latestSongsState.value
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val queue = remember(songs) {
        songs.map { it.song }.toImmutableList()
    }

    return RecentlyPlayedHomeState(
        songs = songs,
        queue = queue
    )
}
