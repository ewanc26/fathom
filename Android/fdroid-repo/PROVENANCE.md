# F-Droid provenance

Fathom runs its own F-Droid repository. There is no F-Droid build or
reproducible-build claim here: the APK is built and signed on the publisher's
machine, then its exact bytes are placed in `repo/` and the index is signed
with the separate repo key.

- The app is signed by the Fathom release key in `Android/release-upload.jks`.
- `repo/index-v1.jar` is signed by `Android/fdroid-repo/keystore.p12`.
- The website serves a byte-for-byte copy of `repo/` under `/fdroid/repo`.
- The GitHub Release asset is the same APK, with the same SHA-256.

The repo key proves index integrity, not that the APK was built by F-Droid or
by GitHub Actions. The release manifest and provenance attestation cover the
GitHub Release assets; verify the APK against the manifest before installing
it. The source for the build is the commit named by the release tag.
