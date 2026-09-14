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
}
