package com.lostf1sh.pixelplayeross.utils

import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.model.SyncedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the playback-position -> lyric-line mapping shared by the lyrics sheet and the car lyric
 * title feature, plus the line -> cue flattening only the latter needs.
 */
class LyricsTimelineUtilsTest {

    private val lines = listOf(
        SyncedLine(time = 1_000, line = "first"),
        SyncedLine(time = 4_000, line = "second"),
        SyncedLine(time = 7_000, line = "third")
    )

    /** A 5-character line, then a 21-character one over 6 s (three cues), then the last line. */
    private val cueLines = listOf(
        SyncedLine(time = 1_000, line = "short"),
        SyncedLine(time = 4_000, line = "abcdefghijklmnopqrstu"),
        SyncedLine(time = 10_000, line = "final")
    )

    private val trackDurationMs = 20_000L

    // --- resolveCurrentLineIndex (lyrics sheet) ---

    @Test
    fun resolveCurrentLineIndex_returnsMinusOneForEmptyTimeline() {
        assertEquals(-1, resolveCurrentLineIndex(emptyList(), position = 1_000))
    }

    @Test
    fun resolveCurrentLineIndex_returnsMinusOneBeforeTheFirstLine() {
        assertEquals(-1, resolveCurrentLineIndex(lines, position = 0))
        assertEquals(-1, resolveCurrentLineIndex(lines, position = 999))
    }

    @Test
    fun resolveCurrentLineIndex_mapsPositionToItsLine() {
        assertEquals(0, resolveCurrentLineIndex(lines, position = 1_000))
        assertEquals(0, resolveCurrentLineIndex(lines, position = 3_999))
        assertEquals(1, resolveCurrentLineIndex(lines, position = 4_000))
        assertEquals(2, resolveCurrentLineIndex(lines, position = 7_000))
    }

    @Test
    fun resolveCurrentLineIndex_keepsLastLineActiveToTheEndOfTheTrack() {
        // The final line has no successor, so it stays current instead of the lyrics going blank
        // for the remainder of the song.
        assertEquals(2, resolveCurrentLineIndex(lines, position = 999_999))
    }

    @Test
    fun resolveCurrentLineIndex_followsSeekBackwards() {
        assertEquals(2, resolveCurrentLineIndex(lines, position = 8_000))
        assertEquals(1, resolveCurrentLineIndex(lines, position = 5_000))
        assertEquals(0, resolveCurrentLineIndex(lines, position = 1_200))
    }

    @Test
    fun resolveCurrentLineIndex_keepsLineWhileItsLastWordIsStillPending() {
        val wordTimed = listOf(
            SyncedLine(
                time = 1_000,
                line = "held on",
                words = listOf(SyncedWord(time = 1_000, word = "held"), SyncedWord(time = 3_000, word = "on"))
            )
        )

        assertEquals(0, resolveCurrentLineIndex(wordTimed, position = 2_500))
    }

    // --- buildLyricCues ---

    @Test
    fun buildLyricCues_returnsNothingForAnEmptyTimeline() {
        assertEquals(emptyList<LyricCue>(), buildLyricCues(emptyList(), trackDurationMs, maxCharsPerCue = 10))
    }

