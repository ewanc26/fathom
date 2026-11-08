# Releasing Fathom

Fathom follows Inkwell's release split: **the signed binaries are built on the
publisher's machine, and GitHub Actions only records provenance for the exact
bytes that were published.** No signing key, certificate, profile or password
is stored in GitHub.

One version is one unified GitHub Release tagged `v<version>`, named
`Fathom <version>`, with the platform artefacts that exist:

- `Fathom-<version>.apk` for Android
- `Fathom-<version>.ipa` for iOS

The Play upload bundle (`.aab`) is a store artefact, not a GitHub Release
asset. The release workflow then attaches:

- `sbom-android-v<version>.spdx.json`
- `sbom-ios-v<version>.spdx.json`
- `release-manifest-v<version>.json`
- a build-provenance attestation for the manifest and SBOMs

The existing `preview-0.2.0` debug APK predates this contract. It remains a
development preview; it is not a `v*` release and is not store-ready.

## One version source

[`version.properties`](version.properties) is the only version source:

```properties
versionName=0.2.0
versionCode=9
```

- Android reads it directly in `Android/app/build.gradle.kts`.
- iOS is synchronised into `iOS/project.yml` and `iOS/Info.plist`:

```bash
python3 scripts/sync-version.py
```

- `python3 scripts/sync-version.py --check` runs in CI. Never edit the iOS
  version fields by hand.
- `versionCode` is both the Android version code and the iOS build number.
  Every uploaded build needs a higher number on both stores. Reusing one is
  rejected by the stores and cannot be undone.
- The release tag is exactly `v` plus `versionName`. The provenance workflow
  rejects tags that are not `vX.Y.Z`.

## Publish locally

The publisher is dry-run by default. Nothing is built, signed or uploaded
until `--yes` is passed. Run it from `main` with the release commit already
pushed to `origin/main`; it refuses a dirty tree or an unpushed commit.

```bash
node tools/release/publish.mjs status
node tools/release/publish.mjs android --yes --notes-file release-notes.md
node tools/release/publish.mjs ios --ipa /path/to/Fathom-0.2.0-9.ipa --yes --notes-file release-notes.md
```

`status` is read-only and shows whether the APK and IPA for the source version
are attached to the release.

When a release does not exist, the first publish creates it and the matching
tag from the pushed `main` HEAD. Later platform uploads attach to the same
release. If `--notes-file` is omitted, the publisher drafts a starting point
from commit subjects; that draft is not finished release copy. A notes file is
only used when the release is created. Edit an existing release body with:

```bash
gh release edit v0.2.0 --notes-file release-notes.md
```

`--skip-existing` makes a rerun a no-op when that exact platform asset is
already attached.

### Signing material

Nothing under version control signs a release, and nothing in GitHub Actions
does either. The old protected `release` environment and its secret names are
no longer used and can be deleted.

#### Android

Set these four variables for the publisher, or use the gitignored
`Android/keystore.properties` with `storeFile`, `storePassword`, `keyAlias`
and `keyPassword`:

```bash
export FATHOM_KEYSTORE_FILE="$PWD/release-upload.jks"
export FATHOM_KEYSTORE_PASSWORD='…'
export FATHOM_KEY_ALIAS='…'
export FATHOM_KEY_PASSWORD='…'
```

`tools/release/publish.mjs android` calls
[`scripts/release-android.sh`](scripts/release-android.sh). That script runs
the shared and Android tests, builds `bundleRelease` and `assembleRelease`,
verifies the AAB with `jarsigner` and the APK with `apksigner`, and writes to
`build/release/android/`:

- `Fathom-<version>-<build>.aab` for Play
- `Fathom-<version>-<build>.apk`, copied to the release name
  `Fathom-<version>.apk`
- `SHA256SUMS` for the local files; the GitHub asset is the manifest, not this
  file

The publisher independently checks that the APK is not signed with the Android
debug key before uploading it.

#### iOS

Install the Apple Distribution certificate in the login keychain and the App
Store provisioning profile in
`~/Library/MobileDevice/Provisioning Profiles`, then export the IPA:

```bash
export IOS_TEAM_ID='…'
export IOS_PROVISIONING_PROFILE_SPECIFIER='Fathom App Store'
./scripts/release-ios.sh
```

The script rebuilds the XCFramework, regenerates the Xcode project, archives
with manual signing, exports an App Store Connect IPA and checks that the
IPA's `CFBundleShortVersionString` and `CFBundleVersion` match
`version.properties`. Output is `build/release/ios/Fathom-<version>-<build>.ipa`
plus `SHA256SUMS`.

Pass that IPA to `publish.mjs ios`. The publisher verifies its embedded
version and build number, copies it to `Fathom-<version>.ipa`, and uploads it.
Xcode archiving needs an interactive keychain, so the publisher deliberately
does not build the IPA itself.

## Provenance in CI

[`.github/workflows/release.yml`](.github/workflows/release.yml) is named
**Release provenance**. It does **not** build, sign or publish an APK/IPA. It
runs on:

- a `v*` tag push
- a GitHub Release being published
- manual dispatch with a published tag input

It checks out the tag, generates an Android dependency SBOM with
`anchore/sbom-action`, generates the iOS SBOM, downloads the **exact** APK/IPA
already attached to the release, hashes them, writes the release manifest,
attests the manifest and SBOMs, and uploads those three files.

The iOS target has no Swift Package Manager dependencies; its SBOM records that
explicitly. The shared Kotlin framework's Gradle dependencies are covered by
the Android SBOM.

