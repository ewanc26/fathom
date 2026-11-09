# AGENTS.md

Guidance for AI coding agents working in **fathom**.

## Project overview

A native teal.fm client for iOS and Android: a read-only listening dashboard
over a shared Kotlin Multiplatform core. The write path — live scrobbling to
the account's own repo — is planned and tracked in the issue tracker.

- Language: Kotlin (Multiplatform core), Swift (iOS UI), Kotlin/Compose (Android UI)
- Default branch: main

## Principles

1. **KMP-first.** Anything that can be shared goes in `shared/` as Kotlin.
   Record models, decoding, query building, statistics and formatting all live
   there. Only platform UI, transport and preference storage stay native. If
   you are about to write a decision in Swift or Kotlin-for-Android, it belongs
   in `shared/`.
2. **Protocol truth over convenience.** Check the lexicons in `lexicons/` and
   the behaviour of the live service rather than inferring a shape from one
   response. A bespoke response that does not match its own lexicon is not a
   contract.
3. **Honest figures.** A number computed from a bounded read is labelled as
   such. "All time" over the newest thousand plays is not all time, and the
   screen must say so. Never present a local aggregate as a network total.
4. **Tolerant reading, strict decoding.** One malformed record costs one
   record: decode per element and count what was skipped. But do not enable
   lenient JSON — a number where the lexicon says string is corruption, not a
   quirk.
5. **No secrets.** Fathom writes nothing and authenticates as nobody. If that
   changes, credentials go in Keychain/Keystore and never in preferences,
   logs, or git.
6. **No duplication.** Two implementations of one rule drift. `FathomCore` is
   the iOS facade and must delegate; it must never reimplement what
   `StatsCalculator` or `TealJson` already does.

## Source boundaries

- `shared/src/commonMain/kotlin/uk/ewancroft/fathom/shared/`
  - `model/` — lexicon records as data classes, plus the tolerant page types.
  - `stats/` — `Period` and `StatsCalculator`, the only place a figure is
    computed.
  - `time/` — RFC 3339 parsing and calendar arithmetic, with no platform types.
  - `format/` — durations, counts, relative times, service names.
  - `search/` — the one folded, case- and diacritic-insensitive matcher used
    by History on both platforms. UI filtering must not reimplement it.
  - `xrpc/` — collection constants, query encoding, blob URLs.
  - `auth/` — the scopes a future write would need. Unused today.
  - `FathomCore.kt` — the narrow Swift-facing facade.
- `Android/app/src/main/java/uk/ewancroft/fathom/`
  - `data/` — `TealClient` (OkHttp), `FathomRepository` (pagination),
    `IdentityStore` (preferences).
  - `ui/` — one folder per screen, plus `components/` for shared pieces.
- `iOS/Fathom/`
  - `Networking/TealClient.swift`, `Model/DashboardLoader.swift`,
    `Model/AppModel.swift`, `Features/` per screen, `Components/` shared,
    `Resources/` for the bundled licence.
- `website/`
  - Plain HTML and CSS served by GitHub Pages. `/privacy/` and `/terms/` are
    linked from the client metadata, both stores and the About sections.
    Claims here must describe the code, not an aspiration.
- `tools/release/`
  - `publish.mjs` builds/signs Android locally, builds an unsigned AltStore
    Classic IPA, updates the F-Droid repo and AltStore source, and creates one
    unified `v<version>` GitHub Release. `build-release-manifest.mjs` hashes
    the exact published binaries for CI attestation; `validate-altstore.mjs`
    checks the source and website mirror. Signing material never enters GitHub
    Actions.

## Protocol notes that are easy to get wrong

- The collection authority moved from `fm.teal.alpha.*` to `fm.teal.*`. Both
  can exist in one repo. Read both, and say when older records are included.
- `fm.teal.feed.play` requires only `trackName`. `artists` can be present and
  empty, and `trackName` can be empty in a status record. Never assume a play
  has a track or an artist.
- A `listRecords` envelope carries `uri` and `cid`; both are copied onto the
  play. Deduplicate by URI only: the same track played twice is two plays,
  while the same URI is one record.
- A complete saved read is a stopping point only when every cached play has a
  URI. Page newest-first until a whole page is already known; a page with a
  new or unidentified record keeps the read going. A partial cache always
  triggers a full read, and **Read everything** is the escape hatch for
  edited, deleted or backfilled records below the saved tail.
- `fm.teal.actor.status` and `fm.teal.actor.profile` are keyed `literal:self`,
  so `getRecord` at that key is the right read. A 400/404 there means "not
  written", not "failed".
- A play with a missing or unreadable `playedTime` is counted by
  `undatedPlays` in every period. All-time totals include it; a window cannot,
  and History states how many records it could not place. Never invent a
  timestamp.
