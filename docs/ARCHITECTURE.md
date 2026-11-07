# Architecture

## The boundary

One rule decides everything else: **shared logic goes in `shared/`, in Kotlin.**

```
shared/  record models · tolerant decoding · XRPC construction · statistics · formatting
Android/ Compose screens · OkHttp transport · preferences
iOS/     SwiftUI screens · URLSession transport · UserDefaults
```

The reason the statistics are shared rather than per-platform is that a play
count is the product. Two implementations would drift, and a figure that
disagrees with itself on two phones is worse than one that is merely wrong.

The reason the transport is *not* shared is that each platform already has a
good HTTP client and a bad cross-platform one. `TealClient` exists twice, is
about 150 lines each, and contains no decisions — only a URL, a status code and
an error string. Everything it would be tempting to put in it (query building,
blob URLs, error-body parsing) is in `shared/`.

`shared/FathomCore.kt` is the iOS-facing facade. Kotlin/Native cannot export a
generic class usefully to Swift — `RecordPage<Play>` arrives as an untyped
collection — and a Kotlin enum arrives as a class whose entry names are not
obvious. So Swift calls a narrow, string-keyed surface that delegates to the
real types. Android skips the facade and calls those types directly. Anything
added to the facade must delegate, never reimplement.

## Data flow

```
handle ──▶ resolveHandle ──▶ DID ──▶ plc.directory ──▶ PDS
                                     │
                                     ├── listRecords fm.teal.feed.play
                                     ├── listRecords fm.teal.alpha.feed.play   (migration)
                                     ├── listRecords …actor.profile
                                     └── getRecord   …actor.status/self
                                     │
                              TealJson (tolerant, per record)
                                     │
                             StatsCalculator (pure)
                                     │
                                 Dashboard
                                     │
                          Now · Stats · History · Profile
```

`Dashboard` is fetched once and read by all four tabs. Each tab does not fetch
its own: four independent loads would give four answers to "plays this week"
on four screens.

The clock is read once per dashboard and passed everywhere, so every relative
time on a screen agrees with every other.

## Purity

`StatsCalculator.compute` takes the play list, the current epoch second and the
local offset. It reads no clock, no timezone and no global. That is what makes
the tests assertable on a fixed date without a fake clock, and it is what lets
both platforms produce the same figure from the same input.

The same applies to `IsoInstant`, `CivilDays` and the formatters: no platform
types, no `java.time`, no `Foundation`, no `expect/actual`. Common code with no
platform dependency is common code that cannot behave differently.

## Reading everything, with brakes

- **Plays**: every play in the repo, a hundred at a time, until the cursor
  runs out. That is what lets "All time" be a total.
- **The guard**: fifty thousand plays. A runaway guard rather than a product
  limit, because a decade of heavy listening is the point at which the list
  costs more memory than a dashboard is worth.
- **Partial reads**: `sampled` means the read ended early — the guard, a failed
  page, or a cursor that did not advance — and every screen showing a total
  says the figures are partial. A 400 or 404 for a collection is *not* partial:
  it is an empty namespace, which is the normal state for an account on either
  side of the migration.
- **Progressive**: the profile, the status and the newest page render as soon
  as they arrive; the remaining pages continue in the background and the
  figures grow until the read finishes. A page cost about 585 ms on a real PDS,
  so a twenty-thousand-play history is roughly two minutes — showing a spinner
  for that long would look like a broken app.
- **Period changes**: recomputed from the plays already in memory. Only Refresh
  reads the repo again.
- **Cancellation**: a superseded load stops rather than finishing into a
  snapshot nobody asked for.
- **History**: the same list, not a second fetch.
- **Chart**: fourteen contiguous days, zero-filled, so an empty day is a stub
  rather than a missing bar.
- **Leaders**: five artists and five releases.
- **Partial pages**: a record that does not decode is counted and skipped.

## The saved copy

A completed read is written to app-private storage as one JSON file whose
format lives in `shared/cache`. The next launch decodes it, draws the
dashboard, and starts the repo read behind it; the saved figures are replaced
page by page as the real ones arrive.

The read's `sampled` flag is written with the plays, so a history that
stopped at the guard or on a failed page reopens as exactly that: the plays it
managed, still labelled partial, with a refresh behind them. A partial read is
not a smaller truth — it is a different claim, and it must not lose the label
that says so. `HistoryCache.decode` refuses a file from another version, a
truncated file and a file with no identity to match, because a cache is always
safe to discard.

This is a starting picture, not offline mode: a refresh failure leaves the
saved figures on screen with the error beside them, and the screens go on
saying they are computed from this repo rather than from teal.fm.

## Failure

Every failure becomes a sentence. A network failure, an unresolvable handle, a
DID with no data server and an unexpected body each produce a different
message, because "Could not load" tells the person nothing about whether to
retry, check the handle or check their connection.

A missing profile or status is not a failure: it is the normal state of an
account that has never written one, and the UI falls back to the handle.

## What is deliberately absent

- **No OAuth.** Nothing is written, so there is nothing to authorise.
- **No secure storage.** No credential exists to store.
- **No image loading.** Avatars are monograms; the dashboard makes one kind of
  request and cannot be slowed by a blob host.
- **No database.** One JSON file, rewritten whole after each completed read,
  plus the handle and period in preferences. There is no schema to migrate and
  nothing to vacuum.
