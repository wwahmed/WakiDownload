# Decisions

Dated, with the reason. Newest at the bottom.

## 2026-09-12 Native Kotlin, Views, no Compose
The app is one screen plus a share card. WakiUsage's native template (AGP 8.5.2, Kotlin 2.0.21,
Gradle 8.7, all in the local Gradle cache) builds in seconds; Compose would add build time and
nothing the screen needs.

## 2026-09-12 Public GitHub repo
The in-app updater and the stable `releases/latest/download/WakiDownload.apk` URL must work with
no credential (no login, no OAuth, no accounts). GitHub serves release assets of a private repo
only to a signed-in browser or an API token, which the app does not have. Nothing secret is in
the tree: the keystore, its password and the OAuth properties live in `~/.wakilabs-keystores/`.
WakiDrive stayed private because it hosts server code; WakiDownload has none.

## 2026-09-12 latest.json as a release asset
GitHub's API does not expose Android's `versionCode`, and Android installs by code. publish.sh
therefore uploads a `latest.json` manifest (version, versionCode, byteSize, apkSha256,
signingCertSha256, versioned apkUrl) beside the APK. The app reads it through the stable
`releases/latest/download/latest.json` URL. The manifest points at the VERSIONED APK asset so the
manifest and the bytes it describes are one authority.

## 2026-09-12 Certificate pin is the app identity
`RELEASE_CERT_SHA256` is compiled into the APK and checked against the downloaded update's
signing certificate before PackageInstaller sees it (plus file SHA-256, package name, versionCode
strictly newer and equal to the manifest). publish.sh refuses to ship an APK signed by anything
else. Regenerating `~/.wakilabs-keystores/WakiDownload.jks` would strand every installed phone.

## 2026-09-12 Twitter: syndication endpoint first, fxtwitter second
`cdn.syndication.twimg.com/tweet-result` is what twitter.com's own embeds call; it needs no key,
only a token derived from the tweet id (react-tweet's formula, base-36 conversion ported from
V8 so the digits match a browser). It returns every mp4 rendition; the highest bitrate is taken.
fxtwitter is a free community mirror kept as the fallback. Twitter GIFs are mp4 on the wire and
are saved as mp4 (tagged GIF in history); converting to a real GIF would lose quality and gain
nothing.

## 2026-09-12 TikTok: three tiers, web page is the one that works
Waqas asked for the aweme detail API (`play_addr`) first. Probed on ship day it answered
302 then 504 (`www.tiktok.com/aweme/v1/aweme/detail/`) and the `tiktokv.com` hosts answered
empty bodies without app signing. It stays as tier 1 with an 8-second timeout so it cannot stall
the share card. Tier 2, the public video page's `__UNIVERSAL_DATA_FOR_REHYDRATION__` blob
(desktop shape `webapp.video-detail`, mobile shape `webapp.reflow.video.detail`, plus the older
`SIGI_STATE`), gives `video.playAddr`, which is watermark-free: verified by extracting frames at
3 s and 90 s from a downloaded clip. The CDN wants the page's cookies (`tt_chain_token`, `ttwid`)
and a tiktok.com referer; without them it answers 403. `downloadAddr` is never used (that is the
watermarked file). Tier 3 is tikwm.com's free JSON as a last resort. Unknown shapes log a body
snippet under the `WakiDownload` logcat tag.

## 2026-09-12 minSdk 29
MediaStore `RELATIVE_PATH` (scoped storage, no storage permission) needs API 29. Nothing older is
in the household.

## 2026-09-12 File names
`<source>-<yyyymmdd-hhmmss>-<short-id>.<ext>` as specified; `short-id` is the last six digits of
the post id. Posts with several items get `-1`, `-2` before the extension, because the spec has no
other way to keep four photos from one tweet apart.

## 2026-09-12 Share card stays until done
"Dismiss share activity on completion" is read literally: the card shows resolve and download
progress and closes itself on success. Hide (or tapping outside) closes it early and the
WorkManager job keeps going with its own progress notification.

## 2026-09-12 Cloud targets (Waqas, mid-build): Google Drive and OneDrive via OAuth
Requested after the core was running: per-download choice of location, per-source defaults, an
NSFW checkbox, and share from any app. Google Drive uses Play Services `AuthorizationClient`
(device accounts, scope `drive.file`), registered as a Firebase Android app in the existing
`waki-brain` Cloud project because that project already has a consent screen and consent screens
cannot be created from the CLI. OneDrive uses a hand-rolled OAuth code + PKCE flow (public client,
custom scheme redirect) rather than MSAL, so the only configuration is one client id in
`WakiDownload.oauth.properties`; the Entra registration itself needs Waqas's Microsoft login and is
documented in docs/CLOUD_SETUP.md. Files picked through the system picker (SAF) are a third,
registration-free target kind.

## 2026-09-12 NSFW routing and Personal Vault
Microsoft Graph cannot write to OneDrive Personal Vault, so "send NSFW to the Vault" is not
possible through the API. NSFW downloads go to a chosen NSFW target plus an NSFW subfolder
(default OneDrive `WakiDownload/NSFW` once OneDrive is connected, local otherwise). A Vault folder
picked through the Files picker, if the OneDrive app ever exposes it, can be set as the NSFW
target.

## 2026-09-12 Everything is downloaded to the cache first
With four target kinds (MediaStore, SAF document, Drive resumable upload, Graph upload session),
one staging file per item keeps the worker simple: fetch (progress phase 1), then save
(phase 2). The extra local copy costs nothing noticeable on a phone.
