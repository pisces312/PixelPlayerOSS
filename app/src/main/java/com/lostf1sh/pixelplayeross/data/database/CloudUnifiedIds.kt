package com.lostf1sh.pixelplayeross.data.database

import java.security.MessageDigest

/**
 * Stable 64-bit mapping from cloud server ids/names to negative unified Longs.
 *
 * Replaces `String.hashCode()` (32-bit, collision-prone). Collisions in the old scheme
 * silently merged two cloud songs into one primary key; this keeps the negative-id
 * partition (`id < 0` = cloud) but uses SHA-256 truncated to 63 bits so birthday
 * collisions stay negligible for music-library scale.
 *
 * [MIGRATION_13_14] rewrites existing rows from the old hash to these ids.
 */
object CloudUnifiedIds {

    fun stableHash63(value: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        var hash = 0L
        for (i in 0 until 8) {
            hash = (hash shl 8) or (digest[i].toLong() and 0xffL)
        }
        return hash and Long.MAX_VALUE
    }

    fun unifiedSongId(offset: Long, externalId: String): Long =
        -(offset + stableHash63(externalId))

    fun unifiedAlbumId(offset: Long, externalIdOrName: String): Long =
        -(offset + stableHash63(externalIdOrName))

    fun unifiedArtistId(offset: Long, artistName: String): Long =
        -(offset + stableHash63(artistName.trim().lowercase()))
}
