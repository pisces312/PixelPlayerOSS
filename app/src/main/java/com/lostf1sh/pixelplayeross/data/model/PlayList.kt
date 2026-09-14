package com.lostf1sh.pixelplayeross.data.model

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),
    val isQueueGenerated: Boolean = false,
    val coverImageUri: String? = null,
    val coverColorArgb: Int? = null,
    val coverIconName: String? = null,
    val coverShapeType: String? = null,
    val coverShapeDetail1: Float? = null,
    val coverShapeDetail2: Float? = null,
    val coverShapeDetail3: Float? = null,
    val coverShapeDetail4: Float? = null,
    val source: String = "LOCAL",
    /**
     * The description (or Serendipity context sentence) this playlist was generated from.
     * Null for manually built playlists. Kept out of [name] on purpose: names are identifiers
     * that get searched, exported as file names and shown as queue names, while this is content.
     */
    val aiPrompt: String? = null,
    /** Name of the [com.lostf1sh.pixelplayeross.data.ai.AiLibrarySampleMode] used at generation. */
    val aiSampleMode: String? = null,
    /** How many library titles were sent to the model as context at generation. */
    val aiSampleSize: Int? = null,
    /**
     * Ordered snapshot of the songs the model returned before the user edited the result. The live
     * [songIds] can be added to, removed from or reordered; this keeps what was *originally*
     * generated, so the detail screen can show it. Empty for manual and pre-v10 playlists.
     */
    val aiOriginalSongIds: List<String> = emptyList()
)

enum class PlaylistShapeType {
    Circle,
    SmoothRect,
    RotatedPill,
    Star
}
