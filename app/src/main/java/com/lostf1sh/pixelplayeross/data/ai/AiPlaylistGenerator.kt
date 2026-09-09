package com.lostf1sh.pixelplayeross.data.ai

import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.preferences.AiPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Turns a natural-language description into real songs from the library.
 *
 * The model only ever sees song titles and artist names, and it answers with a 'Title - Artist'
 * list. Those lines are then matched back against the local library, so a song that does not
 * exist on the device can never end up in the result: the model suggests, the library decides.
 */
@Singleton
class AiPlaylistGenerator
@Inject
constructor(
    private val handler: AiHandler,
    private val preferences: AiPreferencesRepository,
    private val musicRepository: MusicRepository
) {

    private data class Suggestion(val title: String, val artist: String)

    suspend fun generate(description: String, maxLength: Int = DEFAULT_MAX_LENGTH): List<Song> =
            withContext(Dispatchers.Default) {
                val songs = musicRepository.getAllSongsOnce()
                if (songs.isEmpty()) return@withContext emptyList()

                val sampleSize = preferences.getLibrarySampleSize().first()
                val raw = handler.generate(description, librarySample(songs, sampleSize))
                resolve(parse(raw), songs, maxLength)
            }

    /**
     * A random subset of the library sent as context.
     *
     * Random rather than sorted: a fixed prefix (by title, by date added, ...) would permanently
     * exclude everything past the cut-off, and those songs could never be suggested. Reshuffling
     * on every request means every song stays reachable and two runs over the same description
     * surface different corners of the library.
     */
    private fun librarySample(songs: List<Song>, size: Int): String =
            songs.shuffled().take(size).joinToString("\n") { "${it.title} - ${it.displayArtist}" }

    /** Reads 'Title - Artist' lines, tolerating numbering, bullets and code fences. */
    private fun parse(raw: String): List<Suggestion> =
            raw.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("```") }
                    .mapNotNull { line ->
                        val text =
                                line.removePrefix("- ")
                                        .removePrefix("* ")
                                        .removePrefix("• ")
                                        .replace(LEADING_NUMBER, "")
                                        .trim()
                                        .trim('"', '\'', '`')
                        if (text.isBlank()) return@mapNotNull null
                        val parts = text.split(SEPARATOR, limit = 2)
                        val title = parts[0].trim()
                        if (title.isBlank()) return@mapNotNull null
                        Suggestion(title, parts.getOrNull(1)?.trim().orEmpty())
                    }
                    .toList()

    private fun resolve(
        suggestions: List<Suggestion>,
        songs: List<Song>,
        maxLength: Int
    ): List<Song> {
        val picked = LinkedHashMap<String, Song>()
        for (suggestion in suggestions) {
            val match = bestMatch(suggestion, songs) ?: continue
            picked.putIfAbsent(match.id, match)
            if (picked.size >= maxLength) break
        }
        return picked.values.toList()
    }

    private fun bestMatch(suggestion: Suggestion, songs: List<Song>): Song? {
        val wantedTitle = normalize(suggestion.title)
        val wantedArtist = normalize(suggestion.artist)
        var best: Song? = null
        var bestScore = 0f
        for (song in songs) {
            val titleScore = similarity(wantedTitle, normalize(song.title))
            if (titleScore < TITLE_THRESHOLD) continue
            val artistScore =
                    if (wantedArtist.isBlank()) 1f
                    else similarity(wantedArtist, normalize(song.displayArtist))
            val score = titleScore + ARTIST_WEIGHT * artistScore
            if (score > bestScore) {
                bestScore = score
                best = song
            }
        }
        return best
    }

    private fun normalize(value: String): String =
            PUNCTUATION
                    .replace(
                            PARENTHESES.replace(value.lowercase(Locale.getDefault()), " "),
                            " "
                    )
                    .trim()
                    .replace(WHITESPACE, " ")

    /** Bigrams keep this usable for scripts without word breaks (Chinese titles). */
    private fun similarity(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        if (a == b) return 1f
        if (a in b || b in a) return CONTAINMENT_SCORE
        val left = bigrams(a)
        val right = bigrams(b)
        val shared = left.intersect(right).size
        return (2f * shared) / (left.size + right.size).coerceAtLeast(1)
    }

    private fun bigrams(value: String): Set<String> =
            if (value.length < 2) setOf(value)
            else (0..value.length - 2).mapTo(HashSet()) { value.substring(it, it + 2) }

    private companion object {
        const val DEFAULT_MAX_LENGTH = 25

        const val TITLE_THRESHOLD = 0.5f
        const val ARTIST_WEIGHT = 0.25f
        const val CONTAINMENT_SCORE = 0.9f

        val LEADING_NUMBER = Regex("^\\d+[.、)]\\s*")
        val SEPARATOR = Regex("\\s+[-–—:]\\s+")
        val PARENTHESES = Regex("\\(.*?\\)|\\[.*?]")
        val PUNCTUATION = Regex("[^\\p{L}\\p{N}\\s]+")
        val WHITESPACE = Regex("\\s+")
    }
}
