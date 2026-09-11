package com.lostf1sh.pixelplayeross.data.backup.restore

import com.lostf1sh.pixelplayeross.data.database.SongSummary

/**
 * Metadata of a backed-up playlist song, persisted alongside unresolved references so they can
 * be matched against the local library once a sync has populated it. Serialized with the backup
 * Gson both inside the backup payload and in the pending-resolution preference.
 */
data class PendingSongRef(
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long
)

/**
 * Pure (Android-free) matcher that maps backup song IDs to the IDs of songs in the current local
 * library. Shared by the one-shot restore pass ([com.lostf1sh.pixelplayeross.data.backup.module.
 * PlaylistsModuleHandler]) and the deferred post-sync pass ([PlaylistRestoreResolver]) so both use
 * exactly the same matching rules.
 *
 * Strategy per backup ID:
 * 1. Direct ID match, verified against stored metadata when available → keep the ID.
 * 2. Metadata match on normalized title + artist, disambiguated by album then duration.
 * 3. No confident match → null; callers decide whether to keep the reference for a later pass.
 */
class PlaylistSongMatcher(summaries: List<SongSummary>) {

    val isLibraryEmpty: Boolean = summaries.isEmpty()

    private val byId: Map<String, SongSummary> = summaries.associateBy { it.id.toString() }

    private val metadataIndex: Map<String, List<SongSummary>> =
        buildMap<String, MutableList<SongSummary>> {
            summaries.forEach { song ->
                getOrPut(matchKey(song.title, song.artistName)) { mutableListOf() }.add(song)
            }
        }

    /**
     * Resolves a single backup ID. [metadata] comes from the backup (or the pending store) and may
     * be null for legacy payloads that never carried it.
     */
    fun resolve(backupSongId: String, metadata: PendingSongRef?): String? {
        val directMatch = byId[backupSongId]
        if (directMatch != null) {
            if (metadata == null) return backupSongId
            if (metadataMatches(metadata, directMatch)) return backupSongId
        }

        if (metadata == null) {
            // No metadata to fall back on: only an exact ID hit is trustworthy.
            return if (directMatch != null) backupSongId else null
        }

        val candidates = metadataIndex[matchKey(metadata.title, metadata.artist)] ?: return null
        if (candidates.size == 1) return candidates[0].id.toString()

        val albumMatches = candidates.filter { candidate ->
            normalize(candidate.albumName) == normalize(metadata.album)
        }
        if (albumMatches.size == 1) return albumMatches[0].id.toString()

        val durationPool = albumMatches.ifEmpty { candidates }
        val durationMatches = durationPool.filter { candidate ->
            kotlin.math.abs(candidate.duration - metadata.duration) <= DURATION_TOLERANCE_MS
        }
        return if (durationMatches.size == 1) durationMatches[0].id.toString() else null
    }

    private fun metadataMatches(metadata: PendingSongRef, song: SongSummary): Boolean =
        normalize(metadata.title) == normalize(song.title) &&
            normalize(metadata.artist) == normalize(song.artistName)

    private fun matchKey(title: String, artist: String): String =
        "${normalize(title)}|${normalize(artist)}"

    private fun normalize(text: String): String = text.trim().lowercase()

    companion object {
        private const val DURATION_TOLERANCE_MS = 2000L
    }
}
