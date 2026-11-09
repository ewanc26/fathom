# Changelog

All notable changes to Fathom are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
semantic versioning.

## [Unreleased]

### Added

- AltStore Classic and self-hosted F-Droid distribution. The publisher now
  builds an unsigned IPA for AltStore, regenerates and mirrors the signed
  F-Droid repo, updates both channel files, and pushes the website assets with
  the GitHub Release.

### Changed

- The website and both channel files are now deployed by Vercel at
  `https://fathom-app-delta.vercel.app`; the GitHub Pages deployment has been removed.
  The canonical `fathom.ewancroft.uk` domain is attached to the same project but
  waits on its DNS record.
- Releases now follow Inkwell's split: `tools/release/publish.mjs` builds and
  signs the Android APK locally, builds the AltStore IPA, updates the
  self-hosted F-Droid repo, and creates one unified `v<version>` GitHub Release.
  CI only adds SPDX SBOMs, a release manifest and build-provenance
  attestations, so signing keys never enter Actions.
- GitHub release assets are now `Fathom-<version>.apk` and
  `Fathom-<version>.ipa`; the Play `.aab` stays a local store upload rather
  than a release asset. The attested release manifest is the published
  checksum record, replacing the per-platform `SHA256SUMS` upload.
- `RELEASE_PROCESS.md` replaces `docs/RELEASE.md` and documents local signing,
  provenance verification, store handoff and rollback.

## [0.2.1] - 2026-11-09

0.2.1 fixes the F-Droid listing icon. 0.2.0's APK contained only an adaptive
icon XML, which fdroidserver does not render; the release adds raster mipmap
fallbacks at every density. No app behaviour changed. Both channels move to
build 10.

## [0.2.0] - 2026-11-08

0.2.0 is the first tagged release. It adds the About surface, History search
and date navigation, the public website and a tag-driven release path. The
first distribution channels are AltStore Classic and a self-hosted F-Droid
repo; there is no App Store or Google Play listing.

### Added

- History search over track, artist and release, implemented once in the
  shared core with case- and diacritic-insensitive matching. The same query
  returns the same plays on iOS and Android, and search never reads the repo
  again.
- A month jump in History, built from the months the loaded plays actually
  contain, and a match count that distinguishes “12 matches” from “no plays
  match”.
- Plays with no readable timestamp are listed under **No date** with their
  count, instead of being left out of History after being counted in the
  figures.
- An About section on both platforms showing the version and build number from
  the bundle, the source repository, the AGPL-3.0 licence, privacy and terms
  links, copyright, and third-party notices. The full licence is bundled and
  readable offline.
- A public website in `website/`: landing page, privacy policy, terms of use,
  the app icon and the client metadata. It is plain HTML and CSS with no
  JavaScript, analytics or third-party requests, and is deployed by GitHub
  Pages.
- A tag-driven release path: `v<versionName>` builds the signed artifacts,
  checks the tag against `version.properties`, and publishes the AAB, APK, IPA
  and checksums on a GitHub Release. A manual dispatch builds artifacts
  without publishing.
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
  rollback is documented in `RELEASE_PROCESS.md`. Store uploads remain manual.

### Fixed

- History no longer presents a wall of every play with no way to narrow it:
  search and the month jump operate on the loaded records, and a blank query
  returns the full history in its original order.
- About reads `BuildConfig.VERSION_NAME`/`VERSION_CODE` on Android and the
  bundle's `CFBundleShortVersionString`/`CFBundleVersion` on iOS, so the
  version a tester sees is the build they installed rather than a literal.
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

- The release workflow now runs on a tag and waits on a protected `release`
  environment with a required reviewer. Signing secrets remain environment
  secrets; nothing is committed, and a manual dispatch cannot publish.
- The website has no cookies, analytics, advertising or third-party scripts;
  its privacy policy states what the apps store locally and what the hosting
  provider may log.
- Release signing material is supplied only through environment variables or
  the gitignored `Android/keystore.properties`; certificates, profiles,
  keystores and passwords are excluded from version control and temporary CI
  keychains are removed after the build.
- The saved listening history is excluded from Android Auto Backup and device
  transfer, and from iCloud and computer backups on iOS. The iOS file is also
  written with complete file protection, so it is unreadable while the device
  is locked.
