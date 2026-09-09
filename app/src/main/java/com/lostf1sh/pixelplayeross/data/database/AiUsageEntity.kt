package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per completed AI request, used for the usage report in AI settings.
 *
 * `thoughtTokens` tracks reasoning tokens billed by thinking-capable models; it stays 0 when the
 * provider does not report them.
 */
@Entity(tableName = "ai_usage")
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val provider: String,
    val model: String,
    val promptType: String,
    val promptTokens: Int,
    val outputTokens: Int,
    val thoughtTokens: Int
)
