# Releasing Fathom

This is the first release path: it is set up to produce signed Android and
iOS artifacts from one version source. It does **not** upload to Play or App Store Connect
yet, and no tester build has been published. The first internal build remains
the acceptance test for #24.

## One version source

[`version.properties`](../version.properties) is the source:

```properties
versionName=0.1.0
versionCode=8
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

## Build in CI

Run **Actions → Release artifacts → Run workflow** from the commit to release.
The workflow uses the `release` environment, so it waits for its reviewers.
It uploads two 14-day artifacts:

- `android-release` — AAB, APK and checksums
- `ios-release` — IPA and checksums

Download them with:

```bash
gh run download <run-id> --repo ewanc26/fathom
```

The workflow is manual on purpose. It has no store credentials and cannot
publish a build by itself.

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
- [ ] Build signed artifacts with the release workflow or the local scripts.
- [ ] Smoke-test on a real account: enter a handle, check Now, Stats,
      History and Profile; refresh once; run **Read everything**; disconnect
      and confirm the saved history is gone.
- [ ] Upload the AAB to Play internal testing and the IPA to TestFlight.
- [ ] Verify both stores show the same version and build number.
- [ ] Update the install links on the website once the site exists (#15).
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

- Privacy policy and terms (#16), and the public website (#15).
- Store listing copy, support URL and screenshots from
  `docs/images/screens/`.
- Automated Play/TestFlight uploads. This workflow stops at signed artifacts;
  uploading is manual until the first internal build is proven.
- The apps do not show their version or an About section yet (#20), so the
  store is currently the only place a tester can confirm the build.
