package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.utils.nextLyricBoundaryMs
import com.lostf1sh.pixelplayeross.utils.resolveCurrentLineIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
 * **Scheduling.** Media3 exposes no position callback, so the active line has to be sampled. Rather
 * than sampling on a fixed interval, this controller sleeps until the *next lyric boundary* —
 * `delay = min(boundary - position, WATCHDOG_INTERVAL_MS) / speed` — and recomputes from the live
 * position after every wake-up, so inaccuracy cannot accumulate. That is roughly one wake-up per
 * lyric line instead of two per second, and **no** wake-up at all while paused, disabled, or
 * without synced lyrics. The watchdog cap is the self-healing fallback a fixed poll gets for free:
 * if an event we depend on never arrives, the title still catches up within
 * [WATCHDOG_INTERVAL_MS].
 *
 * Player events (seek, play/pause, track change, speed change, load completion) all just [signal] a
 * recompute through [Player.Listener.onEvents]; the service signals too for Bluetooth device
 * changes and for a MediaSession player replacement.
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

    /** Pending wake-up for the next lyric boundary; null while paused or past the last line. */
    private var boundaryJob: Job? = null

    /** Identifies what [boundaryJob] is waiting for, so repeated signals cannot re-arm it. */
    private var armedLineIndex: Int? = null
    private var armedBoundaryMs: Long? = null
    private var armedSpeed = 1f

    /** Wrapper the event listener is currently attached to; the service replaces it on swaps. */
    private var attachedPlayer: Player? = null

    /** Last reported gating state; only changes are logged so the loop stays quiet. */
    private var lastReportedState: String? = null

    private var lastBluetoothOutputActive = false

    /**
     * Conflated: a burst of events (a seek emits several) collapses into one recompute, and an
     * event arriving while a tick is already running is merged into the next one rather than lost.
     */
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)

    private val playerEventsListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = signal()
    }

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
                        // boundary, otherwise the head unit would keep the last lyric line on
                        // screen.
                        stopScheduling()
                        publish(player = playerProvider(), line = null)
                    }
                    Timber.tag(TAG).d("car lyric title: toggle %s", if (value) "on" else "off")
                }
                signal()
            }
        }

        scope.launch {
            for (unused in wakeUps) {
                // Never let a single bad tick kill the loop: the feature would then be silently
                // dead for the rest of the session, with nothing surfaced to the user.
                runCatching { tick() }
                    .onFailure { Timber.tag(TAG).w(it, "car lyric title: tick failed") }
            }
        }

        signal()
    }

    /** Requests a recompute of the published title. Safe to call from any thread. */
    fun signal() {
        wakeUps.trySend(Unit)
    }

    /**
     * Called when the service hands the MediaSession a new player wrapper (crossfade, engine swap).
     * The new instance starts with no override while [lastPublishedLine] still holds the previous
     * line, which would suppress the re-publish and leave the real track title on screen until the
     * line changes. Forgetting it makes the next tick publish to the new wrapper.
     */
    fun onPlayerReplaced() {
        lastPublishedLine = null
        signal()
    }

    private suspend fun tick() = withContext(Dispatchers.Main.immediate) {
        val player = playerProvider()
        if (player == null) {
            reportState("idle: player not created yet")
            return@withContext
        }
        ensureListenerAttached(player)

        if (!enabled) {
            reportState("idle: toggle off")
            stopScheduling()
            publish(player, null)
            return@withContext
        }

        if (!refreshBluetoothOutput()) {
            reportState("idle: bluetooth output not active")
            stopScheduling()
            publish(player, null)
            return@withContext
        }

        val mediaId = player.currentMediaItem?.mediaId
        if (mediaId == null) {
            reportState("idle: nothing playing")
            stopScheduling()
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
            stopScheduling()
            publish(player, null)
            return@withContext
        }

        val positionMs = player.currentPosition + syncOffsetMs
        val index = resolveCurrentLineIndex(lines, positionMs)
        reportState("active: ${lines.size} synced lines")
        publish(player, lines.getOrNull(index)?.line?.trim()?.takeIf { it.isNotEmpty() })
        scheduleNextWakeUp(player, positionMs, index)
    }

    /**
     * Switches the override over to [mediaId]. The previous override is dropped right away so the
     * outgoing track's lyric line never shows up under the incoming track's title.
     */
    private fun onSongChanged(player: LyricTitlePlayer, mediaId: String) {
        currentSongId = mediaId
        lines = emptyList()
        syncOffsetMs = 0
        stopScheduling()
        lyricsJob?.cancel()
        publish(player, null)
        lyricsJob = scope.launch {
            loadLyrics(mediaId)
            // Lyrics arriving is what makes scheduling possible again.
            signal()
        }
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
     * Arms the single pending wake-up, for the moment [index] stops being the active line. Also the
     * watchdog: capping the delay at [WATCHDOG_INTERVAL_MS] means a missed event or a very sparse
     * lyric still gets refreshed instead of leaving the title stale forever.
     *
     * Idempotent for one transition: a burst of player events (startup, crossfade) asks for a
     * recompute over and over, and re-arming each time would cancel and relaunch the timer
     * needlessly. Skipping is safe because the deadline for a given (line, boundary, speed) is
     * absolute — the residual is re-derived from the live position when the timer fires anyway.
     */
    private fun scheduleNextWakeUp(player: Player, positionMs: Long, index: Int) {
        // Paused: nothing advances on its own, and onEvents signals us when playback resumes.
        if (!player.isPlaying) {
            stopScheduling()
            return
        }
        val boundaryMs = nextLyricBoundaryMs(lines, index) ?: run {
            stopScheduling()
            return
        }
        // Lyric timestamps are wall-clock, so a time-stretched player reaches them proportionally
        // later. The previous fixed-interval poll was immune to this by construction.
        val speed = player.playbackParameters.speed.takeIf { it > 0f } ?: 1f

        if (boundaryJob?.isActive == true &&
            armedLineIndex == index &&
            armedBoundaryMs == boundaryMs &&
            armedSpeed == speed
        ) {
            return
        }

        boundaryJob?.cancel()
        armedLineIndex = index
        armedBoundaryMs = boundaryMs
        armedSpeed = speed

        val remainingMs = ((boundaryMs - positionMs) / speed)
            .toLong()
            .coerceIn(MIN_WAKE_UP_DELAY_MS, WATCHDOG_INTERVAL_MS)

        boundaryJob = scope.launch {
            delay(remainingMs)
            signal()
        }
        // Verbose on purpose: the wake-up cadence *is* the design, and this is the only way to see
        // it (a fixed poll has no such line). Suppressed in release by ReleaseTree.
        Timber.tag(TAG).v("car lyric title: next wake in %d ms (line %d)", remainingMs, index)
    }

    private fun stopScheduling() {
        boundaryJob?.cancel()
        boundaryJob = null
        armedLineIndex = null
        armedBoundaryMs = null
    }

    private fun ensureListenerAttached(player: LyricTitlePlayer) {
        if (attachedPlayer === player) return
        attachedPlayer?.removeListener(playerEventsListener)
        player.addListener(playerEventsListener)
        attachedPlayer = player
    }

    /**
     * Reads the current output route. Ticks now happen once per lyric line (or per watchdog
     * interval) instead of twice a second, so the audio service round-trip is far rarer than it
     * was and the short-lived cache the polling version needed is gone.
     */
    private fun refreshBluetoothOutput(): Boolean {
        val active = isBluetoothOutputActive()
        if (active != lastBluetoothOutputActive) {
            lastBluetoothOutputActive = active
            Timber.tag(TAG).d(
                "car lyric title: bluetooth output %s",
                if (active) "active" else "inactive"
            )
        }
        return active
    }

    /** Logs [state] whenever it differs from the previous tick, so the loop stays quiet. */
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

        /** Floor for the armed delay, so a boundary already in the past cannot spin the loop. */
        private const val MIN_WAKE_UP_DELAY_MS = 100L

        /** Ceiling for the armed delay: the fallback when no event arrives to reschedule us. */
        private const val WATCHDOG_INTERVAL_MS = 5_000L
    }
}
