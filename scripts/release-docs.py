#!/usr/bin/env python3
"""Rewrite the CURRENT_VERSION blocks in README.md / RELEASE.md / INSTALL.md.

Doc helper only, NOT a ship: publish.sh sets WAKIDOWNLOAD_SHIP_FROM_PUBLISH_SH=1 and passes
the real APK SHA-256. Run standalone it refuses, so a version string can never land in a doc
where a digest belongs (that happened once in WakiDrive).
"""
import os, re, sys, pathlib

if os.environ.get("WAKIDOWNLOAD_SHIP_FROM_PUBLISH_SH") != "1":
    sys.exit("release-docs.py is not a ship script. Use scripts/publish.sh <version> <apk>.")
if len(sys.argv) != 3:
    sys.exit("usage: release-docs.py <version> <sha256>")
version, sha = sys.argv[1].lstrip("v"), sys.argv[2]
if not re.fullmatch(r"[0-9a-f]{64}", sha):
    sys.exit(f"refusing: '{sha}' is not a SHA-256")

root = pathlib.Path(__file__).resolve().parent.parent
repo = "https://github.com/wwahmed/WakiDownload"
block = f"""**Current version: v{version}** · SHA-256 `{sha}`

- [Latest APK (always newest)]({repo}/releases/latest/download/WakiDownload.apk) · [release notes]({repo}/releases/latest)
- [v{version} APK (this exact version)]({repo}/releases/download/v{version}/WakiDownload-v{version}.apk) · [v{version} notes]({repo}/releases/tag/v{version})"""
start, end = "<!-- CURRENT_VERSION:START -->", "<!-- CURRENT_VERSION:END -->"
pat = re.compile(re.escape(start) + r"[\s\S]*?" + re.escape(end))
changed = []
for name in ("README.md", "RELEASE.md", "INSTALL.md"):
    p = root / name
    if not p.exists():
        continue
    cur = p.read_text()
    if not pat.search(cur):
        continue
    new = pat.sub(f"{start}\n{block}\n{end}", cur)
    if new != cur:
        p.write_text(new)
        changed.append(name)
print(f"Updated version block (v{version}) in: {', '.join(changed)}" if changed else f"No version blocks changed (already v{version}).")
