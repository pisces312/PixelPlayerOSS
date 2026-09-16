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

/**
 * One publishable slice of the lyric timeline: a whole line, or one of the segments a line too
 * long for a single-line title field is split into.
 *
 * @param sequence id increasing with the timeline. Together with [text] it forms the publisher's
 *   de-duplication key, so two slices that happen to read the same text are still published
 *   separately.
 * @param timeMs playback position at which this slice becomes the current one.
 * @param text slice contents; empty for a line that carries no lyrics (an instrumental marker).
 */
data class LyricCue(
    val sequence: Int,
    val timeMs: Long,
    val text: String
)

/**
 * Flattens [lines] into the ordered sequence of slices to publish.
 *
 * A line is cut into `ceil(length / maxCharsPerCue)` segments of near-equal length, spread evenly
 * over that line's own duration. Splitting exists because the consumer of these titles is a head
 * unit title field that renders only a handful of characters and would otherwise truncate the
 * tail of the line; spreading evenly in time is what keeps every segment in step with the vocal.
 *
 * @param trackDurationMs consulted for the last line only, which has no successor to borrow an
 *   end from. Pass the player duration; pass anything not greater than the last line's start when
 *   it is unknown (the player reports `C.TIME_UNSET` in that case, which is `Long.MIN_VALUE`).
 * @param maxCharsPerCue how many characters one slice may carry; must be positive.
 */
fun buildLyricCues(
    lines: List<SyncedLine>,
    trackDurationMs: Long,
    maxCharsPerCue: Int
): List<LyricCue> {
    if (lines.isEmpty()) return emptyList()

    val cues = ArrayList<LyricCue>()
    var sequence = 0

    lines.forEachIndexed { index, line ->
        val startMs = line.time.toLong()
        val segments = splitIntoSegments(line.line.trim(), maxCharsPerCue)
        val endMs = lineEndMs(lines, index, startMs, trackDurationMs, segments.size)
        val spanMs = (endMs - startMs).coerceAtLeast(0L)

        segments.forEachIndexed { segmentIndex, segment ->
            cues += LyricCue(
                sequence = sequence++,
                timeMs = startMs + spanMs * segmentIndex / segments.size,
                text = segment
            )
        }
    }

    // Per-word timings can push a line's end past the *next* line's timestamp, which would leave
    // the list out of order, and [resolveCueIndex] relies on it being ordered. `sortedBy` is
    // stable, so slices sharing a timestamp keep the order they were built in.
    return cues.sortedBy { it.timeMs }
}

/**
 * Index of the slice covering [position], or -1 when [position] precedes the first one (intro).
 *
 * The last slice stays current to the end of the track, matching [resolveCurrentLineIndex], so
 * the lyrics never go blank at the tail of a song.
 */
fun resolveCueIndex(cues: List<LyricCue>, position: Long): Int =
    cues.indexOfLast { it.timeMs <= position }

/**
 * Position at which the slice at [index] stops being the current one, or null when there is none
 * left: before the first slice (intro) the boundary is that slice's start; on or past the last
 * slice there is nothing left to announce. The car lyric title controller arms its next wake-up
 * with this.
 */
fun nextCueTimeMs(cues: List<LyricCue>, index: Int): Long? {
    if (cues.isEmpty()) return null
    if (index < 0) return cues.first().timeMs
    if (index >= cues.lastIndex) return null
    return cues[index + 1].timeMs
}

/**
 * End of the line at [index]. A line with a successor ends where that successor starts (see
 * [resolveLineEndTimeMs] for the per-word exception); the final line has nobody to borrow from and
 * falls back to [trackDurationMs], or to [FALLBACK_CUE_DURATION_MS] per segment when the track
 * duration is unknown.
 *
 * `C.TIME_UNSET` is `Long.MIN_VALUE`, which **must** be rejected by comparison: subtracting it
 * first would overflow into a positive span and silently stretch the last line across the whole
 * track.
 */
private fun lineEndMs(
    lines: List<SyncedLine>,
    index: Int,
    startMs: Long,
    trackDurationMs: Long,
    segmentCount: Int
): Long {
    val next = lines.getOrNull(index + 1)
    if (next != null) return resolveLineEndTimeMs(lines[index], next.time)

    if (trackDurationMs > startMs) return trackDurationMs
    return startMs + FALLBACK_CUE_DURATION_MS * segmentCount
}

/**
 * Cuts [text] into near-equal segments of at most [maxCharsPerCue] characters each. The remainder
 * of an uneven division goes to the leading segments, so no two segments differ by more than one
 * character and none can exceed the cap.
 */
private fun splitIntoSegments(text: String, maxCharsPerCue: Int): List<String> {
    if (text.isEmpty()) return listOf("")
    val segmentCount = (text.length + maxCharsPerCue - 1) / maxCharsPerCue
    if (segmentCount <= 1) return listOf(text)

    val baseLength = text.length / segmentCount
    val remainder = text.length % segmentCount

    // Ideal cuts are cumulative and never depend on a previous adjustment, so one moved cut
    // cannot drag the rest of the line along with it.
    val idealCuts = IntArray(segmentCount - 1)
    var running = 0
    for (index in 0 until segmentCount - 1) {
        running += baseLength + if (index < remainder) 1 else 0
        idealCuts[index] = running
    }

    val segments = ArrayList<String>(segmentCount)
    var start = 0
    for (index in idealCuts.indices) {
        val nextIdealCut = idealCuts.getOrNull(index + 1) ?: text.length
        val cut = breakAdjustedCut(text, idealCuts[index], nextIdealCut, start, maxCharsPerCue)
        // Trimmed so a cut that landed right after a space does not publish a trailing blank; a
        // segment that is nothing but the break itself collapses to empty and is dropped by the
        // publisher, which is also what an instrumental marker does.
        segments += text.substring(start, cut).trim()
        start = cut
    }
    segments += text.substring(start).trim()
    return segments
}

/**
 * Pulls [idealCut] back to the nearest break character, so a word is not bisected mid-way — but
 * only as far as keeps both the segment ending here and the one starting here within
 * [maxCharsPerCue].
 *
 * The *ideal* next cut is what gets checked, not the final one: the next cut may move back as
 * well, so this is a deliberate approximation rather than a search. Erring on the side of
 * `idealCut` is always safe, because the ideal lengths already respect the cap.
 */
private fun breakAdjustedCut(
    text: String,
    idealCut: Int,
    nextIdealCut: Int,
    previousCut: Int,
    maxCharsPerCue: Int
): Int {
    val earliestCut = (idealCut - MAX_CUT_LOOKBACK_CHARS).coerceAtLeast(previousCut + 1)
    for (cut in idealCut downTo earliestCut) {
        if (!isBreakCharacter(text[cut - 1])) continue
        if (nextIdealCut - cut > maxCharsPerCue) continue
        return cut
    }
    return idealCut
}

/**
 * Whether a segment may end just after [character]. Whitespace and the punctuation a phrase
 * naturally ends on, so both `Hello world` and `你好，世界` break on a readable boundary. Opening
 * brackets are deliberately absent: they belong with the text that follows them.
 */
private fun isBreakCharacter(character: Char): Boolean =
    character.isWhitespace() || character in BREAK_CHARACTERS

/** Per-segment length when the track duration is unknown (`C.TIME_UNSET`). */
private const val FALLBACK_CUE_DURATION_MS = 4_000L

/** How far back a cut may be pulled to land on a break character. */
private const val MAX_CUT_LOOKBACK_CHARS = 3

private const val BREAK_CHARACTERS = "、，。！？；：,.!?;:…—·’”)）】》」』"
