# Polish rounds and ship receipts

One entry per release: what shipped, how it was verified, what was NOT verified.

## v0.1.0 (2026-09-13 00:36 UTC), first release

**Shipped.** Share-to-download for Twitter / X (syndication endpoint, highest-bitrate mp4, photos
at original size, GIFs as mp4) and TikTok (web-page `playAddr`, no watermark; aweme tier logged
dead, tikwm fallback), plus any other link (direct media or og:video / og:image) and any file
shared from any app. Four target kinds: Downloads/WakiDownload (MediaStore), folders picked in the
Files app (SAF), Google Drive (Play Services authorization, `drive.file`, resumable upload) and
OneDrive (OAuth code + PKCE, Graph upload session). Share card with location chips and NSFW
checkbox; per-source defaults and an NSFW location + subfolder in settings; "ask each time"
switch. Self-updater: `latest.json` from GitHub Releases on cold start and on tap, verified
digest / package / versionCode / certificate pin before PackageInstaller.

**Verification on the `wakidrive` emulator (API 35, arm64), release-signed APK.**
- Twitter video share: `twitter-20260912-202844-469056.mp4`, 1920x1080 h264, 3,139,253 bytes,
  in `Download/WakiDownload/`.
- TikTok video share: `tiktok-20260912-203002-511711.mp4`, 720x1280, 5,122,743 bytes, SHA-256
  identical to the clip fetched on the Mac whose frames at 3 s and 90 s show no watermark
  (`qa/screenshots/2026-09-12-v0.1.0/07-*.png`, `08-*.png`). Tier 1 logged its failure and tier 2
  answered, as designed.
- Generic link (`httpbin.org/image/jpeg` shared with surrounding text): `web-…-b6a1f0.jpg`.
- File shared from the Photos app through the system share sheet: `shared-…-933989.png`, byte
  count equal to the source.
- NSFW checkbox on a photo tweet: `Download/WakiDownload/NSFW/twitter-…-078720.jpg`.
- Custom folder target (picker, Documents): `Documents/twitter-…-469056.mp4`.
- Google Drive Connect: Play Services authorization flow opened (add-account screen on the
  account-less emulator). OneDrive: row reads "Needs setup" because the Entra client id is not
  configured yet.
- Cold start after publish: "You are on the latest version (0.1.0)" from the live manifest.
- Unit tests: 28 run, 0 failed (link classifier, file names, Twitter token + parsers, TikTok
  parsers, generic og parser, PKCE vector).

**Ship receipt.** `scripts/publish.sh 0.1.0` real exit 0 (output redirected, no pipe). main pushed
at `7cb68bc5`, `origin/main..HEAD` 0. Release `v0.1.0` is latest with `WakiDownload.apk`,
`WakiDownload-v0.1.0.apk`, `latest.json`; both APK assets re-downloaded by asset id
(560244718, 560244721) and by the anonymous stable URL, all with digest
`6255edb8884a66861590412166c94801bc05515775b8d2f4a707f96a949a0e07` (14,718,894 bytes,
versionCode 1). Signing certificate `b155b93522ccf54d35e3a0b5a268a36e766413f679e44e0dd34e68b33ff532c8`.
RELEASE.md / README.md / INSTALL.md carry the real digest. Repo public. Freeze to live: 29 s for
the publish step itself; the whole build from empty directory to live took about 52 minutes,
including the mid-build scope additions.

**NOT verified (no accounts on the emulator).** An actual Google Drive upload (needs a signed-in
Google account and, per docs/CLOUD_SETUP.md, possibly the Android OAuth client in waki-brain),
an actual OneDrive upload (needs the Entra registration), and whether the OneDrive app's
provider exposes Personal Vault to the folder picker.
