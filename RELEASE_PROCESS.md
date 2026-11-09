# Releasing Fathom

Fathom follows Inkwell's split: **the binaries are built on the publisher's
machine, and GitHub Actions only records provenance for the exact bytes that
were published.** No signing key, password or profile is stored in GitHub.

AltStore Classic and a self-hosted F-Droid repo are the first distribution
routes. The App Store and Google Play are planned, not live. The website and
both channel files are served by Vercel at `https://fathom-app-delta.vercel.app`;
`fathom.ewancroft.uk` is attached to the same project and remains the canonical
app and legal origin while its DNS record is pending.

One version is one unified GitHub Release tagged `v<version>`, named
`Fathom <version>`, with:

- `Fathom-<version>.apk` — signed with the Fathom release key;
- `Fathom-<version>.ipa` — unsigned, for AltStore Classic to re-sign.

The release workflow then attaches:

- `sbom-android-v<version>.spdx.json`
- `sbom-ios-v<version>.spdx.json`
- `release-manifest-v<version>.json`
- a build-provenance attestation for the manifest and SBOMs.

The old `preview-0.2.0` debug APK is a development preview, not a `v*` release.

## One version source

[`version.properties`](version.properties) is the only version source:

```properties
versionName=0.2.1
versionCode=10
```

- Android reads it directly in `Android/app/build.gradle.kts`.
- iOS is synchronised into `iOS/project.yml` and `iOS/Info.plist`:

```bash
python3 scripts/sync-version.py
```

- `python3 scripts/sync-version.py --check` runs in CI. Never edit the iOS
  version fields by hand.
- `versionCode` is both the Android version code and the iOS build number.
  Raise it for every uploaded build; never reuse one.
- The release tag is exactly `v` plus `versionName`. The provenance workflow
  rejects anything else.

## One-time signing setup

Run this once on the publisher's machine:

```bash
./scripts/setup-release-keys.sh
```

It creates two independent 4096-bit RSA keys and writes only gitignored files:

| File | Purpose |
|------|---------|
| `Android/release-upload.jks` | Signs the APK users install |
| `Android/keystore.properties` | Its local password configuration |
| `Android/fdroid-repo/keystore.p12` | Signs the F-Droid repo index |
| `Android/fdroid-repo/config.yml` | fdroidserver configuration and repo password |

Back up all four in a password manager or encrypted archive. Losing the APK key
prevents upgrades; losing the repo key prevents index updates. Neither can be
recovered from GitHub. Do not reuse Inkwell's keys.

The AltStore IPA needs no Apple Distribution certificate or provisioning
profile because AltStore Classic signs it on the user's device.

## Publish

The publisher is dry-run by default. Run it from `main` with the release commit
already pushed to `origin/main`; it refuses a dirty tree or an unpushed commit.
Use `--push` so the F-Droid repo, AltStore source and website assets are pushed
after publication.

```bash
node tools/release/publish.mjs status
node tools/release/publish.mjs android --yes --push --notes-file release-notes.md
node tools/release/publish.mjs ios --yes --push --notes-file release-notes.md
```

`status` shows the source version, GitHub Release assets, AltStore version and
F-Droid version.

When the release does not exist, the first publish creates it and the matching
tag from the pushed `main` HEAD. Later platform uploads attach to the same
release. A notes file is only used when the release is created; edit an existing
release with:

```bash
gh release edit v0.2.1 --notes-file release-notes.md
```

`--skip-existing` makes a rerun a no-op when that platform asset is already
attached.

### Android and F-Droid

[`scripts/release-android.sh`](scripts/release-android.sh) runs the shared and
Android tests, builds `bundleRelease` and `assembleRelease`, verifies the AAB
with `jarsigner` and the APK with `apksigner`, and writes to
`build/release/android/`:

- `Fathom-<version>-<build>.aab` for Play, not a release asset;
- `Fathom-<version>-<build>.apk`;
- `SHA256SUMS` for local use.

The publisher independently rejects a debug-signed APK, then:

