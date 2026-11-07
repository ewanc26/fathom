# Changelog

All notable changes to Fathom are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
semantic versioning.

## [Unreleased]

### Added

- A native teal.fm dashboard for iOS and Android, with Now, Stats, History and
  Profile screens.
- Kotlin Multiplatform core holding the `fm.teal.*` record models, tolerant
  per-record JSON decoding, XRPC query construction, and all listening
  statistics.
- Reads from the account's PDS rather than a hosted AppView, because the
  `fm.teal.stats.*` routes were not reachable on any public host.
- Both the production `fm.teal.*` and the pre-migration `fm.teal.alpha.*`
  namespaces are read, so an account mid-migration keeps its older history.
- Now playing from the `fm.teal.actor.status` record, including expiry
  handling and the empty-track records trackers leave behind.
- Bounded reads: at most 1,000 plays, with every figure labelled when the cap
  was reached.
- OAuth scope constants and client metadata recorded for a future write path,
  unused today.
