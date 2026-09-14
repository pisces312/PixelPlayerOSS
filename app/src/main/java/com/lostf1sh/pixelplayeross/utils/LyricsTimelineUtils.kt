package com.lostf1sh.pixelplayeross.utils

import com.lostf1sh.pixelplayeross.data.model.SyncedLine

/**
 * Timeline helpers for synced lyrics. Kept outside the Compose layer because the playback
 * service also needs to resolve the active line (car lyric title, see
 * `data/service/player/CarLyricTitleController`).
 */

/**
 * End of [line] in milliseconds, clamped to at least the start of [nextLineStartMs].
 * A line whose last word starts after the next line's timestamp (possible with
 * per-word timing) keeps its highlight until that word is reached.
 */
fun resolveLineEndTimeMs(line: SyncedLine, nextLineStartMs: Int): Long {
    val baseEnd = nextLineStartMs.toLong()
    val lastWordStart = line.words?.maxOfOrNull { it.time.toLong() } ?: line.time.toLong()
    return maxOf(baseEnd, lastWordStart + 1L)
}

/**
 * Index of the line covering [position], or -1 when nothing matches (before the first
 * line, or past the end of the last one).
 */
fun resolveCurrentLineIndex(
    lines: List<SyncedLine>,
    position: Long
): Int {
    if (lines.isEmpty()) return -1

    return lines.withIndex().lastOrNull { (index, line) ->
        val nextTime = lines.getOrNull(index + 1)?.time ?: Int.MAX_VALUE
        val lineEndTime = resolveLineEndTimeMs(line, nextTime)
        position in line.time.toLong()..<lineEndTime
    }?.index ?: -1
}
