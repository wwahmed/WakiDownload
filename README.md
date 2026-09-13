# WakiDownload

Android app, one job: share a Twitter / X or TikTok link to it and the video or pictures land in
`Downloads/WakiDownload/`. TikTok videos are saved without the watermark. Side-loaded, self-updating
from GitHub Releases, no accounts, no analytics.

<!-- CURRENT_VERSION:START -->
**Current version: unreleased**
<!-- CURRENT_VERSION:END -->

## Use it

1. In Twitter / X or TikTok, tap Share and pick **WakiDownload**.
2. A small card shows progress and closes itself when the file is saved. Hide it any time; the
   download continues with a progress notification.
3. The completion notification opens the file; **Show in Files** opens the Downloads folder.

Files are named `<source>-<yyyymmdd-hhmmss>-<short-id>.<ext>`, for example
`tiktok-20260912-203015-511711.mp4`. A tweet with several pictures gets `-1`, `-2`, ... before the
extension.

The app's own screen lists recent downloads, has **Check for updates** (also runs on every cold
start) and shows the default folder. Sharing an actual image or video file to WakiDownload copies
it into the same folder.

## Install

See [INSTALL.md](INSTALL.md). Stable download URL:
`https://github.com/wwahmed/WakiDownload/releases/latest/download/WakiDownload.apk`

## Build and ship

- `scripts/build.sh` builds the signed release APK (keystore at `~/.wakilabs-keystores/WakiDownload.jks`).
- `scripts/publish.sh <version> <apk> [notes.md]` is the only ship path. See
  [docs/RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md) and [RELEASE.md](RELEASE.md).
- Decisions and their reasons: [docs/DECISIONS.md](docs/DECISIONS.md). Per-release receipts:
  [docs/POLISH_ROUNDS.md](docs/POLISH_ROUNDS.md).

For personal use. Respect content creators' rights. Do not redistribute downloaded media without permission.
