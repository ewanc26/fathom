# AGENTS.md

Guidance for agents working on Fathom, a native teal.fm dashboard for iOS and
Android. This document is the architectural contract; keep it aligned with the
code.

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
  - `xrpc/` — collection constants, query encoding, blob URLs.
  - `auth/` — the scopes a future write would need. Unused today.
  - `FathomCore.kt` — the narrow Swift-facing facade.
- `Android/app/src/main/java/uk/ewancroft/fathom/`
  - `data/` — `TealClient` (OkHttp), `FathomRepository` (pagination),
    `IdentityStore` (preferences).
  - `ui/` — one folder per screen, plus `components/` for shared pieces.
- `iOS/Fathom/`
  - `Networking/TealClient.swift`, `Model/DashboardLoader.swift`,
    `Model/AppModel.swift`, `Features/` per screen, `Components/` shared.

## Protocol notes that are easy to get wrong

- The collection authority moved from `fm.teal.alpha.*` to `fm.teal.*`. Both
  can exist in one repo. Read both, and say when older records are included.
- `fm.teal.feed.play` requires only `trackName`. `artists` can be present and
  empty, and `trackName` can be empty in a status record. Never assume a play
  has a track or an artist.
- `fm.teal.actor.status` and `fm.teal.actor.profile` are keyed `literal:self`,
  so `getRecord` at that key is the right read. A 400/404 there means "not
  written", not "failed".
- A status record survives its `expiry`. Both the expiry and the presence of a
  track have to be checked before saying something is playing.
- `nowapi.teal.fm/xrpc/fm.teal.actor.getStatus` returns `{did, isListening,
  message}` — not the lexicon's `statusView`. Do not build on it.
- `com.atproto.repo.listRecords` caps `limit` at 100 and returns newest first
  by default. Do not send `reverse: false`; leave it unset.
- Facet byte offsets and timestamps are not character indices. Day bucketing is
  by the listener's offset, through an explicit floor division.

## Build and verification

```bash
# Shared core tests (the real coverage)
cd Android && ./gradlew :shared:jvmTest

# Android debug build
cd Android && ./gradlew :app:assembleDebug

# iOS: framework, project, build
./scripts/build-xcframework.sh
cd iOS && xcodegen generate
xcodebuild -project Fathom.xcodeproj -scheme Fathom \
  -destination 'platform=iOS Simulator,name=<installed device>' build test
```

- `./gradlew test` does **not** run the shared tests: the KMP JVM target
  exposes `jvmTest`, not `test`.
- Gradle 8.13 does not run on JDKs newer than 23. Set `JAVA_HOME` to a JDK
  17–23 (or the build fails before it compiles anything).
- `xcodebuild` needs the XCFramework, which is gitignored and must be built
  first. Rebuild it after any change under `shared/`.
- The Xcode project is generated. `iOS/Fathom.xcodeproj` is gitignored;
  `iOS/project.yml` is the source.

State exactly what was verified and where — host, simulator, device. Never
claim hardware you did not use. Neither app has been run on a device.

## Conventions

- Conventional Commits, scoped by area: `feat(android): …`,
  `fix(shared): …`, `docs(protocol): …`.
- British English in prose and UI copy.
- Files own one responsibility; split before 400 lines.
- Comments explain decisions and protocol constraints, not what the code
  already says.

## Current state

Read-only. No OAuth, no writes, no secure storage, no image loading, no cache,
no database. Four screens fed by one fetch. `website/client-metadata.json`
records the scopes that a write would need.
