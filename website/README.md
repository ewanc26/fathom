# Fathom website

The public landing page and the privacy and terms documents for Fathom. It is
plain HTML and CSS with no build step, no JavaScript, no cookies and no
third-party requests.

## Pages

| Path | Purpose |
|------|---------|
| `/` | What Fathom is, the four screens, how the figures are made, and the current release status. |
| `/privacy/` | The privacy policy promised by `client-metadata.json` and both stores. |
| `/terms/` | The terms of use. |
| `/client-metadata.json` | The OAuth client metadata, served unchanged. Unused today because Fathom does not write. |
| `/icon.png` | The app icon, used by the site and by `logo_uri`. |

## Preview locally

```bash
python3 -m http.server 8000 --directory website
```

Then open <http://localhost:8000>. The clean URLs are directories, so
`/privacy/` and `/terms/` work without a server rewrite rule.

## Deploy

`.github/workflows/pages.yml` publishes `website/` to GitHub Pages on every
push to `main` that changes it, and on manual dispatch.

One-time setup:

1. In **Settings → Pages**, set **Source** to **GitHub Actions**.
2. The `CNAME` file asks Pages for `fathom.ewancroft.uk`. At the DNS provider,
   add a `CNAME` record for `fathom` pointing at `ewanc26.github.io`.
3. After DNS resolves, enable **Enforce HTTPS** in the Pages settings.

The workflow uses the `github-pages` environment and needs `pages: write` and
`id-token: write`. It does not need repository secrets.

If the custom domain is not configured yet, the workflow still publishes to the
repository's Pages URL; every link inside the site is relative except the
source and licence links, so it works under either origin. The absolute URLs in
`client-metadata.json` remain `https://fathom.ewancroft.uk/...` on purpose:
that is the origin the OAuth metadata names.

## Rules

- Keep `/privacy/` and `/terms/` at those paths; the client metadata and both
  store listings point at them.
- Do not add analytics, advertising, third-party fonts or tracking scripts.
- Keep the claims aligned with the apps: read-only, no account, no tokens, one
  app-private history file, excluded from backups, and no Fathom server.
- The site is part of the AGPL-3.0 source distribution. A change to it is a
  change to the repository.
