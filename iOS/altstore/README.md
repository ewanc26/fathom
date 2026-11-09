# AltStore Classic source

This directory is the source of truth for Fathom's AltStore Classic source at
`https://ewanc26.github.io/fathom/altstore/source.json`. The publisher writes
the same file to `website/altstore/source.json`; the two must stay byte-for-byte
identical.

## Classic only

Fathom uses **AltStore Classic**, not AltStore PAL. The IPA is unsigned when it
is published; AltStore Classic signs it with the user's own Apple ID through
AltServer. For that reason:

- do **not** add `marketplaceID`;
- do **not** add an ADP/notarisation flow;
- do **not** describe this as an App Store or PAL build.

The source is plain JSON. `versions[0]` is the newest release; AltStore uses
array order, not dates, to decide what is newer. Each entry needs a distinct
`version` or `buildVersion`.

## Publishing

[`tools/release/publish.mjs`](../../tools/release/publish.mjs) owns the update:

1. builds an unsigned IPA with
   [`scripts/build-altstore-ipa.sh`](../../scripts/build-altstore-ipa.sh);
2. copies it to `website/altstore/Fathom-<version>.ipa`;
3. prepends a `versions` entry with the date, size, SHA-256, build number and
   download URL;
4. mirrors this file to `website/altstore/source.json`;
5. commits both channel paths and, with `--push`, pushes the site update.

Only the publisher should edit `source.json`. Hand-editing the `versions` array
is how the top-level latest fields and the mirror drift apart.

The IPA files live under `website/altstore/` and are committed so GitHub Pages
serves them. This directory ignores local IPAs.

## Hosted paths

- Source: `https://ewanc26.github.io/fathom/altstore/source.json`
- Icon: `https://ewanc26.github.io/fathom/altstore/icon.png`
- IPA: `https://ewanc26.github.io/fathom/altstore/Fathom-<version>.ipa`

The custom domain `fathom.ewancroft.uk` is not live yet. The publisher uses
`FATHOM_SITE_BASE` to switch all hosted URLs once DNS is configured; regenerate
the source and F-Droid index at the same time.
