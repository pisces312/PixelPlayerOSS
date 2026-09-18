# Release Checklist

PixelPlayerOSS releases are tagged from `main` — `main` is the release starting point, not the
only branch development happens on. Feature branches are merged into `main` before a release
is cut, otherwise the tag would miss their work. Every release candidate then passes the local
checks below and a basic device smoke test.

## Versioning

Version values live in `gradle.properties`:

```properties
APP_VERSION_NAME=0.1.0
APP_VERSION_CODE=1
```

For every public release:

1. Update `APP_VERSION_NAME`.
2. Increment `APP_VERSION_CODE`.
3. Move the relevant `CHANGELOG.md` entries from `Unreleased` to the release version.
4. Tag the release as `v<APP_VERSION_NAME>`, for example `v0.1.0`.

## Required Local Checks

Run these before tagging:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:compileDebugKotlin
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:lintDebug
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease -Ppixelplayer.enableAbiSplits=false -Ppixelplayer.disableReleaseSigning=true
```

For split APK artifacts, use:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease -Ppixelplayer.enableAbiSplits=true
```

## Signing

Release signing is driven by `KEY_*` environment variables and nothing else. No
`keystore.properties` file is read, so no plaintext password ever has to touch the disk:

```sh
export KEY_STORE_LOCATION=/absolute/path/to/release.jks
export KEY_STORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
```

`KEY_STORE_LOCATION` is resolved against the repository root when it is relative. If any of
the four is unset, or the keystore file does not exist, release builds come out **unsigned
rather than failing** — always confirm with
`apksigner verify --print-certs -v <apk>`. There is no fallback to a properties file, and no
keystore is ever committed.

CI does not sign anything in this fork: the build workflows in `.github/workflows/` are kept
disabled and have never run here, so they are not a reference for how signing works — see the
`CI` note under "签名与发布" in `AGENTS.md`. Releasing is a local build followed by a local
`gh release create`.

For F-Droid-compatible unsigned verification builds, pass `-Ppixelplayer.disableReleaseSigning=true` even when the environment variables are set.

## Device Smoke Test

Install the release candidate and verify:

1. First launch and setup complete.
2. Local library scan finds music.
3. Playback starts, pauses, resumes, skips, and survives backgrounding.
4. Full player opens and closes smoothly.
5. Widget controls still reach the playback service.
6. Navidrome and Jellyfin login screens open.
7. Backup export flow creates a file.

## Publishing

1. Merge the feature branch being released into `main`, then ensure `main` is clean and pushed.
2. Create a tag: `git tag v<APP_VERSION_NAME>`.
3. Push the tag: `git push origin v<APP_VERSION_NAME>`.
4. Create a GitHub release from the tag.
5. Attach APK artifacts and paste the changelog section.

## F-Droid Metadata

Before submitting a tagged release to F-Droid-compatible app stores:

1. Update `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. Verify the unsigned universal release build from [FDROID.md](FDROID.md).
3. Check `PRIVACY.md` still matches the optional network services present in the app.
4. Create source archives from git, not from the working tree, so ignored local artifacts are excluded.
