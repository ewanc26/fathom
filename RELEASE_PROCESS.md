# Releasing Fathom

This is the first release path: it produces signed Android and iOS artifacts
from one version source and publishes them on a GitHub Release when a tag
matches. It does **not** upload to Play or App Store Connect yet, and no signed
tester build has been published. The first internal build remains the
acceptance test for #24.

The protected `release` environment exists with a required reviewer. Its
signing secrets are still unset, so a tag currently stops at the environment
check rather than claiming a release.

## One version source

[`version.properties`](../version.properties) is the source:

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

## Signing material

Nothing under version control signs a release. Certificates, profiles,
keystores and passwords live outside the repository; `.gitignore` also blocks
common accidental copies.

### GitHub environment

The release workflow uses the protected `release` environment. Create it in
**Settings → Environments** and add required reviewers. Add these environment
secrets:

| Secret | Android / iOS | Meaning |
|--------|---------------|---------|
| `ANDROID_KEYSTORE_BASE64` | Android | Base64 of the upload keystore (`.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | Android | Keystore password |
| `ANDROID_KEY_ALIAS` | Android | Upload key alias |
| `ANDROID_KEY_PASSWORD` | Android | Upload key password |
| `IOS_CERTIFICATE_BASE64` | iOS | Base64 of the Apple Distribution certificate (`.p12`) |
| `IOS_CERTIFICATE_PASSWORD` | iOS | Password for the `.p12` |
| `IOS_PROVISIONING_PROFILE_BASE64` | iOS | Base64 of the App Store provisioning profile |
| `IOS_TEAM_ID` | iOS | Apple Developer team ID |
| `IOS_PROVISIONING_PROFILE_SPECIFIER` | iOS | Profile name installed by the workflow |

Encode a file on macOS with:

```bash
base64 -i path/to/file -o path/to/file.base64
```

The Apple certificate must be an **Apple Distribution** certificate, and the
profile must match `uk.ewancroft.fathom` and contain that certificate. The
workflow imports both into a temporary keychain and removes it afterwards.

## Build locally

Set the version first, then run the platform script. Both scripts run
`sync-version.py --check`, so an un-synced version fails before a build starts.

### Android

```bash
export FATHOM_KEYSTORE_FILE="$PWD/release-upload.jks"
export FATHOM_KEYSTORE_PASSWORD='…'
export FATHOM_KEY_ALIAS='…'
export FATHOM_KEY_PASSWORD='…'
./scripts/release-android.sh
```

The script runs the shared and Android tests, builds `bundleRelease` and
`assembleRelease`, verifies the AAB with `jarsigner` and the APK with
`apksigner`, and writes to `build/release/android/`:

- `Fathom-<version>-<build>.aab` for Play
- `Fathom-<version>-<build>.apk` for side-loading
- `SHA256SUMS`

`Android/keystore.properties` with `storeFile`, `storePassword`, `keyAlias`
and `keyPassword` is supported as an alternative to the environment.

### iOS

Install the distribution certificate in the login keychain and the
provisioning profile in `~/Library/MobileDevice/Provisioning Profiles`, then:

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

## Build and publish in CI

The workflow is **Release**. It has two entry points:

- **Tag push:** `git tag v0.2.0 && git push origin v0.2.0` checks that the tag
  is exactly `v` plus `versionName`, builds both platforms, and publishes a
  GitHub Release with the AAB, APK, IPA and checksums. The release waits on the
  `release` environment's reviewer.
- **Manual dispatch:** **Actions → Release → Run workflow** builds the same
  artifacts and uploads them as two 14-day artifacts, but deliberately does
  not publish a release. Use it to test signing before creating a tag.

The tag check fails before any signing secret is touched when the tag and
`version.properties` disagree. It also fails a tag such as `v0.2.0-preview`;
preview tags are not release tags.

Download artifacts from a manual run with:

```bash
gh run download <run-id> --repo ewanc26/fathom
```

A successful tag run creates the GitHub Release automatically. The store
uploads remain manual because the workflow has no Play or App Store Connect
credentials.

## Upload to internal testing

### Play internal testing

1. Open Play Console → Fathom → Testing → Internal testing.
2. Create a release and upload the `.aab` from `android-release`.
3. Add the internal tester list, roll out, and open the opt-in link on a
   tester's device.
4. Check that the release build and the debug build coexist; the debug
   application ID ends in `.debug`.

### TestFlight internal testing

App Store Connect needs the app record, a privacy policy URL and the internal
tester group before the first upload. The privacy and support pages are still
tracked by #16 and #15.

Upload with an App Store Connect API key (the `.p8` belongs in
`~/.appstoreconnect/private_keys/AuthKey_<key-id>.p8`):

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
- [ ] Confirm `python3 scripts/sync-version.py --check` passes.
- [ ] Run shared tests, Android tests and build, and the iOS `FathomTests`.
- [ ] Build signed artifacts with a manual **Release** dispatch first, or
      run the local scripts, and confirm the signatures.
- [ ] Smoke-test on a real account: enter a handle, check Now, Stats,
      History and Profile; search History and jump to a month; open About and
      confirm the version; refresh once; run **Read everything**; disconnect
      and confirm the saved history is gone.
- [ ] Create and push the matching tag (`v<versionName>`); wait for the
      protected environment approval and the GitHub Release.
- [ ] Upload the AAB to Play internal testing and the IPA to TestFlight.
- [ ] Verify both stores show the same version and build number, and that
      About shows the same build.
- [ ] Update the install links on the website once signed builds exist (#24).
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

If a signing key is exposed, stop: revoke the certificate or request an upload
key reset, rotate the GitHub secret, and rebuild. A Play upload-key reset and
an Apple certificate revocation are both store-level operations with waiting
periods, so treat the secrets as production credentials.

## Known gaps before a public listing

- The `release` environment's signing secrets are not configured yet: the
  Android upload keystore and the Apple Distribution certificate and profile.
  Until they exist, both release jobs fail at their secret check.
- The Play Console app and internal-testing track, and the App Store Connect
  app record and internal tester group, still have to exist.
- Store listing copy, support URL and screenshots from
  `docs/images/screens/`.
- Automated Play/TestFlight uploads. This workflow stops at signed artifacts
  and a GitHub Release; uploading is manual until the first internal build is
  proven.
- The website's custom-domain DNS (`fathom.ewancroft.uk`) is documented in
  `website/README.md`; the Pages workflow publishes whether or not the CNAME
  has resolved yet.
