package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached response for a single AI prompt.
 *
 * The primary key is the SHA-256 hash of the prompt text, so an identical request always
 * overwrites the previous answer instead of accumulating duplicates.
 */
@Entity(tableName = "ai_cache")
data class AiCacheEntity(
    @PrimaryKey val promptHash: String,
    val responseJson: String,
    val timestamp: Long
)
