# Protocol research

What the dashboard is built on, and what was checked rather than assumed.

## The lexicons

teal.fm publishes its lexicons from the account
`did:plc:iwhuynr6mm6xxuh25o4do2tx` into `com.atproto.lexicon.schema`, and
maintains the same JSON in [teal-fm/teal](https://github.com/teal-fm/teal)
under `lexicons/fm.teal/`. The copies in `lexicons/` are taken from that
repository unmodified; the 15 files there are the complete published set.

The lexicon authority moved from `fm.teal.alpha.*` to `fm.teal.*`. Both may be
present in one repo — a record keeps the collection it was written to — so
Fathom reads both and reports when older records are included.

### Records

| NSID | Key | Notes |
|------|-----|-------|
| `fm.teal.feed.play` | `tid` | One play. Only `trackName` is required. |
| `fm.teal.actor.profile` | `literal:self` | At most one per repo. |
| `fm.teal.actor.status` | `literal:self` | One per repo; what is playing now. |

A play carries `trackName`, optional `artists[]` (each with `artistName` and
`artistMbId`), `releaseName`, `releaseMbId`, `duration` in seconds,
`playedTime`, `originUri`, `musicServiceUri`, `isrc` and
`submissionClientAgent`. `artistNames[]` and `artistMbIds[]` are marked
deprecated in the lexicon but still appear in older records, so both are
decoded and the current field is preferred.

A status carries `time`, an optional `expiry`, and `item`, which is a
`fm.teal.feed.defs#playView`. The lexicon says to assume ten minutes after
`time` when `expiry` is absent, and that the newest record wins if several
exist — in practice a client writes to `literal:self`, so "newest" and "only"
coincide.

### Queries

`fm.teal.feed.getActorFeed`, `getPlay`; `fm.teal.actor.getProfile`,
`getProfiles`, `getStatus`, `searchActors`; `fm.teal.stats.getLatest`,
`getTopArtists`, `getTopReleases`, `getUserTopArtists`, `getUserTopReleases`.

The stats queries take a `period` of `all`, `30days` or `7days` (the per-user
pair accepts only `30days` and `7days`) and return `playCount` tallies. Fathom
does not call them — see below — but `Period` uses their exact ids so a
selection can be handed to an AppView unchanged if one becomes dependable.

## What was actually reachable

Checked live while building this:

```
nowapi.teal.fm
  GET /health                                   200  ok
  GET /xrpc/fm.teal.actor.getStatus?actor=…     200  {"did":…,"isListening":false,"message":…}
  GET /xrpc/fm.teal.stats.getLatest?limit=2     404

teal-relay-production.up.railway.app
  every route                                  404  "Application not found"
```

Two things follow.

First, `nowapi.teal.fm` answers `fm.teal.actor.getStatus` with a bespoke body
(`did`, `isListening`, `message`) that is **not** the lexicon's `statusView`.
Anything written against it would break the moment it conformed to its own
lexicon. Fathom reads the status record with
`com.atproto.repo.getRecord` at `literal:self` instead, which always matches
the schema and needs no AppView.

Second, there is no dependable hosted stats AppView, so the figures are
computed from records. That is not a workaround for a missing feature; it is
the architecture the protocol intends, where an AppView is an index and the
repo is the source.

## Reading a repo

Every step is a public, unauthenticated read.

1. **Handle → DID**: `GET https://public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle?handle=…`.
   The public Bluesky AppView answers this for any handle on the network, not
   only its own accounts.
2. **DID → PDS**: `GET https://plc.directory/{did}` for `did:plc`, or
   `https://{host}/.well-known/did.json` for `did:web`. The service typed
   `AtprotoPersonalDataServer` is the one to read.
3. **Records**: `GET {pds}/xrpc/com.atproto.repo.listRecords?repo={did}&collection={nsid}&limit=100&cursor=…`.
   Default order is newest first, which is the order the dashboard wants;
   `reverse` is left unset rather than sent as `false`.
4. **Status**: `GET {pds}/xrpc/com.atproto.repo.getRecord?repo={did}&collection=fm.teal.actor.status&rkey=self`,
   where 400/404 means "no status", not "failed".

A 404 from `listRecords` means the collection is absent. A 400 does not:
AT Protocol also uses it for invalid cursors and malformed requests, so Fathom
keeps the pages already read, marks the read partial, and reports the failure
rather than presenting a false total. `getRecord` is the one read where a 400
is treated as "not written", because `literal:self` is a known key and a
missing record is a normal state.

Transient 429 and 5xx responses, timeouts and connection failures are retried
three times with bounded backoff. `Retry-After` is honoured in delay-seconds
and IMF-fixdate forms. A 404 is never retried: it is an answer, not a failure.

`listRecords` answers `{"records":[{"uri","cid","value"}],"cursor"}` and caps
`limit` at 100. A real page observed during development:

```json
{"records":[{"uri":"at://did:plc:…/fm.teal.feed.play/3mzqeselnybem",
  "cid":"bafyrei…",
  "value":{"$type":"fm.teal.feed.play",
           "artists":[{"artistName":"SXCREDMANE & ZMAJOR"}],
           "duration":110,
           "originUri":"https://music.apple.com/gb/album/aneurysm/…",
           "trackName":"aneurysm",
           "playedTime":"2026-11-07T17:02:12Z",
           "releaseName":"aneurysm - Single",
           "musicServiceUri":"https://music.apple.com",
           "submissionClientAgent":"piper/v0.1.1"}}],
 "cursor":"3mzqeqt4axqg3"}
```

Every record envelope carries `uri` and `cid`. The URI is the record's stable
identity: Fathom stores it on the play, deduplicates overlapping pages and
restored caches by it, and never merges two plays just because their track and
time look alike.

And a real status, which is why parsing cannot assume a track is named:

```json
{"item":{"artists":[],"trackName":""},
 "time":"2026-11-07T17:02:40Z",
 "expiry":"2026-11-07T17:01:40Z",
 "$type":"fm.teal.actor.status"}
```

That record is already expired and names nothing. `TealStatus.isListening`
checks both, and a tracker that has stopped still leaves one behind.

## Tolerant decoding

`kotlinx.serialization` fails a whole list on the first element it cannot read.
A repo written by several clients over years will contain such an element, and
losing a page for it would lose years of listening. `TealJson` therefore parses
the page as a tree and decodes `records[].value` one at a time, counting what
it had to skip so the UI can say a figure is approximate.

Decoding is **not** lenient: `"trackName": 5` is corruption and is skipped,
not coerced into the track "5".

## Timestamps

`format: datetime` is RFC 3339. Both `Z` and numeric offsets are accepted,
fractional seconds are truncated, and `23:59:60` is clamped to `23:59:59`
rather than rejected — adding a leap second would place the instant in the
next day. Anything unparseable returns null.

Day bucketing happens at the listener's offset, not UTC, and passes through an
explicit floor division so a negative offset west of Greenwich does not round
towards zero and shift a play into the wrong day.

## Authentication

None is used. OAuth 2.1 with DPoP is the protocol's scheme for authenticated
calls, and a client that writes needs a hosted `client-metadata.json` and the
matching `repo:` scopes. Fathom would need:

```
repo:fm.teal.feed.play?action=create
repo:fm.teal.actor.profile?action=create&action=update
repo:fm.teal.actor.status?action=create&action=delete
```

Those live in `shared/.../auth/Scopes.kt` and in `website/client-metadata.json`
so that the day a write is added, the two cannot drift. A scope can only name
collections in the client's own namespace unless `include:` is used, which is
why teal's collections are named individually rather than granted wholesale.

Until then there is nothing to authorise and no credential to keep, which is
why there is no Keychain or Keystore code in either app.