- A status record survives its `expiry`. Both the expiry and the presence of a
  track have to be checked before saying something is playing; when `expiry`
  is absent, the lifetime is ten minutes after `time`. `TealStatus.isLive` is
  the one implementation of both rules, and both apps call it.
- `nowapi.teal.fm/xrpc/fm.teal.actor.getStatus` returns `{did, isListening,
  message}` — not the lexicon's `statusView`. Do not build on it.
- `com.atproto.repo.listRecords` caps `limit` at 100 and returns newest first
  by default. Do not send `reverse: false`; leave it unset.
- A 404 from `listRecords` is an empty namespace. A 400 is not: AT Protocol
  also uses it for an invalid cursor or malformed request, so it must leave the
  read partial. Only `getRecord` treats a 400 as absence, because a missing
  `literal:self` record is normal.
- Idempotent reads retry 429, 5xx, timeouts and connection failures three
  times through `RetryPolicy`; never retry 400 or 404. `Retry-After` is
  honoured in seconds and HTTP-date form, and every wait is capped.
- Facet byte offsets and timestamps are not character indices. Day bucketing is
  by the listener's offset, through an explicit floor division.

## Build and verification

```bash
# Shared core tests (the real coverage)
cd Android && ./gradlew :shared:jvmTest

# Android JVM tests and debug build
cd Android && ./gradlew :app:testDebugUnitTest :app:assembleDebug

# iOS: framework, project, build
./scripts/build-xcframework.sh
cd iOS && xcodegen generate
xcodebuild -project Fathom.xcodeproj -scheme Fathom \
  -destination 'platform=iOS Simulator,name=<installed device>' build test

# Version source must match the generated iOS fields
python3 scripts/sync-version.py --check
```

- `./gradlew test` does **not** run the shared tests: the KMP JVM target
  exposes `jvmTest`, not `test`. `:app:testDebugUnitTest` runs the Android
  transport test and needs no device or network.
- Gradle 8.13 does not run on JDKs newer than 23. Set `JAVA_HOME` to a JDK
  17–23 (or the build fails before it compiles anything).
- `xcodebuild` needs the XCFramework, which is gitignored and must be built
  first. Rebuild it after any change under `shared/`.
- The Xcode project is generated. `iOS/Fathom.xcodeproj` is gitignored;
  `iOS/project.yml` is the source.
- `version.properties` is the one version source. Android reads it directly;
  run `python3 scripts/sync-version.py` after a bump to write the iOS fields.
  Release signing comes from environment variables or the gitignored
  `Android/keystore.properties`; never commit a team, certificate, profile or
  key. `RELEASE_PROCESS.md` is the release contract: local signing, then CI
  provenance only.

State exactly what was verified and where — host, simulator, device. Never
claim hardware you did not use. Neither app has been run on a physical device;
0.2.0 is signed for F-Droid and packaged unsigned for AltStore Classic.

## Conventions

- Conventional Commits, scoped by area: `feat(android): …`,
  `fix(shared): …`, `docs(protocol): …`.
- British English in prose and UI copy.
- Files own one responsibility; split before 400 lines.
- Comments explain decisions and protocol constraints, not what the code
  already says.

## Current state

Read-only. No OAuth, no writes, no secure storage, no image loading, no
database. Four screens fed by one fetch, plus one saved-history file rewritten
whole after each read and excluded from device backups, and one status record
re-read a minute while the dashboard is in the foreground and no history read
is running. A complete saved history is a checkpoint for an incremental
refresh; a partial one always forces a full read, and Profile's **Read
everything** action forces one on demand. The handle and period stay in ordinary
preferences and may be restored from a backup; the history never is.  `website/client-metadata.json` records the scopes that a write would need.

History is searchable through `PlaySearchIndex`, and both apps group its
results by local day. Undated plays are listed under **No date** rather than
dropped. About reads the real build metadata, links the public site and shows
the bundled AGPL text plus the shared third-party notices. Releases follow
the Inkwell split: `tools/release/publish.mjs` builds/signs the APK locally,
builds an unsigned IPA for AltStore Classic, updates the self-hosted F-Droid
repo and AltStore source, and creates one `v<version>` GitHub Release. The
release workflow only adds SBOMs, a hashed manifest and provenance
attestations; signing keys never enter Actions. Both release keys live only in
gitignored local files and must be backed up separately.

## Working rules

- Inspect the README, manifests, CI workflows and nearby code before editing.
- Preserve existing architecture, naming, formatting and error handling.
- Use project scripts for validation; never claim checks you did not run.
- Keep changes scoped and update tests or documentation when behaviour changes.
- Use feature branches and pull requests, and treat generated files,
  credentials, deployment configuration and release metadata as sensitive.
