# Contributing to Fathom

Fathom is a native teal.fm client for iOS and Android: a Kotlin Multiplatform reader that shows the plays already in your AT Protocol repo as a listening dashboard. It never writes a record, never asks for a password and never holds a token. Thank you for wanting to help!

## Getting started

[AGENTS.md](AGENTS.md) is the architectural contract; read it before changing anything, and keep it aligned with the code. Pick an issue, branch from `main`, open a pull request.

- Conventional Commits, scoped by area: `feat(android): …`, `fix(shared): …`, `docs(protocol): …`.
- British English in prose and UI copy.
- The `shared/` Kotlin Multiplatform core (lexicon models, tolerant JSON decoding, XRPC construction, statistics) is what Android consumes; tests matter there most — statistics and decoding regressions are the worst kind.

## Notes for this project

- **Fathom is a reader.** The write path (live scrobbling, OAuth, outbound queue) is designed in the issue tracker but nothing writes yet. A half-implemented write path is worse than none: no OAuth, no tokens, no writes.
- Honesty by construction: a read that ends early is labelled partial, the runaway guard stops at 50,000 plays, and figures are computed from this repo, not from network-wide stats endpoints. Keep the labels truthful.
- Both channels are live (AltStore Classic, self-hosted F-Droid repo) but nothing has been run on a physical device; don't claim device-verified behaviour in a PR without actually doing it.
- Reading strategy lives in `docs/PROTOCOL.md` (why the repo, not the AppView): `com.atproto.repo.listRecords`, paging a hundred at a time, dashboard opening on the newest page while the rest arrives.

## Before opening a PR

- Android and iOS (simulator/emulator) still build; `shared/` tests pass.
- Conventional commits; one focused PR per change.
- AI-assisted PRs are welcome; stand behind every line.

## Licence

AGPL-3.0 (see [LICENSE](LICENSE)). Contributions are accepted under the same terms.