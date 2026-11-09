# AltStore provenance

The IPA served from `website/altstore/` is built on the publisher's machine
from the commit named by the GitHub Release tag. It is **unsigned**: AltStore
Classic re-signs it with the user's Apple ID and a development provisioning
profile when it installs the app.

That means:

- The SHA-256 in `source.json` identifies the exact unsigned IPA hosted here.
- The installed app's signature is created by AltStore Classic, not by this
  project. It will not be the same bytes the user downloaded.
- The GitHub Release asset is the same unsigned IPA, not an App Store export.
- No Apple Distribution certificate or App Store provisioning profile is used
  for this channel.

The release manifest records the SHA-256 of the published IPA. Verify a local
download against that manifest before installing it. Do not expect a
`codesign` signature on the hosted file.