1. copies the APK to `Android/fdroid-repo/repo/Fathom-<version>.apk`;
2. updates `CurrentVersion` and `CurrentVersionCode` in the metadata;
3. runs `fdroid update --clean` in `Android/fdroid-repo/`;
4. mirrors `repo/` to `website/fdroid/repo/`, excluding `repo/status`;
5. uploads `Fathom-<version>.apk` to the GitHub Release;
6. commits the metadata, repo and website mirror, then pushes with `--push`.

The F-Droid install link includes the repo index fingerprint
`DE922C5717CAB6C0A48AF84B5D0EBC72302308023DE7AC770BA68911F771BB29`. The app
signing key is separate from the repo key; neither is committed.

### iOS and AltStore Classic

[`scripts/build-altstore-ipa.sh`](scripts/build-altstore-ipa.sh) builds the
XCFramework, regenerates the Xcode project, archives with code signing disabled,
packages `Payload/Fathom.app` into an IPA, and checks the bundle identifier and
version. Output is
`build/release/ios-altstore/Fathom-<version>-<build>.ipa`.

The publisher then:

1. verifies the IPA reports the version and build from `version.properties`;
2. copies it to `website/altstore/Fathom-<version>.ipa`;
3. prepends a `versions` entry to `iOS/altstore/source.json` with the date,
   size, SHA-256, build number, minimum iOS version and download URL;
4. updates the app-level latest fields and mirrors the file byte-for-byte to
   `website/altstore/source.json`;
5. validates the source and mirror with
   [`tools/release/validate-altstore.mjs`](tools/release/validate-altstore.mjs);
6. uploads `Fathom-<version>.ipa` to the GitHub Release;
7. commits the source and website assets, then pushes with `--push`.

This is an **AltStore Classic** source. Do not add `marketplaceID`, an ADP
manifest or a notarisation step. The hosted IPA is intentionally unsigned;
AltStore Classic re-signs it with the user's Apple ID.

An App Store export still needs the Apple Distribution certificate and profile:

```bash
export IOS_TEAM_ID='…'
export IOS_PROVISIONING_PROFILE_SPECIFIER='Fathom App Store'
./scripts/release-ios.sh
```

Keep that path separate from the AltStore source.

## Website hosting and custom domain

Vercel deploys the repository as project `fathom-app`, serving `website/` as a
static site. The working project URL is `https://fathom-app-delta.vercel.app`. The
custom domain `fathom.ewancroft.uk` is attached to the project, but its CNAME
record is still pending. In Cloudflare, add `CNAME fathom` →
`3012d949f736e19c.vercel-dns-017.com` with the proxy disabled (DNS only).

When the CNAME resolves, switch the generated channel URLs together:

```bash
export FATHOM_SITE_BASE='https://fathom.ewancroft.uk'
```

Regenerate the AltStore source and F-Droid index with the publisher, and update
the links in `website/index.html` and `website/README.md`. Do not change only the
mirror: the index advertises its own repository address. The canonical links in
`website/client-metadata.json`, the app's About screen and the legal pages can
stay on `fathom.ewancroft.uk` throughout.

## Provenance in CI

[`.github/workflows/release.yml`](.github/workflows/release.yml) is named
**Release provenance**. It does **not** build, sign or publish an APK/IPA. It
runs on a `v*` tag push, a published release, or manual dispatch with a
published tag.

It checks out the tag, generates an Android dependency SBOM with
`anchore/sbom-action`, generates the iOS SBOM, downloads the exact APK/IPA
already attached to the release, hashes them, writes the release manifest,
attests the manifest and SBOMs, and uploads those three files.

The workflow refuses to run until the release exists and has at least one
binary. If a platform is added after the first publish, the publisher queues a
fresh provenance run once both assets are present; you can also run
**Actions → Release provenance → Run workflow** with the tag.

### Verify a release

```bash
gh release download vX.Y.Z --repo ewanc26/fathom \
  --pattern "release-manifest-*.json" --pattern "sbom-*.spdx.json"
gh attestation verify release-manifest-vX.Y.Z.json --owner ewanc26
gh attestation verify sbom-android-vX.Y.Z.spdx.json --owner ewanc26
gh attestation verify sbom-ios-vX.Y.Z.spdx.json --owner ewanc26

shasum -a 256 Fathom-X.Y.Z.apk
shasum -a 256 Fathom-X.Y.Z.ipa
```

