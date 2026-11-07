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
  progress rather than a stalled spinner.
- A history is saved on the device, so a second launch paints the dashboard
  at once and refreshes behind it. The saved copy keeps the read's completeness
  flag, so a partial history is still labelled partial when it reopens.
- Pull-to-refresh on every dashboard screen, so reading the repo again does
  not mean relaunching; the figures already on screen stay while the new read
  arrives.
- OAuth scope constants and client metadata recorded for a future write path,
  unused today.
