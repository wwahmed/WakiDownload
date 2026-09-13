# WakiDownload

Android app, one job: share a link or a file to it and the video or pictures land where you want
them. Twitter / X and TikTok links are resolved to their media (TikTok without the watermark);
any other link is saved if it points at a picture, a video, or a page with one; any file shared
from any app (WhatsApp, a browser, Photos) is saved as is. Targets: `Downloads/WakiDownload/`,
Google Drive, OneDrive, or any folder picked in the Files app. Side-loaded, self-updating from
GitHub Releases, no analytics.

<!-- CURRENT_VERSION:START -->
**Current version: v0.2.0** · SHA-256 `ea76fd34f0896afb1c90bd9b5736823c53a686b2ebf85ce228607c1234e501ca`

- [Latest APK (always newest)](https://github.com/wwahmed/WakiDownload/releases/latest/download/WakiDownload.apk) · [release notes](https://github.com/wwahmed/WakiDownload/releases/latest)
- [v0.2.0 APK (this exact version)](https://github.com/wwahmed/WakiDownload/releases/download/v0.2.0/WakiDownload-v0.2.0.apk) · [v0.2.0 notes](https://github.com/wwahmed/WakiDownload/releases/tag/v0.2.0)
<!-- CURRENT_VERSION:END -->

## Use it

1. In Twitter / X or TikTok, tap Share and pick **WakiDownload**.
2. A small card shows progress and closes itself when the file is saved. Hide it any time; the
   download continues with a progress notification.
3. The completion notification opens the file; **Show in Files** opens the Downloads folder.

Files are named `<source>-<yyyymmdd-hhmmss>-<short-id>.<ext>`, for example
`tiktok-20260912-203015-511711.mp4`. A tweet with several pictures gets `-1`, `-2`, ... before the
extension.

The share card asks where to save (chips for every configured location) and has an **NSFW**
checkbox that switches to the NSFW location and adds the NSFW subfolder. Turn "Ask where to save
on every share" off and shares go straight to the per-source default.

The app's own screen has: **Save locations** (connect Google Drive, connect OneDrive, add folders
from the Files app), **Default location per source** (Twitter / X, TikTok, other links, files
shared from apps, NSFW), **Check for updates** (also runs on every cold start), and recent
downloads. Cloud setup, including the one-time OneDrive registration and the Personal Vault
limitation, is in [docs/CLOUD_SETUP.md](docs/CLOUD_SETUP.md).

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
