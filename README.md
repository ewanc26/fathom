<p align="center">
  <img src="docs/images/app-icon.png" alt="Fathom" width="180">
</p>

<p align="center">
  <a href="https://github.com/ewanc26/fathom/actions/workflows/ci.yml"><img src="https://github.com/ewanc26/fathom/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/ewanc26/fathom?label=licence" alt="Licence"></a>
  <a href="https://github.com/sponsors/ewanc26"><img src="https://img.shields.io/github/sponsors/ewanc26?logo=githubsponsors&logoColor=white&label=sponsors" alt="Sponsor"></a>
</p>

# Fathom

A native teal.fm client for iOS and Android.

Fathom reads the plays already in your AT Protocol repo and turns them into a
listening dashboard: what is playing now, what today has been, top artists and
releases over 7 days, 30 days or all time, and the full history grouped by the
day you actually had.

It is a **reader**. It never writes a record, never asks for a password and
never holds a token.

## Status

Read-only, and new. I would rather say so plainly.

- **The dashboard works end to end.** All four screens draw from a full read of a real account, a second launch opens from the saved copy, and the reading banner can be dismissed while the read continues behind it.
- **The figures are honest by construction.** A read that ends early is labelled partial rather than passed off as complete, and the runaway guard stops at 50,000 plays.
- **Neither app has been run on a device.** Everything verified is Android emulator and iOS simulator, and there is no signed build yet, so installing Fathom still needs a toolchain.
- **The write path is planned, not started.** Live scrobbling — OAuth, play qualification, an outbound queue and the controls that come with them — is designed in the issue tracker. Nothing here writes yet.

The changelog is in [CHANGELOG.md](CHANGELOG.md), the conventions and the
decisions behind them in [AGENTS.md](AGENTS.md), and the open work in the
[issue tracker](https://github.com/ewanc26/fathom/issues).

## Why it reads your repo rather than an AppView

teal.fm's custom lexicons describe plays, profiles and statuses, and their
records live in each person's own PDS. The hosted AppViews are not reliably
deployed — when this was written, one public host answered
`fm.teal.actor.getStatus` and 404'd every `fm.teal.stats.*` route — so a
dashboard built on those endpoints would be a dashboard that mostly does not
load.

Every play is in the repo, so Fathom reads `com.atproto.repo.listRecords` from
the account's PDS and aggregates on the device, a hundred records at a time,
until the repo runs out. Reading all of it is what makes "All time" a total
rather than the newest slice. A page costs about half a second on a real data
server, so a long history would be minutes of waiting: the dashboard opens on
the newest page and its figures grow as the rest arrives. A runaway guard stops
at 50,000 plays, and a read that ends early is labelled partial rather than
passed off as complete. See [`docs/PROTOCOL.md`](docs/PROTOCOL.md) for the
research behind reading the repo at all.

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
- **Saved history** — a completed read is written to app-private storage, so a
  second launch draws the dashboard immediately and refreshes behind it. A
  saved copy keeps the read's completeness label, so a partial history is
  still labelled partial when it reopens.
- **Refresh** — pull down on any screen, or use the Refresh action, to read
  the repo again without relaunching. The figures already on screen stay while
  the new read arrives.
- **Background reads** — the dashboard fills in as pages arrive, and the reading
  banner can be dismissed without stopping the read. The next read brings the
  banner back.
- Reads both `fm.teal.*` and the pre-migration `fm.teal.alpha.*` namespace, so
  an account mid-migration keeps its older history.
- Works for any account on any PDS: the DID is resolved through the PLC
  directory and the records are read from that account's own data server.

## Screens

The four screens on iOS, captured from the simulator against a real account.
Android draws the same information with Material 3 components.

| Now | Stats | History | Profile |
|:---:|:---:|:---:|:---:|
| <img src="docs/images/screens/now.png" width="200" alt="Now screen: today's plays, time and streak, a fourteen-day chart, and recent plays"> | <img src="docs/images/screens/stats.png" width="200" alt="Stats screen: totals for 7 days and top artists"> | <img src="docs/images/screens/history.png" width="200" alt="History screen: plays grouped by day"> | <img src="docs/images/screens/profile.png" width="200" alt="Profile screen: account details and how the figures are made"> |

## Building

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

<p align="center">
  <img src="docs/images/app-icon.png" alt="Fathom's app icon: five teal bars on a deep teal-black tile" width="180">
  &nbsp;&nbsp;&nbsp;
  <img src="docs/images/launch-mark.png" alt="Fathom's launch mark: the same five bars on the deep background" width="247">
</p>

The mark is the dashboard's own chart: five bottom-aligned bars, the tallest in
mint. The launch screen carries the same bars without the tile, and both are
drawn from one set of coordinates in `scripts/gen-icon.py`.

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

Both apps have been run against a real account (`ewancroft.uk`) on an Android
emulator and an iOS simulator. That run is what found a facade method that
called itself and crashed the first network read, and what established that
the account holds more than the 50,000-play guard: the read stops there, the
screens say the figures are partial, and the saved copy keeps that label.

The iOS account run lives in `iOS/FathomUITests/` and in the `FathomAccount`
scheme, separate from the default scheme because it needs the public network
and about five minutes.

## Limitations

- **Read-only.** There is no scrobbling, no profile editing and no status
  writing. `website/client-metadata.json` records the scopes those would need.
- **Long first loads.** The history is read a hundred plays at a time, so an
  account with tens of thousands of plays takes minutes to read the first
  time; the dashboard opens on the newest page and counts the plays it has
  reached while the rest arrive. Later launches open from the saved copy while
  that read runs again. The guard stops at 50,000 plays, and anything read
  short of the end is labelled partial.
- **Figures are local.** They are computed from this repo, not from teal.fm's
  network-wide stats endpoints, and the two are not the same measurement.
- **No OAuth.** None is needed to read, and a half-implemented flow would be
  worse than none.

## Contributing

[AGENTS.md](AGENTS.md) is the architectural contract; read it before changing
anything, and keep it aligned with the code. Conventional Commits, scoped by
area: `feat(android): …`, `fix(shared): …`, `docs(protocol): …`. British
English in prose and UI copy. The
[issue tracker](https://github.com/ewanc26/fathom/issues) holds the open work:
pick an issue, branch from `main`, and open a pull request.

## Support

If you find this project useful, consider supporting its development:

[![Ko-fi](https://img.shields.io/badge/Ko--fi-F16061?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/ewancroft)
[![GitHub Sponsors](https://img.shields.io/badge/GitHub%20Sponsors-30363D?style=for-the-badge&logo=github&logoColor=white)](https://github.com/sponsors/ewanc26)

## Licence

Fathom is licensed under the GNU Affero General Public License v3.0. See
[LICENSE](LICENSE).

## Star History

<a href="https://www.star-history.com/?repos=ewanc26%2Ffathom&type=date&legend=bottom-right">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=ewanc26/fathom&type=date&theme=dark&legend=bottom-right" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=ewanc26/fathom&type=date&legend=bottom-right" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=ewanc26/fathom&type=date&legend=bottom-right" />
 </picture>
</a>