    @Test
    fun buildLyricCues_publishesShortLinesWhole() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)

        // 5 chars needs no cutting at all, so the line is one cue starting on its own timestamp.
        assertEquals(LyricCue(sequence = 0, timeMs = 1_000L, text = "short"), cues.first())
    }

    @Test
    fun buildLyricCues_splitsALongLineIntoEvenSegments() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)
        val longLine = cues.filter { it.sequence in 1..3 }

        // 21 chars -> ceil(21/10) = 3 segments of 7, spread evenly over the line's own 6 s.
        assertEquals(listOf("abcdefg", "hijklmn", "opqrstu"), longLine.map { it.text })
        assertEquals(listOf(4_000L, 6_000L, 8_000L), longLine.map { it.timeMs })
    }

    @Test
    fun buildLyricCues_spacesSegmentsEvenlyWithinTheirLine() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)
        val longLine = cues.filter { it.sequence in 1..3 }

        // The car lyric title publishes every cue early by one constant, so these gaps — and only
        // these gaps — set the rhythm on screen; a per-segment offset would show up right here.
        assertEquals(
            listOf(2_000L, 2_000L),
            longLine.zipWithNext { earlier, later -> later.timeMs - earlier.timeMs }
        )
    }

    @Test
    fun buildLyricCues_spreadsTheRemainderOverTheLeadingSegments() {
        val cues = buildLyricCues(
            listOf(SyncedLine(time = 0, line = "abcdefghijklmnopqrs")),
            trackDurationMs = 4_000,
            maxCharsPerCue = 10
        )

        // 19 chars -> 2 segments, 10 then 9: never more than one character apart, never over the cap.
        assertEquals(listOf("abcdefghij", "klmnopqrs"), cues.map { it.text })
    }

    @Test
    fun buildLyricCues_breaksOnWhitespaceInsteadOfMidWord() {
        val cues = buildLyricCues(
            listOf(SyncedLine(time = 0, line = "hello world foo")),
            trackDurationMs = 4_000,
            maxCharsPerCue = 10
        )

        // The even cut would land inside "world"; the break character one position back wins.
        assertEquals(listOf("hello", "world foo"), cues.map { it.text })
    }

    @Test
    fun buildLyricCues_usesTheTrackDurationForTheLastLine() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)

        // The last line has no successor to borrow an end from: 20 s track - 10 s start = 10 s.
        assertEquals(LyricCue(sequence = 4, timeMs = 10_000L, text = "final"), cues.last())
    }

    @Test
    fun buildLyricCues_fallsBackWhenTheTrackDurationIsUnset() {
        // `C.TIME_UNSET` is Long.MIN_VALUE, which has to be rejected by comparison: subtracting it
        // first would overflow into a positive span and stretch the line across the whole track.
        val cues = buildLyricCues(
            listOf(SyncedLine(time = 1_000, line = "abcdefghijklmno")),
            trackDurationMs = Long.MIN_VALUE,
            maxCharsPerCue = 10
        )

        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].timeMs)
        assertEquals(4_000L, cues[1].timeMs - cues[0].timeMs)
    }

    @Test
    fun buildLyricCues_keepsBlankLinesAsEmptyCues() {
        val cues = buildLyricCues(
            listOf(
                SyncedLine(time = 1_000, line = "sung"),
                SyncedLine(time = 4_000, line = "   "),
                SyncedLine(time = 7_000, line = "again")
            ),
            trackDurationMs = 10_000,
            maxCharsPerCue = 10
        )

        // An instrumental marker still takes its turn: publishing an empty title is what restores
        // the real track name for that stretch, rather than the previous line lingering.
        assertEquals(listOf("sung", "", "again"), cues.map { it.text })
    }

    @Test
    fun buildLyricCues_numbersCuesInTimelineOrder() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)

        // The sequence is the publisher's de-duplication key, so it has to identify one cue exactly.
        assertEquals(listOf(0, 1, 2, 3, 4), cues.map { it.sequence })
    }

    @Test
    fun buildLyricCues_ordersCuesThatPerWordTimingPushedPastTheNextLine() {
        val cues = buildLyricCues(
            listOf(
                SyncedLine(
                    time = 1_000,
                    line = "abcdefghijklmnopqrstu",
                    // A last word far past the next line's timestamp stretches the line's own end.
                    words = listOf(SyncedWord(time = 6_000, word = "u"))
                ),
                SyncedLine(time = 2_000, line = "next")
            ),
            trackDurationMs = 20_000,
            maxCharsPerCue = 10
        )

        // Out-of-order timestamps are possible with per-word timing, and resolveCueIndex relies on
        // the list being sorted.
        assertEquals(cues.map { it.timeMs }.sorted(), cues.map { it.timeMs })
    }

    // --- resolveCueIndex / nextCueTimeMs (car lyric title) ---

    @Test
    fun resolveCueIndex_returnsMinusOneForAnEmptyTimeline() {
        assertEquals(-1, resolveCueIndex(emptyList(), position = 1_000))
    }

    @Test
    fun resolveCueIndex_returnsMinusOneDuringTheIntro() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)

        // Nothing to show before the first line, which is what keeps the real title on screen.
        assertEquals(-1, resolveCueIndex(cues, position = 0))
        assertEquals(-1, resolveCueIndex(cues, position = 999))
    }

    @Test
    fun resolveCueIndex_switchesExactlyOnTheCueTimestamp() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)

        assertEquals(0, resolveCueIndex(cues, position = 1_000))
        assertEquals(0, resolveCueIndex(cues, position = 3_999))
        assertEquals(1, resolveCueIndex(cues, position = 4_000))
        assertEquals(1, resolveCueIndex(cues, position = 5_999))
        assertEquals(3, resolveCueIndex(cues, position = 9_999))
        assertEquals(4, resolveCueIndex(cues, position = 10_000))
    }

    @Test
    fun resolveCueIndex_keepsTheLastCueToTheEndOfTheTrack() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)

        assertEquals(4, resolveCueIndex(cues, position = 999_999))
    }

    @Test
    fun resolveCueIndex_followsSeekBackwards() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)

        assertEquals(3, resolveCueIndex(cues, position = 8_000))
        assertEquals(1, resolveCueIndex(cues, position = 5_000))
        assertEquals(0, resolveCueIndex(cues, position = 1_200))
    }

    @Test
    fun nextCueTimeMs_returnsNullForAnEmptyTimeline() {
        assertEquals(null, nextCueTimeMs(emptyList(), index = 0))
    }

    @Test
    fun nextCueTimeMs_beforeTheFirstCueTargetsIt() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)

        // Intro: the wake-up should land exactly on the first cue's timestamp.
        assertEquals(1_000L, nextCueTimeMs(cues, index = -1))
    }

    @Test
    fun nextCueTimeMs_targetsTheFollowingCue() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)

        // Mid-line, the next wake-up is the next *segment* of the same line, not the next line.
        assertEquals(6_000L, nextCueTimeMs(cues, index = 1))
        assertEquals(8_000L, nextCueTimeMs(cues, index = 2))
        assertEquals(10_000L, nextCueTimeMs(cues, index = 3))
    }

    @Test
    fun nextCueTimeMs_onTheLastCueReturnsNull() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)

        assertEquals(null, nextCueTimeMs(cues, index = 4))
        assertEquals(null, nextCueTimeMs(cues, index = 99))
    }

    @Test
    fun nextCueTimeMs_neverLooksBackwards() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxCharsPerCue = 10)

        assertTrue(
            (0 until cues.lastIndex).all { index ->
                nextCueTimeMs(cues, index)!! > cues[index].timeMs
            }
        )
    }
}
