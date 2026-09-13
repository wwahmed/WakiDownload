# WakiDownload release process (fast lane, mirrors WakiDrive)

Target: code freeze to live in 20 minutes or less. Ship small and often; never hold a ready fix
to batch it.

## Gate tiers (pick one at code freeze)

| Change | Required gate |
|---|---|
| Resolver / parser / naming logic | `:app:testDebugUnitTest` green + a fixture test for the new shape + one real link of each affected source downloaded on the emulator |
| UI / copy | unit tests green + a screenshot of the affected screen (both themes only when theme-sensitive) |
| Updater or signing | unit tests green + an actual in-app update from the previous release on the emulator (install old, ship new, tap Check for updates, confirm) |

## Steps

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. `scripts/build.sh` (runs release unit tests, then `assembleRelease`; fails without the keystore).
3. Run the tier's gate on the emulator (`adb install -r` the release APK, share a link).
4. `scripts/publish.sh <version> app/build/outputs/apk/release/app-release.apk notes.md > /tmp/publish.log 2>&1; echo $?`
   (redirect, never pipe; no shell `timeout` wrapper).
5. Confirm `git rev-list --count origin/main..HEAD` is 0 and `gh release view v<version>` shows `Latest`.
6. Append the receipt to `docs/POLISH_ROUNDS.md`, commit, push.
7. Fire exactly one PushNotification to Waqas.

## Ship invariants

`publish.sh` is the only ship path. Cert `b155b935…`. Three assets. `--latest`. Digest-verified by
asset id and through the anonymous stable URL. RELEASE.md real SHA. One PushNotification after 1-6.
