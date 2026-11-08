# Changelog

All notable changes to Fathom are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
semantic versioning.

## [Unreleased]

### Added

- A native teal.fm dashboard for iOS and Android, with Now, Stats, History and
  Profile screens.
- A bar-chart app icon on both platforms: default, dark and tinted
  asset-catalog images on iOS, and adaptive foreground, background and
  monochrome layers on Android.
- A launch screen on both platforms carrying the bar mark without its tile,
  on the same deep background as the app.
- Kotlin Multiplatform core holding the `fm.teal.*` record models, tolerant
  per-record JSON decoding, XRPC query construction, and all listening
  statistics.
- Reads from the account's PDS rather than a hosted AppView, because the
  `fm.teal.stats.*` routes were not reachable on any public host.
- Both the production `fm.teal.*` and the pre-migration `fm.teal.alpha.*`
  namespaces are read, so an account mid-migration keeps its older history.
- Now playing from the `fm.teal.actor.status` record, including expiry
  handling and the empty-track records trackers leave behind.
- Reads the whole play history, paging past the first thousand plays, so "All
  time" is a total rather than the newest slice. A guard stops at 50,000 plays,
  and a read that ends early is labelled partial rather than passed off as
  complete.
- Changing the period recomputes the figures from the plays already in memory,
  so switching between 7 days, 30 days and all time does not re-read the repo.
- Progressive reads: the dashboard opens on the newest page and its figures grow
  as the rest of the history arrives, because a full history is minutes of
  requests on a real data server.
- The read shows how many plays it has reached, so a long history looks like
  progress rather than a stalled spinner. The banner can be dismissed without
  stopping the read, and it returns for the next one.
- A history is saved on the device, so a second launch paints the dashboard
  at once and refreshes behind it. A complete saved copy is also the stopping
  point for an incremental refresh: Fathom reads newest-first only until a
  wholly known page, so a normal second launch costs one page rather than the
  whole repo. The saved copy keeps the read's completeness flag, so a partial
  history is still labelled partial when it reopens.
- Pull-to-refresh on every dashboard screen, so reading the repo again does
  not mean relaunching; the figures already on screen stay while the new read
  arrives. A refresh that stops on its first known page does not flash the
  reading banner.
- Profile offers **Read everything** for a full re-read after an edit,
  deletion or backfill below the saved tail.
- Top tracks in Stats, ranked across the selected period with the artist under
  each title, so the tracks total has a list behind it.
- Now playing is re-read once a minute while the dashboard is in the
  foreground and no history read is running, with an immediate read when the
  app returns, so a status that expires while the app is open stops claiming a
  track is playing.
- OAuth scope constants and client metadata recorded for a future write path,
  unused today.
- One version source, [`version.properties`](version.properties), now feeds
  Android directly and the iOS project through `scripts/sync-version.py`. CI
  rejects drift between the marketing version, Android version code and iOS
  build number.
- A manual **Release artifacts** workflow is set up to build signed Android
  AAB/APK and App Store Connect IPA files from a protected GitHub environment. Local
  `scripts/release-android.sh` and `scripts/release-ios.sh` do the same and
  verify the signatures or embedded version before handing over artifacts.
- A release checklist covering secrets, internal testing, smoke tests and
  rollback is documented in `docs/RELEASE.md`. Store uploads remain manual.

### Fixed

- The Now chart states its own fourteen-day total instead of borrowing the
  selected period's play count, which was a different number whenever Stats
  was not showing seven days.
- The busiest day on Stats shows a readable date instead of the raw ISO day
  key.
- A status without an explicit `expiry` now stops being live ten minutes after
  `time`, as the lexicon specifies, instead of staying live forever.
- Plays whose timestamp cannot be read are counted in every period and
  reported in both Stats and History, rather than silently missing from the
  chart and History.
- A saved history without a `version` field is discarded instead of being read
  as the current schema, and every new cache file writes the version it was
  written with.
- The Android error for an unsupported DID method names the identity instead of
  printing the literal `$did`, and iOS uses the same wording.
- A 400 from `listRecords` is no longer treated as an empty collection: the
  pages already read are kept, the history stays partial, and the failure is
  shown. A 404 still means an absent namespace.
- Transient PDS reads retry three times with bounded, jittered backoff,
  honouring `Retry-After` in seconds and HTTP-date form. A 429 followed by a
  200 finishes the read; 400 and 404 are never retried.
- Plays keep their AT URI and CID, so overlapping pages and restored caches
  deduplicate by record identity rather than by track or time. Repeats of the
  same track still count separately.
- The saved-history format is version 3, the first whose completeness flag is
  trusted as an incremental stopping point. Version 2 and version 1 files are
  discarded and rebuilt, so an upgrade starts with a full read.

### Security

- Release signing material is supplied only through environment variables or
  the gitignored `Android/keystore.properties`; certificates, profiles,
  keystores and passwords are excluded from version control and temporary CI
  keychains are removed after the build.
- The saved listening history is excluded from Android Auto Backup and device
  transfer, and from iCloud and computer backups on iOS. The iOS file is also
  written with complete file protection, so it is unreadable while the device
  is locked.
