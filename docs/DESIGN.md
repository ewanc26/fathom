# Design

## The idea

teal.fm's promise is "your music, beautifully tracked". A dashboard's job is
the second word: to answer "how much, how often, and of what" at a glance,
without a feed to scroll.

So Fathom is four screens with one job each, and every screen leads with a
number rather than a list.

## Screens

### Now

The opening screen answers what is happening and what today has been.

1. **Now playing** — the status record, when it is live. It shows the track,
   the artist and the time playback started. When it is not live the card still
   appears, saying so, because an empty top of the screen reads as a bug.
2. **Today** — three tiles: plays today, time today, day streak. Three is the
   limit; a fourth would push the numbers below the fold on a small phone.
3. **Last 14 days** — a bar chart, one bar per day. A day with no plays is a
   short stub in the muted colour, so a gap reads as zero rather than as
   missing data.
4. **Recently played** — four plays, enough to confirm the account is the right
   one without becoming the History screen.

The streak counts back from today and does not break for a day still in
progress: if yesterday had plays and today has none yet, the streak is intact.
Otherwise every streak would read zero every morning.

### Stats

The period is the first control, as a segmented row: 7 days, 30 days, all time.
Those are teal.fm's own period values, so the vocabulary survives if an AppView
ever answers these questions.

Then five totals, then the busiest day and the most used service, then top
artists and top releases with a bar showing each one's share of the leader.

The provenance line at the bottom is part of the design, not a disclaimer:
these figures are computed from this repo, and teal.fm's stats endpoints
aggregate across the network. Both are legitimately "top artists" and they are
different numbers.

### History

Every play read, grouped by day with the day's name as a heading. Times are
relative within a week and a date beyond it. The length shown is the length the
record declared, which is not a measurement of time listened — the app never
claims otherwise.

### Profile

Who the dashboard is about, and what it is reading. The account panel states
the DID, the handle, how many plays were read and whether a teal profile record
exists. The method panel states the namespace rule and the local-computation
rule in plain sentences. Disconnect is the only action; it deletes the saved
history and forgets the handle.

## Colour and type

Dark-only. The palette is a deep teal-black surface with one bright teal for
emphasis, one deep teal for containers and a muted grey-teal for secondary
text. A light mode would be a second design rather than a recolour, and one
that is right beats two that are guesses.

Hierarchy is carried by size and weight, not by colour alone: every number is
larger than its label, and no state is indicated only by hue.

## The mark

The app icon is the dashboard's own chart: five bottom-aligned bars, the
tallest in mint and the rest in the bright teal. It borrows the family's
construction rather than its letterform — one centred geometric mark, flat
colour, no text — because Inkwell's letter belongs to Inkwell.

iOS gets one opaque 1024×1024 square with no baked corners or shadow, so the
system can mask it and apply its own dark and tinted treatments. Android uses
108 dp adaptive layers, keeps the mark inside the 66 dp safe zone, and ships a
monochrome layer for themed icons. `scripts/gen-icon.py` draws the bitmap and
both vector layers from the same coordinates, so the two cannot drift.

## Platform fidelity

The same four screens, drawn with each platform's own conventions:

- **Android** uses a Material 3 `NavigationBar`, a `TopAppBar` with a Refresh
  action, pull-to-refresh on every tab, `FilterChip` for the period and cards
  with a 20 dp corner radius.
- **iOS** uses a `TabView` with SF Symbols, a navigation title with a toolbar
  button and pull-to-refresh, a segmented `Picker` and a plain `List` for
  History.

Neither is a port of the other. The KMP boundary is logic; presentation stays
native.

## Accessibility

- Every interactive element has a text label or an accessibility description;
  no icon-only control is unlabelled.
- Text scales: tiles use `minimumScaleFactor` rather than clipping.
- Status is stated in words ("NOW PLAYING", "Nothing is playing right now"),
  never by colour alone.
- The chart is decoration over numbers that are also stated in text, so it does
  not carry information only sighted users can reach.
- Touch targets are the platform defaults rather than shrunk to fit density.
