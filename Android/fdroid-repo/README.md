# Fathom self-hosted F-Droid repo

This is the `fdroidserver` working copy for the repo hosted at
`https://fathom.ewancroft.uk/fdroid/repo`. It lives inside the Fathom
repository. The generated `repo/`, `metadata/`, icon and this README are
tracked; the signing material is not.

## What is here

- `config.yml` — local fdroidserver configuration, including the repo index
  signing password. Gitignored. Treat it as a secret.
- `keystore.p12` — the **F-Droid repo index signing key**. It proves that index
  updates came from this repository. Losing it means publishing a new repo at
  a new URL and asking every user to remove and re-add the old one.
- `fathom-icon.png` — the repo's own icon, separate from the app icon.
- `metadata/uk.ewancroft.fathom.yml` — the app listing shown in F-Droid.
- `repo/` — the generated signed index, icons and APK. The publisher mirrors it
  to `website/fdroid/repo`.

The **APK signing key** is separate: `Android/release-upload.jks`, configured
by `Android/keystore.properties`. It is not the F-Droid repo key and must not be
committed. Do not reuse Inkwell's keys for Fathom.

## First-time setup

Run the key setup once on the publisher's machine:

```bash
./scripts/setup-release-keys.sh
```

It generates two independent keys, writes the gitignored configuration, and
prints the public fingerprints. It refuses to overwrite existing material. Back
up all four files in a password manager or encrypted archive:

- `Android/release-upload.jks`
- `Android/keystore.properties`
- `Android/fdroid-repo/keystore.p12`
- `Android/fdroid-repo/config.yml`

Losing the APK key prevents upgrades; losing the repo key prevents index
updates. Neither can be recovered from GitHub.

## Publishing a version

The publisher does this automatically:

```bash
node tools/release/publish.mjs android --yes --push
```

The steps are:

1. Build and verify a signed release APK.
2. Copy it to `repo/Fathom-<version>.apk`.
3. Update `CurrentVersion` and `CurrentVersionCode` in the metadata.
4. Run `fdroid update --clean` here.
5. Replace `website/fdroid/repo` with the regenerated `repo/`, excluding
   `repo/status`.
6. Publish the APK to the GitHub Release and commit the channel files.

## Manual regeneration

```bash
cd Android/fdroid-repo
cp /path/to/Fathom-0.2.0.apk repo/
$EDITOR metadata/uk.ewancroft.fathom.yml
fdroid update --clean
rm -rf ../../website/fdroid/repo
cp -R repo ../../website/fdroid/repo
rm -rf ../../website/fdroid/repo/status
```

`fdroid` 2.4.5 is installed locally. The repository URL is in `config.yml`;
changing it changes the index's advertised address and should be done only
together with a site move.

## Icon extraction

fdroidserver extracts a raster `res/mipmap-*/ic_launcher.png`; it does not
render an adaptive-icon XML. Fathom therefore ships PNG fallbacks at every
density in addition to the adaptive icon. If `repo/icons-640/` is empty after
`fdroid update -v`, check that those PNGs survived resource shrinking before
changing anything else. The artwork source is `website/icon.png`.

## Verification

`repo/index-v1.jar` is signed with `keystore.p12`. The website install link
includes the public SHA-256 fingerprint so F-Droid can verify the index. The
publisher mirrors the repo; it does not invent a second index.
