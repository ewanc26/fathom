# Fathom

A native teal.fm dashboard for iOS and Android.

Fathom reads the plays already in your AT Protocol repo and turns them into a
listening dashboard: what is playing now, what today has been, top artists and
releases over 7 days, 30 days or all time, and the full history grouped by the
day you actually had.

It is a **reader**. It never writes a record, never asks for a password and
never holds a token.

## Why it reads your repo rather than an AppView

teal.fm's custom lexicons describe plays, profiles and statuses, and their
records live in each person's own PDS. The hosted AppViews are not reliably
deployed — when this was written, one public host answered
`fm.teal.actor.getStatus` and 404'd every `fm.teal.stats.*` route — so a
dashboard built on those endpoints would be a dashboard that mostly does not
load.

Every play is in the repo, so Fathom reads `com.atproto.repo.listRecords` from
the account's PDS and aggregates on the device. The trade is bounded reads: up
to 1,000 plays are fetched, and when the repo holds more, the screens say the
figures cover only the most recent plays instead of presenting them as totals.
See [`docs/PROTOCOL.md`](docs/PROTOCOL.md) for the research behind that
decision.

## Repository structure

| Directory | Purpose |
|-----------|---------|
| `shared/` | Kotlin Multiplatform core: lexicon record models, tolerant JSON decoding, XRPC construction, and all of the statistics. Consumed by Android as a Gradle module and by iOS as an XCFramework. |
| `Android/` | Android client (Kotlin, Compose, Material 3). Also the only Gradle root. |
| `iOS/` | iOS client (SwiftUI), with a generated Xcode project. |
| `lexicons/` | The `fm.teal.*` lexicons, vendored from [teal-fm/teal](https://github.com/teal-fm/teal) unmodified. |
| `website/` | The OAuth client metadata Fathom would need if it ever writes. Unused today. |
| `docs/` | Protocol research, architecture, and the dashboard's design. |

## Features

- **Now** — what is playing, from the account's `fm.teal.actor.status` record,
  including whether it has expired; today's plays, today's time and the current
  day streak; a fourteen-day bar chart; the four most recent plays.
- **Stats** — plays, time in tracks, artists, releases and tracks for 7 days,
  30 days or all time; the busiest day and the most used service; top artists
  and top releases with shares of the leader.
- **History** — every play read, grouped by the local day, with relative times
  and the length each record declared.
- **Profile** — the teal.fm profile record, featured item, DID, and a plain
  statement of where the figures come from.
- Reads both `fm.teal.*` and the pre-migration `fm.teal.alpha.*` namespace, so
  an account mid-migration keeps its older history.
- Works for any account on any PDS: the DID is resolved through the PLC
  directory and the records are read from that account's own data server.

## Getting started

### Android

```bash
cd Android
./gradlew assembleDebug
```

Requires JDK 17–23 (Gradle 8.13 refuses newer), Android SDK 36, and
`local.properties` with `sdk.dir`. Run the shared core's tests with:

```bash
./gradlew :shared:jvmTest
```

`./gradlew test` does not run them: the KMP JVM target exposes `jvmTest`, not
`test`, so the aggregate task skips `:shared`.

### iOS

```bash
./scripts/build-xcframework.sh     # once, and after any shared/ change
cd iOS && xcodegen generate
open Fathom.xcodeproj
```

`JAVA_HOME` must point at a JDK the Gradle version supports. The Xcode project
and the XCFramework are build products and are gitignored; `project.yml` is the
source.

## Interoperability

Fathom reads; it publishes nothing, so it owns no lexicon namespace. The
collections it reads are teal.fm's own:

| Collection | Use |
|------------|-----|
| `fm.teal.feed.play` | Plays |
| `fm.teal.actor.profile` | The dashboard's profile header |
| `fm.teal.actor.status` | Now playing |
| `fm.teal.alpha.*` | The same three, before the migration |

## Design

Dark-only, teal, and built around one number per tile. The palette is chosen
against a deep surface and a light mode would be a second design rather than a
recolour, so there is only the one. Avatars are monograms: Fathom deliberately
does not fetch blobs, which keeps the dashboard to one kind of network request
and avoids decoding arbitrary images.

[`docs/DESIGN.md`](docs/DESIGN.md) covers the screens and the decisions in
them.

## Testing

- `shared/src/commonTest/` holds the real coverage: timestamp parsing and UTC
  bucketing, AT URI validation, query encoding, tolerant record decoding, and
  the statistics themselves.
- `iOS/FathomTests/` covers the Swift-facing facade — that the bridge still
  carries a play, a page and a computed figure.
- Android has no unit tests of its own: everything worth testing is in the
  shared core, and the app's remaining logic is transport and layout.

No hardware or emulator validation has been performed. The Android app builds
as a debug APK and the iOS app builds for the simulator; neither has been run
on a device.

## Limitations

- **Read-only.** There is no scrobbling, no profile editing and no status
  writing. `website/client-metadata.json` records the scopes those would need.
- **Bounded reads.** At most 1,000 plays per load. "All time" then means "all
  time, up to what was read", and the UI says so.
- **Figures are local.** They are computed from this repo, not from teal.fm's
  network-wide stats endpoints, and the two are not the same measurement.
- **No OAuth.** None is needed to read, and a half-implemented flow would be
  worse than none.

## Licence

Fathom is licensed under the GNU Affero General Public License v3.0. See
[`LICENSE`](LICENSE).
