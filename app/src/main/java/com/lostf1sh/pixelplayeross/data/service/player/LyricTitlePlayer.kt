package com.lostf1sh.pixelplayeross.data.service.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Outermost session-facing player wrapper that lets the service replace the outgoing media
 * metadata (typically the title) without touching the inner player's playlist.
 *
 * Media3's [androidx.media3.session.MediaSession] caches metadata from
 * `Player.Listener.onMediaMetadataChanged` and pushes it to the legacy session stub, which is
 * exactly the channel the Bluetooth AVRCP stack reads (see `MediaSessionImpl.onMediaMetadataChanged`).
 * Overriding [getMediaMetadata] alone is therefore not enough: external consumers only refresh
 * when that listener callback fires. The only supported way to produce the callback through the
 * inner player is `replaceMediaItem`/`setMediaItem`, which also emits `onMediaItemTransition`
 * and triggers unrelated side effects (listening-stats sync, scrobble, snapshot persistence).
 *
 * This wrapper keeps its own reference to every registered listener so it can dispatch
 * `onMediaMetadataChanged` directly, giving session consumers a metadata update with zero
 * playlist mutation on the inner player.
 *
 * Listeners registered through [addListener] are still forwarded to the inner player, so all
 * other events behave exactly as before.
 */
@OptIn(UnstableApi::class)
class LyricTitlePlayer(
    val innerPlayer: Player
) : ForwardingPlayer(innerPlayer) {

    /**
     * Mirror of the listeners registered on this wrapper. Media3 requires session consumers to
     * receive exactly the events they subscribed to; we reuse this list to emit the synthetic
     * metadata update without going through the inner player.
     */
    private val registeredListeners = CopyOnWriteArrayList<Player.Listener>()

    @Volatile
    private var metadataOverride: MediaMetadata? = null

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        if (!registeredListeners.contains(listener)) {
            registeredListeners.add(listener)
        }
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        registeredListeners.remove(listener)
    }

    override fun getMediaMetadata(): MediaMetadata {
        return metadataOverride ?: super.getMediaMetadata()
    }

    /**
     * Replaces the metadata exposed to session consumers. Pass `null` to fall back to the inner
     * player's metadata. Must be called on the application thread, because Media3 verifies the
     * thread inside the listener callback.
     */
    fun publishMetadataOverride(override: MediaMetadata?) {
        if (metadataOverride == override) return
        metadataOverride = override
        val published = mediaMetadata
        registeredListeners.forEach { listener ->
            runCatching { listener.onMediaMetadataChanged(published) }
        }
    }
}