Do not run `gh attestation verify` against the APK or IPA themselves: they were
not built in Actions. The manifest's `.artifacts.*.sha256` values link the
attested document to the binaries.

## Store handoff

### AltStore Classic

The source is live at
`https://fathom-app-delta.vercel.app/altstore/source.json`. Add it in AltStore
Classic with **Sources → +**. The hosted IPA is re-signed by AltStore, so the
installed signature is not the hosted file's signature.

### F-Droid

The repo is live at `https://fathom-app-delta.vercel.app/fdroid/repo`. Add it by
link or QR code in F-Droid; the fingerprint is in the website install link.

### Play internal testing

1. Open Play Console → Fathom → Testing → Internal testing.
2. Upload the `.aab` from `build/release/android/`.
3. Add the internal tester list, roll out, and open the opt-in link.
4. Check that the release build and the debug build coexist; the debug
   application ID ends in `.debug`.

### TestFlight internal testing

App Store Connect needs the app record, a privacy policy URL and an internal
tester group. Upload the App Store export with:

```bash
xcrun altool --upload-app \
  --type ios \
  --file build/release/ios/Fathom-<version>-<build>.ipa \
  --apiKey "$ASC_KEY_ID" \
  --apiIssuer "$ASC_ISSUER_ID"
```

Alternatively, open the IPA in Transporter.

## Release checklist

- [ ] Pick a version. `versionName` changes only for a marketing release;
      `versionCode` increases for every uploaded build.
- [ ] Edit `version.properties`, run `python3 scripts/sync-version.py`, and
      commit the generated `iOS/project.yml` and `iOS/Info.plist` changes.
- [ ] Update `CHANGELOG.md` and prepare `release-notes.md`.
- [ ] Confirm `python3 scripts/sync-version.py --check` passes.
- [ ] Run the shared tests, Android tests and iOS `FathomTests`.
- [ ] Smoke-test on a real account: Now, Stats, History, search, month jump,
      About version, refresh, read everything, disconnect and confirm the saved
      history is gone. A physical device is still outstanding for 0.2.1.
- [ ] Run the Android publisher with `--yes --push`.
- [ ] Run the iOS publisher with `--yes --push`.
- [ ] Confirm the F-Droid repo includes the APK and the AltStore source's
      `versions[0]` matches `version.properties`.
- [ ] Wait for **Release provenance** and confirm the manifest and both SBOMs
      are attached.
- [ ] Verify the install links on the live Vercel site.
- [ ] Upload the AAB to Play and the App Store IPA to TestFlight when those
      records exist.

## Rollback

There is no downgrade path in either channel; roll back by publishing a new,
higher build.

- **AltStore:** publish a new IPA with a higher build number. Users can install
  it over the old one. Editing `versions[0]` backwards is not a rollback.
- **F-Droid:** publish a new APK with a higher `versionCode`. Do not replace an
  APK under the same version code; F-Droid clients cache signatures and
  indexes.
- **Bad artifact, not bad code:** rebuild from the same commit with a higher
  `versionCode`; do not overwrite a downloaded artifact.
- **Bad commit:** revert it, raise `versionCode`, rebuild. Do not move or reuse
  a release tag.
- **Bad release notes:** edit the GitHub Release; provenance does not need to be
  rerun unless the binaries or SBOMs changed.
- **Signing key exposed:** stop publishing. Replace the APK key with a new
  package identity or coordinate an upgrade path; replace the F-Droid repo key
  with a new repo URL and ask users to re-add it. Both are disruptive and have
  no remote recovery.

## Known gaps

- No App Store or Google Play listing. Their app records, Apple Distribution
  certificate/profile and Play upload key are separate work.
- No physical-device smoke test has been done; 0.2.1 is emulator and simulator
  verified.
- The custom domain `fathom.ewancroft.uk` is attached to Vercel but its
  Cloudflare CNAME is not set yet, so the live channel URLs use the Vercel
  project URL.
- Store uploads are manual.
