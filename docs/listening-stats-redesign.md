# Listening stats redesign

## Goal

Move the home screen's recently played section to the top-level Stats tab and replace the current stats implementation with a new screen based on the former home listening-stats card.

The existing `StatsScreen.kt` remains in the tree for reference, but the `stats` route will render a new `ListeningStatsScreen.kt`.

## Screen structure

1. Recently played
   - Uses the existing `RecentlyPlayedSection`.
   - Shows the first 64 recent songs.
   - Tapping a song plays the recently played queue.
   - The trailing arrow opens the existing `recently_played` detail screen.
   - The detail screen keeps only Today, Week to Date, and Month to Date.

2. Listening stats overview
   - Uses one summary chosen automatically.
   - Selection order: Week to Date, Month to Date, Year to Date, All Time.
   - The first range with listening activity is used.
   - Displays total duration, total plays, average daily duration, and top track.
   - Includes the same compact timeline concept as the former home overview.

3. Rankings
   - Top songs are playable through `PlayerViewModel.playSongById`.
   - Top artists resolve to an artist id and open the artist detail screen.
   - Top albums resolve to an album id and open the album detail screen.
   - Missing ids disable navigation rather than crashing.

## Implementation notes

- Add an independent `homeOverview` flow to `StatsViewModel`; do not alter the legacy screen's main summary state.
- Reuse `PlaybackStatsRepository.loadSummary` and the existing summary model.
- Extract the recently played home-state collection into a reusable Compose state helper.
- Remove the recently played section from `HomeScreen` after moving it to the new screen.
- Add only English source strings; translations remain with the localization workflow.
