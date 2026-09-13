# WakiDownload, session notes

Native Android / Kotlin, no Compose: AGP 8.5.2, Kotlin 2.0.21, Gradle 8.7, compileSdk 35, minSdk 29.
`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`, JDK 17 from Homebrew
(`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`). Emulator AVD `wakidrive`
(API 35, arm64) works for QA.

## Rules of the road

- No em dashes anywhere (docs, strings, comments, commit messages).
- Kotlin nests block comments: never write `image/*` inside a `/* */` or KDoc comment.
- The keystore at `~/.wakilabs-keystores/WakiDownload.jks` (password file beside it) is the app's
  identity. Never regenerate it. The cert SHA-256 `b155b93522ccf54d35e3a0b5a268a36e766413f679e44e0dd34e68b33ff532c8`
  is pinned in `app/build.gradle.kts` and `scripts/publish.sh`.
- Bump BOTH `versionCode` and `versionName` in `app/build.gradle.kts` for every release; Android
  installs by code and `publish.sh` refuses a code that does not beat the last published one.
- `docs/DECISIONS.md` records why things are the way they are. Add to it when a decision is made.
- Unknown-shape failures from Twitter or TikTok are logged under logcat tag `WakiDownload` with a
  body snippet. Start any resolver fix from that log, not from a guess.
- Unit tests: `./gradlew :app:testDebugUnitTest` (resolver parsers, link classifier, file names).

## Definition of Done for a SHIP (MANDATORY, mirrors WakiDrive)

A version is only "shipped" when every one of these is true and VERIFIED (real `$?`, never a
piped exit code):

1. `scripts/publish.sh <version> <apk> [notes]` ran and exited 0 (capture output to a file, check
   `$?`). `publish.sh` is the ONLY ship path. `scripts/release-docs.py` is a doc helper guarded to
   refuse standalone runs.
2. `git push origin main` succeeded: `git rev-list --count origin/main..HEAD` == 0.
3. `gh release create v<version>` created the release with `WakiDownload.apk` (unversioned) AND
   `WakiDownload-v<version>.apk` (versioned), plus `latest.json`.
4. `gh release view v<version>` confirms the tag exists, `--latest` is set, and each asset's
   SHA-256 matches the local APK (publish.sh re-downloads by asset id and diffs).
5. RELEASE.md's version block carries the REAL sha256 (publish.sh writes it).
6. The Waqas-facing URL is live and serves the new version:
   `https://github.com/wwahmed/WakiDownload/releases/latest/download/WakiDownload.apk`
   (public repo: an anonymous `curl -L` must return the new digest; publish.sh checks this).
7. **PushNotification fired to Waqas's phone**, ONCE per real ship, only AFTER 1-6 verified. One
   line, under 100 characters of what is new plus the latest-download URL. Never on a partial ship,
   never twice for one version.
8. A `docs/POLISH_ROUNDS.md` entry is appended for the release (what shipped + verification receipt).

GitHub Releases are the single source of truth. No Drive copy, no Play Store, no own server.
