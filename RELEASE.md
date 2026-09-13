# Latest release

<!-- CURRENT_VERSION:START -->
**Current version: unreleased**
<!-- CURRENT_VERSION:END -->

- [Release history](https://github.com/wwahmed/WakiDownload/releases)
- [Install steps](INSTALL.md)

## How releases are published (invariant)

Always publish with **`scripts/publish.sh <version> <apk> [notes.md]`**, never a bare
`gh release create`. It guarantees, then verifies:

1. **Three assets** on every release: `WakiDownload-vX.Y.Z.apk`, an unversioned copy named exactly
   `WakiDownload.apk` (the stable `releases/latest/download/WakiDownload.apk` URL matches on asset
   name, so a release without it makes that URL 404), and `latest.json`, the updater manifest.
2. The APK is signed with the release certificate `b155b935…32c8` (full digest in the script), its
   `versionName` matches the tag, its `versionCode` beats the last published one, and the updater
   contract (permission + receiver) is inside the APK.
3. `main` is pushed and the tag points at the shipped commit; the docs' version blocks carry the
   real SHA-256.
4. **Post-publish verification** re-reads the API: latest == this tag, both APK assets download
   with the local digest, and the anonymous stable URLs for the APK and `latest.json` serve the new
   bytes. Any miss exits non-zero: do not announce.

The repo is public so the phone can read `latest.json` and the APK without a credential. Nothing
secret is in the tree; the keystore lives outside it.
