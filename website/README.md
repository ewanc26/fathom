# Fathom website

The public landing page and the privacy and terms documents for Fathom. It is
plain HTML and CSS with no build step, no JavaScript, no cookies and no
third-party requests.

## Paths

| Path | Purpose |
|------|---------|
| `/` | What Fathom is, the four screens, how the figures are made, the install routes and the current release status. |
| `/privacy/` | The privacy policy promised by `client-metadata.json` and both stores. |
| `/terms/` | The terms of use. |
| `/altstore/` | AltStore Classic source, icon and hosted IPA. |
| `/fdroid/repo/` | The self-hosted F-Droid repository, mirrored from `Android/fdroid-repo/repo/`. |
| `/client-metadata.json` | The OAuth client metadata, served unchanged. Unused today because Fathom does not write. |
| `/icon.png` | The app icon, used by the site and by `logo_uri`. |

## Preview locally

```bash
python3 -m http.server 8000 --directory website
```

Then open <http://localhost:8000>. The clean URLs are directories, so
`/privacy/` and `/terms/` work without a server rewrite rule.

## Deploy

Vercel deploys this repository as project **`fathom-app`**. The repository root
stays the project root; [`../vercel.json`](../vercel.json) serves `website/` as
a static site and enforces trailing slashes. There is no build command and no
framework preset to maintain.

The Vercel Git integration deploys `main` on every push that changes the site.
To deploy manually:

```bash
vercel link --project fathom-app
vercel --prod
```

The working project origin is `https://fathom-app-delta.vercel.app`.

`fathom.ewancroft.uk` is attached to the same Vercel project and is the
canonical origin for the app's About links, the OAuth client metadata and the
legal pages. Its Cloudflare DNS record is still pending. Add this record in
Cloudflare:

| Type | Name | Target | Proxy |
|------|------|--------|-------|
| CNAME | `fathom` | `3012d949f736e19c.vercel-dns-017.com` | **DNS only** (grey cloud) |

Do not proxy the record through Cloudflare. Once it resolves, Vercel issues
the certificate. Do not add a `CNAME` file to this directory: that only applies
to GitHub Pages, which is no longer used.

The absolute URLs in `client-metadata.json` remain
`https://fathom.ewancroft.uk/...` on purpose: that is the origin the OAuth
metadata names. Until the custom domain resolves, the channel files and install
links use the working Vercel project origin.

## Install channels

AltStore Classic and F-Droid are the first distribution routes. Both are
static files under `website/`; there is no Fathom download server.

- AltStore source: `https://fathom-app-delta.vercel.app/altstore/source.json`.
  `tools/release/publish.mjs ios` updates it and copies the unsigned IPA into
  `website/altstore/`. AltStore Classic re-signs that IPA with the user's Apple
  ID. Never add `marketplaceID`; this is not a PAL source.
- F-Droid repo: `https://fathom-app-delta.vercel.app/fdroid/repo`. The publisher
  regenerates `Android/fdroid-repo/repo/`, mirrors it here, and updates the
  metadata. The install link includes the repo index fingerprint
  `DE922C5717CAB6C0A48AF84B5D0EBC72302308023DE7AC770BA68911F771BB29`.

Once `fathom.ewancroft.uk` resolves, set `FATHOM_SITE_BASE` for the publisher,
regenerate the AltStore source and F-Droid index, and update the links in
`index.html` and the metadata together.

## Rules

- Keep `/privacy/` and `/terms/` at those paths; the client metadata and both
  store listings point at them.
- Do not add analytics, advertising, third-party fonts or tracking scripts.
- Keep the claims aligned with the apps: read-only, no account, no tokens, one
  app-private history file, excluded from backups, and no Fathom server.
- The site is part of the AGPL-3.0 source distribution. A change to it is a
  change to the repository.
