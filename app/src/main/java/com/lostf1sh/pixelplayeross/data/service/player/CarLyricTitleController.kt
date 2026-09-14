package com.lostf1sh.pixelplayeross.data.service.player

import android.os.SystemClock
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.utils.resolveCurrentLineIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Drives the "car lyric title" feature: instead of the track title, session consumers (Bluetooth
 * AVRCP stack, notification, SMTC) see the lyric line matching the playback position, which is how
 * head units that can only render Title/Artist end up scrolling lyrics.
 *
 * The override is published through [LyricTitlePlayer], which fakes a metadata-changed event
 * rather than mutating the playlist; see that class for why.
 *
 * Everything is gated — the real track title is kept unless all of these hold:
 * 1. the `car lyric title` toggle is on,
 * 2. the current output is a Bluetooth A2DP device,
 * 3. a song is loaded and it has synced lyrics.
 *
 * Condition 2 exists because the override is visible to *every* MediaSession consumer, not just the
 * head unit: the notification and the lock screen read the same metadata. Without it, staring at
 * the phone with wired headphones would also show a lyric line where the song title belongs.
 *
 * Note on "does the head unit support it": there is no public API to query the peer's AVRCP
 * version, and there does not need to be — a unit that cannot render metadata simply never issues
 * `GetElementAttributes`. The override is harmless in that case.
 */
class CarLyricTitleController(
    private val scope: CoroutineScope,
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val playerProvider: () -> LyricTitlePlayer?,
    private val isBluetoothOutputActive: () -> Boolean
) {

    private var enabled = false
    private var currentSongId: String? = null
    private var lines: List<SyncedLine> = emptyList()
    private var syncOffsetMs = 0
    private var lastPublishedLine: String? = null
    private var lyricsJob: Job? = null

    /** Null until the first probe, so the very first result is always logged. */
    private var bluetoothOutputActive: Boolean? = null

    /** 0 rather than Long.MIN_VALUE: `now - Long.MIN_VALUE` overflows and stays negative, which
     *  would make the cache check below always take the early return. */
    private var lastRoutingCheckUptimeMs = 0L

    /** Last reported gating state; only changes are logged so the poll loop stays quiet. */
    private var lastReportedState: String? = null

    fun start() {
        scope.launch {
            userPreferencesRepository.carLyricTitleEnabledFlow.collect { value ->
                // Media3 verifies the application thread inside the metadata callback, so touch the
                // player from the main dispatcher rather than trusting the upstream context.
                withContext(Dispatchers.Main.immediate) {
                    if (enabled == value) return@withContext
                    enabled = value
                    if (!value) {
                        // Opting out restores the real title immediately instead of on the next
                        // tick, otherwise the head unit would keep the last lyric line on screen.
                        publish(player = playerProvider(), line = null)
                    }
                    Timber.tag(TAG).d("car lyric title: toggle %s", if (value) "on" else "off")
                }
            }
        }

        scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                // Never let a single bad tick kill the loop: the feature would then be silently
                // dead for the rest of the session, with nothing surfaced to the user.
                runCatching { tick() }
                    .onFailure { Timber.tag(TAG).w(it, "car lyric title: tick failed") }
            }
        }
    }

    private suspend fun tick() = withContext(Dispatchers.Main.immediate) {
        val player = playerProvider()
        if (player == null) {
            reportState("idle: player not created yet")
            return@withContext
        }

        if (!enabled) {
            reportState("idle: toggle off")
            publish(player, null)
            return@withContext
        }

        if (!refreshBluetoothOutput()) {
            reportState("idle: bluetooth output not active")
            publish(player, null)
            return@withContext
        }

        val mediaId = player.currentMediaItem?.mediaId
        if (mediaId == null) {
            reportState("idle: nothing playing")
            publish(player, null)
            return@withContext
        }

        if (mediaId != currentSongId) {
            reportState("switching to song $mediaId")
            onSongChanged(player, mediaId)
            return@withContext
        }

        if (lines.isEmpty()) {
            reportState("idle: no synced lyrics for song $mediaId")
            publish(player, null)
            return@withContext
        }

        reportState("active: ${lines.size} synced lines")
        val index = resolveCurrentLineIndex(lines, player.currentPosition + syncOffsetMs)
        publish(player, lines.getOrNull(index)?.line?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * Switches the override over to [mediaId]. The previous override is dropped right away so the
     * outgoing track's lyric line never shows up under the incoming track's title.
     */
    private fun onSongChanged(player: LyricTitlePlayer, mediaId: String) {
        currentSongId = mediaId
        lines = emptyList()
        syncOffsetMs = 0
        lyricsJob?.cancel()
        publish(player, null)
        lyricsJob = scope.launch { loadLyrics(mediaId) }
    }

    private suspend fun loadLyrics(mediaId: String) {
        val song = runCatching { musicRepository.getSong(mediaId).first() }.getOrNull()
        if (song == null) {
            Timber.tag(TAG).d("car lyric title: no song for media id %s, keeping track title", mediaId)
            return
        }

        val offset = runCatching { userPreferencesRepository.getLyricsSyncOffset(mediaId) }
            .getOrDefault(0)
        // May hit the network on a cold cache, same as opening the lyrics sheet does; the result is
        // persisted, so the next play of this song resolves offline.
        val lyrics = runCatching { musicRepository.getLyrics(song) }.getOrNull()

        if (currentSongId != mediaId) return
        syncOffsetMs = offset
        lines = lyrics?.synced.orEmpty()
        Timber.tag(TAG).d(
            "car lyric title: loaded %d synced lines for %s (offset %d ms)",
            lines.size,
            mediaId,
            offset
        )
    }

    /**
     * Caches the output route for [ROUTING_CHECK_INTERVAL_MS]; querying the audio service on every
     * poll would be wasteful and the route only changes on connect/disconnect.
     */
    private fun refreshBluetoothOutput(): Boolean {
        val now = SystemClock.elapsedRealtime()
        bluetoothOutputActive?.let { cached ->
            if (now - lastRoutingCheckUptimeMs < ROUTING_CHECK_INTERVAL_MS) return cached
        }

        lastRoutingCheckUptimeMs = now
        val active = isBluetoothOutputActive()
        if (active != bluetoothOutputActive) {
            bluetoothOutputActive = active
            Timber.tag(TAG).d(
                "car lyric title: bluetooth output %s",
                if (active) "active" else "inactive"
            )
        }
        return active
    }

    /** Logs [state] whenever it differs from the previous tick, so the poll loop stays quiet. */
    private fun reportState(state: String) {
        if (state == lastReportedState) return
        lastReportedState = state
        Timber.tag(TAG).d("car lyric title: %s", state)
    }

    /** Replaces the exposed title with [line], or restores the real metadata when it is null. */
    private fun publish(player: LyricTitlePlayer?, line: String?) {
        if (player == null) return
        if (line == lastPublishedLine) return
        lastPublishedLine = line
        player.publishMetadataOverride(
            line?.let { player.innerPlayer.mediaMetadata.buildUpon().setTitle(it).build() }
        )
        if (line != null) {
            Timber.tag(TAG).d("car lyric title: %s", line)
        }
    }

    companion object {
        private const val TAG = "MusicService_PixelPlayer"
        private const val POLL_INTERVAL_MS = 500L
        private const val ROUTING_CHECK_INTERVAL_MS = 2_000L
    }
}
