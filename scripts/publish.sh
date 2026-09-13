#!/usr/bin/env bash
#
# Canonical WakiDownload release publisher (mirrors WakiDrive's publish.sh). The ONLY ship
# path. Guarantees, every time, that a release carries:
#
#   WakiDownload-vX.Y.Z.apk   versioned asset
#   WakiDownload.apk          unversioned asset, so the stable URL resolves:
#                             https://github.com/wwahmed/WakiDownload/releases/latest/download/WakiDownload.apk
#   latest.json               the in-app updater manifest (version, versionCode, sha256, cert)
#
# `gh release upload file#Name` sets a display LABEL, not the asset name, so the script copies
# the APK to a file literally named WakiDownload.apk before uploading.
#
# Usage:  scripts/publish.sh <version> <path-to-apk> [release-notes-file]
#   e.g.  scripts/publish.sh 0.1.0 app/build/outputs/apk/release/app-release.apk notes.md
#
# Requires: gh (authenticated), $ANDROID_HOME build-tools (aapt, apksigner), python3.
set -euo pipefail

REPO="wwahmed/WakiDownload"
# Release signing cert, FULL digest. Load-bearing: the app's updater pins the same value
# (app/build.gradle.kts releaseCertSha256). Keystore: ~/.wakilabs-keystores/WakiDownload.jks
EXPECTED_CERT="b155b93522ccf54d35e3a0b5a268a36e766413f679e44e0dd34e68b33ff532c8"

VERSION="${1:?usage: publish.sh <version> <apk> [notes-file]}"
APK="${2:?usage: publish.sh <version> <apk> [notes-file]}"
NOTES_FILE="${3:-}"
VERSION="${VERSION#v}"
TAG="v$VERSION"
VERSIONED="WakiDownload-${TAG}.apk"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

[ -f "$APK" ] || { echo "✗ APK not found: $APK" >&2; exit 1; }
ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"

echo "▸ Verifying $APK for $TAG"
APK_VER="$("$BT/aapt" dump badging "$APK" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
[ "$APK_VER" = "$VERSION" ] || { echo "✗ APK versionName ($APK_VER) != $VERSION" >&2; exit 1; }
APK_CODE="$("$BT/aapt" dump badging "$APK" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
[ -n "$APK_CODE" ] || { echo "✗ could not read versionCode from APK" >&2; exit 1; }
CERT="$("$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | sed -n 's/.*SHA-256 digest: \([0-9a-f]*\).*/\1/p' | head -1)"
[ "$CERT" = "$EXPECTED_CERT" ] || { echo "✗ signing cert ($CERT) != $EXPECTED_CERT (debug or wrong key: never publish)" >&2; exit 1; }
"$BT/apksigner" verify "$APK" >/dev/null 2>&1 || { echo "✗ apksigner rejected the APK" >&2; exit 1; }
# Updater contract must be IN the APK or the phone can never take the next release.
# (count matches rather than grep -q: -q SIGPIPEs aapt mid-stream and pipefail reads that as failure)
[ "$("$BT/aapt" dump permissions "$APK" 2>/dev/null | grep -c "REQUEST_INSTALL_PACKAGES")" -ge 1 ] || { echo "✗ updater contract MISSING (REQUEST_INSTALL_PACKAGES)" >&2; exit 1; }
[ "$("$BT/aapt" dump xmltree "$APK" AndroidManifest.xml 2>/dev/null | grep -c "InstallReceiver")" -ge 1 ] || { echo "✗ updater contract MISSING (InstallReceiver)" >&2; exit 1; }
# versionCode is the update authority (Android installs by code, not name): must beat the last published.
PREV_CODE=0
PREV_MANIFEST_URL="$(gh api "/repos/$REPO/releases/latest" --jq '.assets[]|select(.name=="latest.json").browser_download_url' 2>/dev/null || true)"
case "$PREV_MANIFEST_URL" in
  https://*) PREV_CODE="$(curl -fsSL "$PREV_MANIFEST_URL" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("versionCode",0))' 2>/dev/null || echo 0)" ;;
  *) echo "  (no previous release: first publish)" ;;
esac
[ "$APK_CODE" -gt "${PREV_CODE:-0}" ] || { echo "✗ APK versionCode ($APK_CODE) is not greater than last published ($PREV_CODE)" >&2; exit 1; }
SHA="$(shasum -a 256 "$APK" | awk '{print $1}')"
BYTES="$(stat -f%z "$APK" 2>/dev/null || stat -c%s "$APK")"
echo "  ✓ version=$APK_VER code=$APK_CODE cert=${CERT:0:8}… sha256=$SHA bytes=$BYTES"

