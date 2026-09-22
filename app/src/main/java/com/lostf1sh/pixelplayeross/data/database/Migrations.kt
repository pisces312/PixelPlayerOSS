package com.lostf1sh.pixelplayeross.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Returns true if [table] already has a column named [column].
 *
 * Platform Auto Backup can restore a database that reports the right schema version but whose
 * columns have drifted, so migrations guard each `ALTER` with this check instead of assuming a
 * bare `ADD COLUMN` is safe (see the migration notes in CLAUDE.md / CONTRIBUTING).
 */
private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}

private fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, ddl: String) {
    if (!hasColumn(table, column)) {
        execSQL("ALTER TABLE `$table` ADD COLUMN $ddl")
    }
}

/**
 * v1 -> v2: album-artist support for the unified library (issue #8).
 *
 * - `songs.album_artist_id`: id of the *effective* album artist (the song's `album_artist` when
 *   present, otherwise its primary track artist). Source-independent, so the "Group by Album
 *   Artist" Artists tab can collapse on it at runtime without forcing a re-sync.
 * - `navidrome_songs.album_artist` / `jellyfin_songs.album_artist`: carry the server's
 *   album-artist tag through the cloud cache so the unified projection can populate the above.
 *
 * Additive and idempotent — each column is added only when missing. `album_artist_id` is then
 * backfilled to the existing primary artist so the collapsed Artists tab is populated before the
 * next library sync recomputes precise values (e.g. collapsing compilations under one artist).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing("songs", "album_artist_id", "`album_artist_id` INTEGER NOT NULL DEFAULT 0")
        db.addColumnIfMissing("navidrome_songs", "album_artist", "`album_artist` TEXT")
        db.addColumnIfMissing("jellyfin_songs", "album_artist", "`album_artist` TEXT")

        db.execSQL("UPDATE songs SET album_artist_id = artist_id WHERE album_artist_id = 0")

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_album_artist_id` ON `songs` (`album_artist_id`)")
    }
}

/**
 * v2 -> v3: opt-in ListenBrainz scrobbling + MusicBrainz identifier storage.
 *
 * - `listenbrainz_pending_listens`: offline scrobble queue. Rows snapshot track metadata at
 *   enqueue time and are deleted on successful submission or permanent rejection.
 * - `songs.mb_recording_id` / `mb_release_id` / `mb_artist_id`: MusicBrainz identifiers, read
 *   from embedded file tags or applied via tag lookup. Scrobbles carry the recording MBID when
 *   known for better server-side matching.
 *
 * Additive and idempotent, per the Auto Backup drift guard above.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `listenbrainz_pending_listens` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `listened_at_ms` INTEGER NOT NULL,
                    `track_name` TEXT NOT NULL,
                    `artist_name` TEXT NOT NULL,
                    `release_name` TEXT,
                    `duration_ms` INTEGER,
                    `recording_mbid` TEXT,
                    `source` TEXT NOT NULL,
                    `attempts` INTEGER NOT NULL DEFAULT 0,
                    `created_at_ms` INTEGER NOT NULL
                )
            """.trimIndent()
        )

        db.addColumnIfMissing("songs", "mb_recording_id", "`mb_recording_id` TEXT")
        db.addColumnIfMissing("songs", "mb_release_id", "`mb_release_id` TEXT")
        db.addColumnIfMissing("songs", "mb_artist_id", "`mb_artist_id` TEXT")
    }
}

/**
 * v3 -> v4: audio bookmarks — named position markers inside a track (audiobooks, DJ sets,
 * podcasts), grouped per song on the Bookmarks screen.
 *
 * `audio_bookmarks` snapshots song metadata at save time so a bookmark stays presentable even
 * if the underlying song leaves the library. Additive and idempotent, per the Auto Backup
 * drift guard above.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `audio_bookmarks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `song_id` TEXT NOT NULL,
                    `song_title` TEXT NOT NULL,
                    `artist_name` TEXT NOT NULL,
                    `album_art_uri` TEXT,
                    `title` TEXT NOT NULL,
                    `timestamp_ms` INTEGER NOT NULL,
                    `created_time` INTEGER NOT NULL
                )
            """.trimIndent()
        )
    }
}

/** v4 -> v5: app-private offline copies of Navidrome and Jellyfin tracks. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `offline_tracks` (
                    `download_id` TEXT NOT NULL,
                    `attempt_id` TEXT NOT NULL,
                    `song_id` TEXT NOT NULL,
                    `source_uri` TEXT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `mime_type` TEXT,
                    `local_path` TEXT,
                    `state` TEXT NOT NULL,
                    `bytes_downloaded` INTEGER NOT NULL,
                    `total_bytes` INTEGER,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `error_message` TEXT,
                    PRIMARY KEY(`download_id`)
                )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_tracks_source_uri` " +
                "ON `offline_tracks` (`source_uri`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_offline_tracks_song_id` " +
                "ON `offline_tracks` (`song_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_offline_tracks_state` " +
                "ON `offline_tracks` (`state`)"
        )
    }
}

/**
 * v5 -> v6: playlist positions are unique, while song ids may repeat.
 *
 * M3U playlists can intentionally contain the same track more than once. The previous
 * `(playlist_id, song_id)` primary key silently collapsed those entries during persistence.
 * Existing rows are copied in their current order and assigned dense positions so even legacy
 * databases with duplicate sort values migrate without dropping a song.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `playlist_songs_v6`")
        db.execSQL(
            """
                CREATE TABLE `playlist_songs_v6` (
                    `playlist_id` TEXT NOT NULL,
                    `song_id` TEXT NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    PRIMARY KEY(`playlist_id`, `sort_order`)
                )
            """.trimIndent()
        )
        db.execSQL(
            """
                INSERT INTO `playlist_songs_v6` (`playlist_id`, `song_id`, `sort_order`)
                SELECT
                    `playlist_id`,
                    `song_id`,
                    ROW_NUMBER() OVER (
                        PARTITION BY `playlist_id`
                        ORDER BY `sort_order`, `rowid`
                    ) - 1
                FROM `playlist_songs`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `playlist_songs`")
        db.execSQL("ALTER TABLE `playlist_songs_v6` RENAME TO `playlist_songs`")
        db.execSQL(
            "CREATE INDEX `index_playlist_songs_playlist_id_sort_order` " +
                "ON `playlist_songs` (`playlist_id`, `sort_order`)"
        )
        db.execSQL(
            "CREATE INDEX `index_playlist_songs_song_id` ON `playlist_songs` (`song_id`)"
        )
    }
}

/**
 * v6 -> v7: per-song rating stored alongside the favorite flag.
 *
 * A 0–5 star rating lives in the same row as the favorite status so a third-party import
 * (e.g. Poweramp) can carry both over in one upsert. The column is additive and idempotent,
 * guarded by the same Auto Backup drift check used by the earlier migrations.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing("favorites", "rating", "`rating` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v7 -> v8: AI playlist generation support tables.
 *
 * - `ai_cache`: prompt hash -> raw model response, so an identical request is served without
 *   hitting the provider again.
 * - `ai_usage`: one row per completed request, backing the token usage report in AI settings.
 *
 * Both tables are created only when missing so a database restored by Auto Backup with drifted
 * tables still migrates cleanly.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `ai_cache` (
                    `promptHash` TEXT NOT NULL,
                    `responseJson` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    PRIMARY KEY(`promptHash`)
                )
            """.trimIndent()
        )
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `ai_usage` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `provider` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `promptType` TEXT NOT NULL,
                    `promptTokens` INTEGER NOT NULL,
                    `outputTokens` INTEGER NOT NULL,
                    `thoughtTokens` INTEGER NOT NULL
                )
            """.trimIndent()
        )
    }
}

/**
 * v8 -> v9: remember the prompt an AI playlist was generated from.
 *
 * Nullable with no default value: existing rows (and every manually built playlist) simply have
 * no prompt, and the column stays out of the way for all non-AI flows.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing("playlists", "ai_prompt", "`ai_prompt` TEXT")
    }
}

/**
 * v9 -> v10: remember how an AI playlist was generated.
 *
 * - `ai_sample_mode` / `ai_sample_size`: the sampling mode name and context size actually used, so
 *   the detail screen shows what this mix was built from rather than the current preference.
 * - `ai_original_song_ids`: JSON array of the songs originally returned (in generation order),
 *   kept separately from `playlist_songs` because that table reflects the user's later edits.
 *
 * All three are nullable with no default: existing rows and every manually built playlist simply
 * carry no generation metadata.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing("playlists", "ai_sample_mode", "`ai_sample_mode` TEXT")
        db.addColumnIfMissing("playlists", "ai_sample_size", "`ai_sample_size` INTEGER")
        db.addColumnIfMissing(
            "playlists",
            "ai_original_song_ids",
            "`ai_original_song_ids` TEXT"
        )
    }
}

/**
 * v10 -> v11: keep the chain of thought the model streamed before answering.
 *
 * Nullable with no default: a playlist generated with thinking off, generated before this column
 * existed, or built by hand simply has no thought process, and both the result phase and the prompt
 * details dialog skip the section instead of showing an empty box.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing("playlists", "ai_thinking", "`ai_thinking` TEXT")
    }
}

/**
 * v11 -> v12: full release date for the Years tab "by release date" sort.
 *
 * `songs.release_date` is standardized `yyyy-MM-dd` (year-only tags become `yyyy-01-01`).
 * Three states: NULL = file never read (backfill pending), "0" = read once but no date info,
 * otherwise the date itself. MediaStore only exposes the year, so existing rows start as NULL
 * and the next sync's backfill pass resolves them from file tags.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing("songs", "release_date", "`release_date` TEXT")
    }
}

/**
 * v12 -> v13: lyrics source of truth moves to the `lyrics` table.
 *
 * Backfills `songs.lyrics` (file-embedded / legacy column) into `lyrics` as `source='embedded'`
 * when that song has no `lyrics` row yet. Idempotent: existing manual/remote rows win.
 * The `songs.lyrics` column is kept until a later migration drops it.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                INSERT OR IGNORE INTO lyrics (songId, content, isSynced, source)
                SELECT id, lyrics, 0, 'embedded'
                FROM songs
                WHERE lyrics IS NOT NULL AND lyrics != ''
            """.trimIndent()
        )
    }
}

/**
 * v13 -> v14: cloud unified ids switch from 32-bit `hashCode` to [CloudUnifiedIds] (SHA-256 / 63-bit).
 *
 * Old ids can collide and silently merge two cloud songs. Rewrites `songs` / `albums` / `artists`
 * primary keys and every referencing table (favorites, lyrics, engagements, playlist_songs, …).
 * Local MediaStore ids (positive) are untouched.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val songMap = mutableMapOf<Long, Long>()
        val albumMap = mutableMapOf<Long, Long>()
        val artistMap = mutableMapOf<Long, Long>()

        fun offsetFor(sourceType: Int): Triple<Long, Long, Long> = when (sourceType) {
            SourceType.NAVIDROME -> Triple(9_000_000_000_000L, 10_000_000_000_000L, 11_000_000_000_000L)
            SourceType.JELLYFIN -> Triple(12_000_000_000_000L, 13_000_000_000_000L, 14_000_000_000_000L)
            else -> Triple(0L, 0L, 0L)
        }

        db.query(
            "SELECT id, content_uri_string, source_type, album_id, album_name, artist_id, artist_name, album_artist_id FROM songs WHERE source_type != 0"
        ).use { c ->
            while (c.moveToNext()) {
                val oldId = c.getLong(0)
                val contentUri = c.getString(1) ?: continue
                val sourceType = c.getInt(2)
                val oldAlbumId = c.getLong(3)
                val albumName = c.getString(4) ?: ""
                val oldArtistId = c.getLong(5)
                val artistName = c.getString(6) ?: ""
                val oldAlbumArtistId = c.getLong(7)
                val (songOff, albumOff, artistOff) = offsetFor(sourceType)
                if (songOff == 0L) continue

                val externalId = when {
                    contentUri.startsWith("navidrome://") -> contentUri.removePrefix("navidrome://")
                    contentUri.startsWith("jellyfin://") -> contentUri.removePrefix("jellyfin://")
                    else -> continue
                }
                val newId = CloudUnifiedIds.unifiedSongId(songOff, externalId)
                songMap[oldId] = newId

                // Prefer server album id when the cache table still has it; else name.
                val serverAlbumId = lookupServerAlbumId(db, sourceType, externalId)
                val newAlbumId = CloudUnifiedIds.unifiedAlbumId(
                    albumOff,
                    serverAlbumId?.takeIf { it.isNotBlank() } ?: albumName.lowercase()
                )
                albumMap[oldAlbumId] = newAlbumId

                val newArtistId = CloudUnifiedIds.unifiedArtistId(artistOff, artistName)
                artistMap[oldArtistId] = newArtistId
                if (oldAlbumArtistId != 0L && oldAlbumArtistId != oldArtistId) {
                    // album artist id is rewritten from its own artist row name below if present
                }
            }
        }

        db.query("SELECT id, name FROM artists WHERE id < 0").use { c ->
            while (c.moveToNext()) {
                val oldId = c.getLong(0)
                val name = c.getString(1) ?: continue
                val newId = if (oldId < -12_000_000_000_000L) {
                    CloudUnifiedIds.unifiedArtistId(14_000_000_000_000L, name)
                } else {
                    CloudUnifiedIds.unifiedArtistId(11_000_000_000_000L, name)
                }
                artistMap[oldId] = newId
            }
        }

        db.execSQL("PRAGMA foreign_keys=OFF")
        try {
            applyIdMap(db, "songs", "id", songMap)
            applyIdMap(db, "albums", "id", albumMap)
            applyIdMap(db, "artists", "id", artistMap)
            applyIdMap(db, "songs", "album_id", albumMap)
            applyIdMap(db, "songs", "artist_id", artistMap)
            applyIdMap(db, "songs", "album_artist_id", artistMap)
            applyIdMap(db, "song_artist_cross_ref", "song_id", songMap)
            applyIdMap(db, "song_artist_cross_ref", "artist_id", artistMap)
            applyIdMap(db, "favorites", "songId", songMap)
            applyIdMap(db, "lyrics", "songId", songMap)
            applyTextIdMap(db, "song_engagements", "song_id", songMap)
            applyTextIdMap(db, "playlist_songs", "song_id", songMap)
            applyTextIdMap(db, "audio_bookmarks", "song_id", songMap)
            applyTextIdMap(db, "offline_tracks", "song_id", songMap)
        } finally {
            db.execSQL("PRAGMA foreign_keys=ON")
        }
    }

    private fun lookupServerAlbumId(db: SupportSQLiteDatabase, sourceType: Int, externalId: String): String? {
        val table = if (sourceType == SourceType.NAVIDROME) "navidrome_songs" else "jellyfin_songs"
        val keyCol = if (sourceType == SourceType.NAVIDROME) "navidrome_id" else "jellyfin_id"
        return db.query("SELECT album_id FROM `$table` WHERE `$keyCol` = ? LIMIT 1", arrayOf(externalId)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun applyIdMap(db: SupportSQLiteDatabase, table: String, column: String, map: Map<Long, Long>) {
        map.forEach { (oldId, newId) ->
            if (oldId != newId) {
                db.execSQL("UPDATE `$table` SET `$column` = ? WHERE `$column` = ?", arrayOf(newId, oldId))
            }
        }
    }

    private fun applyTextIdMap(db: SupportSQLiteDatabase, table: String, column: String, map: Map<Long, Long>) {
        map.forEach { (oldId, newId) ->
            if (oldId != newId) {
                db.execSQL(
                    "UPDATE `$table` SET `$column` = ? WHERE `$column` = ?",
                    arrayOf(newId.toString(), oldId.toString())
                )
            }
        }
    }
}
