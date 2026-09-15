package com.lostf1sh.pixelplayeross.utils

import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.model.SyncedWord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the playback-position -> lyric-line mapping shared by the lyrics sheet and the car lyric
 * title feature.
 */
class LyricsTimelineUtilsTest {

    private val lines = listOf(
        SyncedLine(time = 1_000, line = "first"),
        SyncedLine(time = 4_000, line = "second"),
        SyncedLine(time = 7_000, line = "third")
    )

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

    @Test
    fun nextLyricBoundaryMs_returnsNullForEmptyTimeline() {
        assertEquals(null, nextLyricBoundaryMs(emptyList(), index = 0))
    }

    @Test
    fun nextLyricBoundaryMs_beforeTheFirstLineTargetsItsStart() {
        // Intro: the next wake-up should land exactly on the first line's timestamp.
        assertEquals(1_000L, nextLyricBoundaryMs(lines, index = -1))
    }

    @Test
    fun nextLyricBoundaryMs_targetsTheNextLineStart() {
        assertEquals(4_000L, nextLyricBoundaryMs(lines, index = 0))
        assertEquals(7_000L, nextLyricBoundaryMs(lines, index = 1))
    }

    @Test
    fun nextLyricBoundaryMs_onTheLastLineReturnsNull() {
        assertEquals(null, nextLyricBoundaryMs(lines, index = 2))
        assertEquals(null, nextLyricBoundaryMs(lines, index = 99))
    }

    @Test
    fun nextLyricBoundaryMs_respectsPerWordTiming() {
        // A word-timed line whose last word starts after the next line's timestamp keeps the
        // boundary at that word instead of the next line's start.
        val wordTimed = listOf(
            SyncedLine(
                time = 1_000,
                line = "held on",
                words = listOf(SyncedWord(time = 1_000, word = "held"), SyncedWord(time = 6_000, word = "on"))
            ),
            SyncedLine(time = 4_000, line = "second")
        )

        assertEquals(6_001L, nextLyricBoundaryMs(wordTimed, index = 0))
    }
}
