# Changelog

All notable changes to PixelPlayerOSS will be documented in this file.

## [0.4.1-pisces.1] - 2026-09-13

Second fork release. The AI side gets a second entry point that reads the moment, and a mix now
remembers the prompt it was generated from.

### Added
- Serendipity (`不期而遇` in Chinese): a zero-input AI playlist built from right now. The AI banner carries its own magic-wand pill next to the describe field; tapping it opens a sheet showing what it could read as chips (weekday and time of day, city weather, today's step count), the prompt it composed on the device, and two ways to change that prompt: Another line reshuffles the local wording without re-reading anything, and Let the AI rephrase it spends one small extra request, falling back to the local wording if the provider is unreachable. The prompt stays editable before generating, and a signal that could not be read is simply absent instead of being faked.
- A weather source setting with three options: device location (approximate location, permission requested on first use), a chosen city or district, or off. The chosen-city and off options never request the location permission and never read the phone's position. Turning coordinates into a place name is fully offline against a bundled gazetteer of roughly 8,900 places (every Chinese province, city and district, plus world cities above 100,000 residents), so only the forecast request itself goes online, and that endpoint (Open-Meteo) needs no API key.
- Step count is read on demand, only while the moment line is being composed, instead of listening to the step sensor in the background.
- AI playlists remember the prompt they were generated from and show it under the title on the playlist screen. Manually built playlists have no prompt and are unaffected.
- A rating segment in the player toggle row: tapping the star expands a five-star row so a song can be rated without leaving the player.
- A Year row in the song info sheet that opens that year's detail screen.
- Playing the whole top-songs list as a queue straight from listening stats.
- A Statistics section in settings with No limit on stats rankings, so song, artist and album rankings show everything instead of stopping at 100.
- The recent-mixes row became a compact card row with its own screen listing every generated mix, and a mix is named after the idea word that was tapped.

### Changed
- The AI banner's Start pill is renamed Describe (`说一句` in Chinese) and both pills moved onto their own line, so the banner title no longer has to wrap on a 360dp screen.
- The AI entry and Serendipity surfaces are localized in Simplified Chinese, including the idea chips, the sampling controls and the new weather settings.
- The song info sheet can also be opened by tapping the title in the full player.

### Fixed
- Lists no longer end underneath the mini player: the missing bottom space was added on the duplicate-songs screen, the year detail song list and the three stats ranking screens.

## [0.4.0-pisces.1] - 2026-09-11

The first stable release of the personal fork, rolling up the work carried on the fork branch
along with everything that landed on `main` afterwards.

### Added
- AI playlist generation: describe a mood or a moment in plain language and the pick is made from your own library. A dedicated AI settings category works with any OpenAI-compatible endpoint and ships three providers (Xiaomi MiMo, Volcano Engine Ark, and a custom provider with a user-supplied base URL), storing keys per provider and letting you set the API key and model id with a one-tap model list fetch and picker; Xiaomi MiMo Token Plan keys (`tp-`) and pay-as-you-go keys (`sk-`) resolve to their own endpoints automatically. The home AI Mix card opens the prompt sheet, built-in idea chips cover the common moods, and the library slice can be sampled from most-played tracks (stable results, so asking twice costs one request) or randomly (a fresh shuffle that reaches songs you never play) at 15, 25 or 40 songs. Results can be played immediately, trimmed song by song, or saved as a playlist named `yyyyMMdd-HHmm`, and a recently generated row lists the recent mixes.
- Third-party import for Poweramp `.poweramp-backup` files, behind a source picker built to accept more sources later. The parsed backup is previewed with song, playlist and matched counts, the match rate, and examples of unmatched songs before you commit. Matching combines title, artist, album and duration with file paths, and normalises external SD card volume-serial paths (`9C33-6BBD/...`) and multiple storage roots. Playlists, playback history, play counts, and favourites and ratings import independently, with an optional rating threshold that marks highly rated songs as favourites, and a result summary is reported per category.
- Five-star ratings stored as a database field instead of embedded file tags, so cloud tracks can be rated too. Un-favouriting a song keeps its rating, batch metadata edits no longer wipe unrelated tags, and an interactive star row was added to the song info sheet.
- A Years library tab bucketing songs by release year, with a year detail screen supporting play, shuffle and sort.
- Deletion protection, a switch that turns song deletion off by default so a mis-tap cannot destroy files.
- In-app diagnostic logs with an adjustable level (Verbose to Error, WARN by default in release builds), one-tap export and share backed by an in-memory ring buffer with a rolling file and logcat fallback, diagnostic logs attached to crash reports, and oversized logs truncated so copying or sharing cannot hang.
- AI provider configuration in backups, and a dedicated song-matching resolver for playlist restore.
- The changelog sheet now renders the repository `CHANGELOG.md` at runtime instead of a hardcoded list, so it tracks the file and stays current without hand-editing in two languages per release. Section titles stay localized, and entries are shown verbatim.

### Changed
- Listening stats were promoted from a home card to a top-level bottom-bar tab and rebuilt: recently played grouped by day with repeated songs merged and counted, absolute period browsing (today, week, month, year, all) with previous, next and reset to the current period, an overview card for listening duration, plays, songs and artists, and full top-song, top-artist and top-album rankings with a show-all view and sorting by plays or duration.
- Playback events now carry a play-count weight, opening the stats screen flushes the in-progress listening session so the song currently playing appears immediately, and zero-duration events imported from Poweramp are expanded by play count so imported history is not lost.
- Media3 upgraded to 1.11.0.
- Debug and release builds can coexist: the debug build has its own application id suffix, its own name, and a red launcher icon.
- Only `arm64-v8a` is built, with a uniform artifact name of `pixelplayeross-<abi>-<version>-<buildtype>.apk`.
- Release signing credentials fall back to the `KEY_*` environment variables when no keystore properties file is present, so passwords no longer need to touch the disk.
- Ten design documents covering the AI port, listening stats, Poweramp history alignment, playlist restore, audio output options, and the Media3 upgrade path.

### Fixed
- Playlist restore failing on release builds: the R8 keep rules did not cover `PendingSongRef` in `data.backup.restore`, so obfuscation renamed its fields and every string in the backup payload arrived null. A payload this build does not recognise now degrades to no metadata, which still accepts a direct-ID hit, instead of throwing.
- Favourites backups failing because of a duplicate Gson field name.
- The Media3 1.11.0 `onConnect` command-set regression that made playback state jump back after connecting.
- Jellyfin playlists no longer go missing on Jellyfin 10.10 and newer, where playlists can hold mixed content and audio playlists are often reported without a media type.
- Folder ancestry is compared using the path's own separator rather than the host `File.separatorChar`, so the result no longer depends on the operating system.
- Play counts are preserved when exporting and restoring playback history.

### Security
- AI request logging redacts `key`, `token` and `secret` URL parameters before anything is written to disk, and API keys never enter a log entry.

## [0.3.0] - 2026-08-15

### Added
- Optional ListenBrainz scrobbling, disabled by default. Connect a ListenBrainz account with a user token from the Accounts screen; listens that reach the ListenBrainz threshold (4 minutes or half the track, whichever is lower) queue offline and submit with retry, with per-source toggles for local files, Subsonic, and Jellyfin playback. Now-playing status is reported while scrobbling is enabled, and disconnecting deletes any queued listens. An optional custom server URL scrobbles to self-hosted ListenBrainz-compatible servers such as Maloja instead of listenbrainz.org.
- Offline downloads for Navidrome/Subsonic and Jellyfin tracks, with per-track progress, retry/removal actions, album downloads, app-private storage, and transparent local playback when a download is available, plus a dedicated download management screen.
- On-demand MusicBrainz enrichment with ranked result selection and local recording, release, and artist identifiers. Existing metadata is preserved except for missing or unknown values.
- Translations for twelve languages with an in-app language picker, including Turkish and Simplified Chinese (`zh-rCN`). The selected language now also applies to the login and external player screens.
- Audio bookmarks for saving your place inside long tracks such as mixes, audiobooks, and DJ sets.
- Offline natural-language playlist creation: describe the playlist you want and it is built locally, with no network calls.
- Passphrase-protected backups.
- Search in Settings, so a toggle can be found without digging through categories.
- Tempo-matched crossfades and smoother play/pause transitions.
- Opt-in performance recorder for debugging lag reports.

### Changed
- Listening-stats hour labels follow the system 12/24-hour clock format.
- Cached album art is size-capped and artwork extraction is skipped during library scans, reducing memory use and scan time.
- Alpha releases are skipped for docs-only and CI-only changes; the standalone phone APK workflows were dropped in favour of alpha releases.

### Fixed
- Cloud artwork now reaches media notifications, the lock screen, and external media controllers.
- Offline download cancellation races that could leave partial files or stuck progress.
- Restored queues no longer keep dead local proxy links.
- Cloud streams that cannot be resolved now surface a clear error instead of failing silently.
- Synced embedded lyrics are preferred, and the lyrics API rate limiter no longer throttles valid requests.
- Shuffle changes stay in sync with the media session.
- Folder filters stay in sync with the library, and comma-separated genre tags are split correctly.
- The song info sheet no longer overflows on small screens.
- Bookmark buttons that were still hardcoded English are localized.
- The themed launcher icon is restored.

### Security
- Credentials are redacted from debug network logs.

### Removed
- Android Auto media-library browsing and discovery. Standard MediaSession playback controls for notifications, lock screen, Bluetooth devices, and other system surfaces remain available.

## [0.2.0] - 2026-07-17

### Added
- Navidrome library selector for servers that expose more than one music library.
- The Artists tab and cloud albums are grouped by album artist.
- Source code and F-Droid links in the About screen, plus GitHub Sponsors metadata.

### Changed
- Material 3 Expressive pass: motion scheme, wavy progress indicators, and shape morphs.
- New app icon and redesigned header.
- Leaner R8 rules and regenerated baseline profiles.
- Coroutines, Flow, and Compose hygiene pass, plus dead-code and deprecated-API cleanup.
- Dependency bumps across Material 3, Compose, OkHttp, core-ktx, and lifecycle.

### Fixed
- Local server connections on Android 17.
- Plain HTTP is allowed for local Navidrome and Jellyfin servers, including Tailscale and VPN hosts.
- GitLab mirror repository guard after the organisation move.

### Security
- User-installed CAs are trusted so self-signed cloud servers work without disabling verification.
- State-changing media session commands are restricted to trusted clients, and artwork sharing stays private behind explicit URI grants.

## [0.1.0] - 2026-06-09

### Initial release
- First public FOSS release of PixelPlayerOSS, an OSS-focused Android music player.
- Includes local music playback, playlists, favorites, lyrics, listening stats, dynamic Material 3 theming, widgets, and backup/restore.
- Keeps self-hosted library support for Navidrome/Subsonic and Jellyfin, plus optional LRCLIB lyrics and Deezer artist artwork lookups.

### Removed for FOSS
- Removed non-FOSS and Google Play oriented integrations: Telegram, NetEase, QQ Music, Google Drive, Gemini, Cast, Wear OS, Play Store billing, Firebase, Crashlytics, and Google Play Services runtime dependencies.
- Removed public scrobbling integrations such as Last.fm and ListenBrainz; self-hosted Navidrome/Subsonic playback reporting remains scoped to the user's own server.
- Removed bundled translations and the in-app language selector for the first FOSS release; the initial source release ships with English resources only.
- Removed release paths that depended on local/private signing artifacts, dummy signing values, or app-store-only assumptions.

### Release readiness
- Added F-Droid metadata, Fastlane store metadata, dependency/license documentation, privacy notes, security notes, and contributor guidance.
- Release builds now stay unsigned when local signing keys are absent, and `pixelplayer.disableReleaseSigning=true` forces unsigned verification builds even on a maintainer machine.
- Documented third-party asset and dependency licenses, including native/binary Maven artifacts and JitPack source trails.

### Security and privacy
- The loopback cloud-stream proxy now requires a per-session token so other apps on the device cannot stream the user's cloud library by guessing local proxy URLs.
- Backup restore now ignores preference keys owned by dedicated module handlers, preventing crafted global-settings payloads from bypassing module validation.
- Release logging is tightened so HTTP request headers and remaining raw Android logs do not bypass the Timber release filter.

### App polish included in this FOSS release
- Added smart playlist persistence, duplicate-track scanning, playback speed control, clearer playback/sync failure messages, and retry actions on album/artist detail failures.
- Improved accessibility for toggle states and song row actions.
