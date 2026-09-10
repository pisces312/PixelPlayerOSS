package com.lostf1sh.pixelplayeross.data.stats

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.model.ArtistRef
import com.lostf1sh.pixelplayeross.data.model.Song
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import io.mockk.every
import io.mockk.mockk
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PlaybackStatsRepositoryTest {

    @Test
    fun `loadSummary excludes event that only touches the start boundary`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val now = LocalDate.of(2026, 4, 10)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val boundaryTouchingEvent = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = now,
            durationMs = 10_000L,
            startTimestamp = now - 10_000L,
            endTimestamp = now
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1")),
            allEvents = listOf(boundaryTouchingEvent),
            nowMillis = now
        )

        assertThat(summary.totalDurationMs).isEqualTo(0L)
        assertThat(summary.totalPlayCount).isEqualTo(0)
    }

    @Test
    fun `loadSummary preserves playback longer than track duration`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(10, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val listenedMs = TimeUnit.MINUTES.toMillis(15)
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + listenedMs,
            durationMs = listenedMs,
            startTimestamp = start,
            endTimestamp = start + listenedMs
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1", durationMs = TimeUnit.MINUTES.toMillis(3))),
            allEvents = listOf(event),
            nowMillis = start + listenedMs + 1_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(listenedMs)
        assertThat(summary.totalPlayCount).isEqualTo(1)
        assertThat(summary.songs.single().totalDurationMs).isEqualTo(listenedMs)
    }

    @Test
    fun `loadSummary does not count short gaps between spans as listened time`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(12, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val firstDurationMs = 10_000L
        val secondDurationMs = 10_000L
        val gapMs = 1_000L
        val events = listOf(
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = start + firstDurationMs,
                durationMs = firstDurationMs,
                startTimestamp = start,
                endTimestamp = start + firstDurationMs
            ),
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-2",
                timestamp = start + firstDurationMs + gapMs + secondDurationMs,
                durationMs = secondDurationMs,
                startTimestamp = start + firstDurationMs + gapMs,
                endTimestamp = start + firstDurationMs + gapMs + secondDurationMs
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1"), song("song-2")),
            allEvents = events,
            nowMillis = start + firstDurationMs + gapMs + secondDurationMs + 1_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(firstDurationMs + secondDurationMs)
        assertThat(summary.totalPlayCount).isEqualTo(2)
    }

    @Test
    fun `buildSummaryFromEvents uses event spans without filesystem persistence`() = runTest {
        val repository = createRepository()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        val events = listOf(
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = start + durationMs,
                durationMs = durationMs,
                startTimestamp = start,
                endTimestamp = start + durationMs
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1")),
            allEvents = events,
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(durationMs)
        assertThat(summary.uniqueSongs).isEqualTo(1)
    }

    @Test
    fun `loadSummary separates multi artist playback in top artists`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(14, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMs = 60_000L
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + durationMs,
            durationMs = durationMs,
            startTimestamp = start,
            endTimestamp = start + durationMs
        )
        val collaboration = song(
            songId = "song-1",
            artist = "Artist A",
            artists = listOf(
                ArtistRef(id = 1L, name = "Artist A", isPrimary = true),
                ArtistRef(id = 2L, name = "Artist B", isPrimary = false)
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(collaboration),
            allEvents = listOf(event),
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.topArtists.map { it.artist })
            .containsExactly("Artist A", "Artist B")
            .inOrder()
        summary.topArtists.forEach { artist ->
            assertThat(artist.totalDurationMs).isEqualTo(durationMs)
            assertThat(artist.playCount).isEqualTo(1)
            assertThat(artist.uniqueSongs).isEqualTo(1)
        }
    }

    @Test
    fun `loadSummary counts separated artists in genre uniqueness`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(15, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + durationMs,
            durationMs = durationMs,
            startTimestamp = start,
            endTimestamp = start + durationMs
        )
        val collaboration = song(
            songId = "song-1",
            artist = "Artist A",
            artists = listOf(
                ArtistRef(id = 1L, name = "Artist A", isPrimary = true),
                ArtistRef(id = 2L, name = "Artist B", isPrimary = false)
            ),
            genre = "Pop"
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(collaboration),
            allEvents = listOf(event),
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.topGenres.single().uniqueArtists).isEqualTo(2)
    }

    @Test
    fun `loadSummary aggregates a weighted event by its playCount`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(16, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        // 第三方导入的一次事件代表累计 5 次播放。
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + durationMs,
            durationMs = durationMs,
            startTimestamp = start,
            endTimestamp = start + durationMs,
            playCount = 5
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1")),
            allEvents = listOf(event),
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.totalPlayCount).isEqualTo(5)
        assertThat(summary.songs.single().playCount).isEqualTo(5)
    }

    @Test
    fun `playCount zero falls back to a weight of one`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(17, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        // Gson 对旧版 JSON 缺失字段可能走 Unsafe 分配，把 playCount 落成 0。
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + durationMs,
            durationMs = durationMs,
            startTimestamp = start,
            endTimestamp = start + durationMs,
            playCount = 0
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1")),
            allEvents = listOf(event),
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.totalPlayCount).isEqualTo(1)
    }

    @Test
    fun `all time range covers events spread across several days`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val nowMillis = LocalDate.of(2026, 4, 10)
            .atTime(22, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val durationMs = TimeUnit.MINUTES.toMillis(4)
        val events = listOf(0L, 2L, 5L, 10L).mapIndexed { index, daysAgo ->
            val end = nowMillis - daysAgo * dayMs
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-$index",
                timestamp = end,
                durationMs = durationMs,
                startTimestamp = end - durationMs,
                endTimestamp = end
            )
        }

        val summary = repository.buildSummaryFromEvents(
            period = StatsPeriod(StatsTimeRange.ALL),
            songs = events.indices.map { song("song-$it") },
            nowMillis = nowMillis,
            allEvents = events,
            zoneId = zoneId
        )

        assertThat(summary.uniqueSongs).isEqualTo(4)
        assertThat(summary.totalPlayCount).isEqualTo(4)
        assertThat(summary.totalDurationMs).isEqualTo(durationMs * 4)
    }

    @Test
    fun `imported event with zero duration and playCount expands by song duration times weight`() = runTest {
        // Poweramp 导入事件：durationMs = 0, playCount = N → 按 N × 曲长回溯
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val songDuration = 4 * 60 * 1000L // 4 分钟
        val playedAt = LocalDate.of(2026, 4, 10)
            .atTime(20, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = playedAt,
            durationMs = 0L,
            startTimestamp = playedAt,
            endTimestamp = playedAt,
            playCount = 3
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.ALL,
            songs = listOf(song("song-1", durationMs = songDuration)),
            allEvents = listOf(event),
            nowMillis = playedAt + 1_000L,
            zoneId = zoneId
        )

        assertThat(summary.totalPlayCount).isEqualTo(3)
        assertThat(summary.totalDurationMs).isEqualTo(songDuration * 3)
        assertThat(summary.uniqueSongs).isEqualTo(1)
    }

    @Test
    fun `expansion happens before resolveBounds so ALL range includes back-projected duration`() = runTest {
        // 验证展开先于边界计算：played_at 距今很近，但 N×曲长 会回溯到非常早的时间，
        // ALL 范围应该把回溯出的区间整个包住，而不是只取 played_at 为起点。
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val songDuration = 5 * 60 * 1000L // 5 分钟
        val playCount = 100
        val playedAt = LocalDate.of(2026, 4, 10)
            .atTime(20, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = playedAt,
            durationMs = 0L,
            startTimestamp = playedAt,
            endTimestamp = playedAt,
            playCount = playCount
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.ALL,
            songs = listOf(song("song-1", durationMs = songDuration)),
            allEvents = listOf(event),
            nowMillis = playedAt + 1_000L,
            zoneId = zoneId
        )

        // 100 × 5 分钟 = 500 分钟 ≈ 8.3 小时的回溯区间；ALL 范围必须完整计入
        assertThat(summary.totalPlayCount).isEqualTo(playCount)
        assertThat(summary.totalDurationMs).isEqualTo(songDuration * playCount)
        assertThat(summary.startTimestamp).isLessThan(playedAt - songDuration * playCount / 2)
    }

    @Test
    fun `mergeImportedEvents keeps the entry with higher playCount on same dedupe key`() = runTest {
        // 重新导入 Poweramp 备份时，旧的低权重事件应该被带更高次数的事件顶掉
        // （两条事件的 songId + start + end + duration 完全相同）。
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val playedAt = LocalDate.of(2026, 4, 10)
            .atTime(20, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        fun event(count: Int) = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = playedAt,
            durationMs = 0L,
            startTimestamp = playedAt,
            endTimestamp = playedAt,
            playCount = count
        )

        // 旧事件已存在（base），新导入同键但次数更高（incoming）
        val merged = repository.mergeImportedEvents(
            base = listOf(event(1)),
            incoming = listOf(event(15))
        )

        assertThat(merged).hasSize(1)
        assertThat(merged.single().playCount).isEqualTo(15)
    }

    @Test
    fun `mergeImportedEvents preserves distinct songs and stays unbounded`() = runTest {
        // 不同歌（不同去重键）都保留；且不做条数裁剪（无上限）。
        val repository = createRepository()
        val now = LocalDate.of(2026, 4, 10)
            .atTime(20, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val incoming = (1..50).map { i ->
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-$i",
                timestamp = now + i,
                durationMs = 0L,
                startTimestamp = now + i,
                endTimestamp = now + i,
                playCount = i
            )
        }

        val merged = repository.mergeImportedEvents(base = emptyList(), incoming = incoming)

        assertThat(merged).hasSize(50)
    }

    private fun createRepository(): PlaybackStatsRepository {
        val uniqueDir = createTempDirectory(
            "playback-stats-test-${Instant.now().toEpochMilli()}-"
        ).toFile()
        val testContext = mockk<android.content.Context>(relaxed = true)
        every { testContext.filesDir } returns uniqueDir
        return PlaybackStatsRepository(testContext)
    }

    private fun song(
        songId: String,
        durationMs: Long = 5 * 60 * 1000L,
        artist: String = "Artist",
        artists: List<ArtistRef> = emptyList(),
        genre: String? = null
    ): Song = Song(
        id = songId,
        title = "Song $songId",
        artist = artist,
        artistId = 1L,
        artists = artists,
        album = "Album",
        albumId = 1L,
        path = "/music/$songId.mp3",
        contentUriString = "content://media/external/audio/media/$songId",
        albumArtUriString = null,
        duration = durationMs,
        genre = genre,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        sampleRate = 44_100
    )
}