# Stage the three assets with the EXACT names GitHub will serve.
STAGE="$(mktemp -d)"; trap 'rm -rf "$STAGE"' EXIT
cp "$APK" "$STAGE/$VERSIONED"
cp "$APK" "$STAGE/WakiDownload.apk"
NOTES=""; [ -n "$NOTES_FILE" ] && [ -f "$NOTES_FILE" ] && NOTES="$(cat "$NOTES_FILE")"
# apkUrl points at the VERSIONED asset so manifest and APK are one authority (no race with the latest pointer).
VERSION="$VERSION" CODE="$APK_CODE" SHA="$SHA" BYTES="$BYTES" CERT="$CERT" TAG="$TAG" VERSIONED="$VERSIONED" NOTES="$NOTES" REPO="$REPO" python3 - > "$STAGE/latest.json" <<'PY'
import json, os, datetime
e = os.environ
print(json.dumps({
  "version": e["VERSION"],
  "versionCode": int(e["CODE"]),
  "byteSize": int(e["BYTES"]),
  "issuedAt": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
  "apkUrl": f"https://github.com/{e['REPO']}/releases/download/{e['TAG']}/{e['VERSIONED']}",
  "apkSha256": e["SHA"],
  "signingCertSha256": e["CERT"],
  "releaseNotes": e["NOTES"] or f"WakiDownload {e['VERSION']}",
}, indent=2))
PY
echo "  ✓ updater manifest staged (latest.json)"

# Docs carry the REAL sha; commit; push main so the tag references the shipped commit.
echo "▸ Docs + push"
WAKIDOWNLOAD_SHIP_FROM_PUBLISH_SH=1 python3 scripts/release-docs.py "$VERSION" "$SHA"
git add -A && git commit -q -m "docs: version block -> $TAG (SHA-256 $SHA)" || echo "  (no doc changes to commit)"
git push origin HEAD:main
TARGET_SHA="$(git rev-parse HEAD)"
echo "  ✓ pushed main @ ${TARGET_SHA:0:8}"

echo "▸ Publishing $TAG (three assets, --latest)"
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" "$STAGE/$VERSIONED" "$STAGE/WakiDownload.apk" "$STAGE/latest.json" --repo "$REPO" --clobber
  gh release edit "$TAG" --repo "$REPO" --latest
else
  NOTES_ARGS=(--notes "WakiDownload $TAG · SHA-256 \`$SHA\`")
  [ -n "$NOTES_FILE" ] && NOTES_ARGS=(--notes-file "$NOTES_FILE")
  gh release create "$TAG" "$STAGE/$VERSIONED" "$STAGE/WakiDownload.apk" "$STAGE/latest.json" \
    --repo "$REPO" --target "$TARGET_SHA" --latest --title "$TAG" "${NOTES_ARGS[@]}"
fi

echo "▸ Post-publish verification"
fail=0
LATEST_TAG="$(gh api "/repos/$REPO/releases/latest" --jq '.tag_name')"
[ "$LATEST_TAG" = "$TAG" ] || { echo "  ✗ latest is $LATEST_TAG, expected $TAG"; fail=1; }
for name in "$VERSIONED" "WakiDownload.apk"; do
  aid="$(gh api "/repos/$REPO/releases/tags/$TAG" --jq ".assets[]|select(.name==\"$name\").id")"
  [ -n "$aid" ] || { echo "  ✗ asset $name MISSING on $TAG"; fail=1; continue; }
  got="$(gh api -H "Accept: application/octet-stream" "/repos/$REPO/releases/assets/$aid" 2>/dev/null | shasum -a 256 | awk '{print $1}')"
  [ "$got" = "$SHA" ] && echo "  ✓ $name sha matches (asset $aid)" || { echo "  ✗ $name sha $got != $SHA"; fail=1; }
done
# The phone reads these two URLs anonymously (public repo); prove they resolve without a credential.
for name in "WakiDownload.apk" "latest.json"; do
  got="$(curl -fsSL --max-time 300 "https://github.com/$REPO/releases/latest/download/$name" | shasum -a 256 | awk '{print $1}')"
  if [ "$name" = "WakiDownload.apk" ]; then
    [ "$got" = "$SHA" ] && echo "  ✓ anonymous latest URL serves the new APK" || { echo "  ✗ anonymous latest URL sha $got != $SHA"; fail=1; }
  else
    want="$(shasum -a 256 "$STAGE/latest.json" | awk '{print $1}')"
    [ "$got" = "$want" ] && echo "  ✓ anonymous latest.json matches the staged manifest" || { echo "  ✗ latest.json served != staged"; fail=1; }
  fi
done
[ "$fail" = 0 ] || { echo "✗ PUBLISH VERIFICATION FAILED: release is not safe to announce" >&2; exit 1; }

echo "✓ $TAG published + verified: latest flag set, all assets present, SHAs match."
echo "  Stable URL: https://github.com/$REPO/releases/latest/download/WakiDownload.apk"
echo "  Versioned:  https://github.com/$REPO/releases/download/$TAG/$VERSIONED"
echo "  Manifest:   https://github.com/$REPO/releases/latest/download/latest.json"