The workflow refuses to run until the release exists and has at least one
binary. If a platform is added after the first publish, the publisher queues a
fresh provenance run once both assets are present; you can also run
**Actions → Release provenance → Run workflow** with the tag.

### Why the binaries are not attested

The APK and IPA are assembled and signed outside Actions. Attesting a CI
rebuild would describe different bytes from the ones users download. Instead,
the manifest records the SHA-256 of the exact published assets, and the
workflow attests the manifest and SBOMs it genuinely produced. The manifest's
`provenance.binariesBuiltInActions` field is `false`.

### Verify a release

```bash
# 1. Download the manifest and SBOMs, then verify their attestation.
gh release download vX.Y.Z --repo ewanc26/fathom \
  --pattern "release-manifest-*.json" --pattern "sbom-*.spdx.json"
gh attestation verify release-manifest-vX.Y.Z.json --owner ewanc26
gh attestation verify sbom-android-vX.Y.Z.spdx.json --owner ewanc26
gh attestation verify sbom-ios-vX.Y.Z.spdx.json --owner ewanc26

# 2. Confirm the binary you downloaded matches the hash in the manifest.
shasum -a 256 Fathom-X.Y.Z.apk
shasum -a 256 Fathom-X.Y.Z.ipa
```

Do not run `gh attestation verify` against the APK or IPA themselves: they
were not built in Actions and have no matching attestation. The manifest's
`.artifacts.apk.sha256` and `.artifacts.ipa.sha256` are the link between the
attested document and the binary.

## Store handoff

The publisher stops at the GitHub Release. Store uploads remain manual.

### Play internal testing

1. Open Play Console → Fathom → Testing → Internal testing.
2. Create a release and upload the `.aab` from
   `build/release/android/`.
3. Add the internal tester list, roll out, and open the opt-in link on a
   tester's device.
4. Check that the release build and the debug build coexist; the debug
   application ID ends in `.debug`.

### TestFlight internal testing

App Store Connect needs the app record, a privacy policy URL and the internal
tester group before the first upload. Upload with:

```bash
xcrun altool --upload-app \
  --type ios \
  --file build/release/ios/Fathom-<version>-<build>.ipa \
  --apiKey "$ASC_KEY_ID" \
  --apiIssuer "$ASC_ISSUER_ID"
```

Alternatively, open the IPA in Transporter. Wait for processing in App Store
Connect, add it to the internal tester group, and verify the TestFlight build
number matches `version.properties`.

## Release checklist

- [ ] Pick a version. `versionName` changes only for a marketing release;
      `versionCode` increases for every uploaded build.
- [ ] Edit `version.properties`, run `python3 scripts/sync-version.py`, and
      commit the generated `iOS/project.yml` and `iOS/Info.plist` changes.
- [ ] Update `CHANGELOG.md` and prepare the release notes.
- [ ] Confirm `python3 scripts/sync-version.py --check` passes.
- [ ] Run shared tests, Android tests and build, and the iOS `FathomTests`.
- [ ] Export the signed IPA and build the signed APK locally. Confirm
      `apksigner` shows the upload key, not the debug key.
- [ ] Smoke-test on a real account: enter a handle, check Now, Stats,
      History and Profile; search History and jump to a month; open About and
      confirm the version; refresh once; run **Read everything**; disconnect
      and confirm the saved history is gone.
- [ ] Run `node tools/release/publish.mjs android --yes --notes-file <file>`
      and `node tools/release/publish.mjs ios --ipa <file> --yes`.
- [ ] Wait for **Release provenance** and confirm the manifest and both SBOMs
      are attached.
- [ ] Upload the AAB to Play internal testing and the IPA to TestFlight.
- [ ] Verify both stores show the same version and build number, and that
      About shows the same build.
- [ ] Update the install links on the website once signed builds exist.
- [ ] Record the commit, artifact hashes and tester links in the release notes.

## Rollback

There is no downgrade path on either store; roll back by publishing a new,
higher build.

- **Play:** halt the internal rollout. Existing testers keep the last build
  they installed. Fix the problem, raise `versionCode`, rebuild and upload a
  new AAB. Never reuse a version code.
- **TestFlight:** expire the bad build or remove it from the tester group.
  Raise the build number, upload a replacement and add it to the group. Apple
  does not accept a reused build number.
- **Bad artifact, not bad code:** rebuild from the same commit with a higher
  `versionCode`; do not overwrite a downloaded artifact or a store upload.
- **Bad commit:** revert it in a new commit, raise `versionCode` again and
  rebuild. Do not move or reuse a release tag.
- **Bad release notes:** edit the GitHub Release; provenance does not need to
  be rerun unless the binaries or SBOMs changed.
- **Signing key exposed:** stop, revoke the certificate or request an upload
  key reset, replace the local key, and rebuild. Both are store-level
  operations with waiting periods.

## Known gaps

- The local machine has no Apple Distribution certificate or App Store
  provisioning profile, and no Android upload keystore. No signed `v0.2.0`
  release has been published; only the debug preview exists.
- The Play Console app and internal-testing track, and the App Store Connect
  app record and internal tester group, still have to exist.
- Store listing copy, support URL and screenshots from
  `docs/images/screens/`.
- Automated Play/TestFlight uploads. The publisher stops at signed local
  artifacts and a GitHub Release; store uploads are manual until the first
  internal build is proven.
- The website's custom-domain DNS (`fathom.ewancroft.uk`) is documented in
  `website/README.md`; the Pages workflow publishes whether or not the CNAME
  has resolved yet.
