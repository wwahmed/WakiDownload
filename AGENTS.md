# Waki Download

<!-- wakilabs:managed start v1 -->
## WakiLabs conventions (managed block, do not edit by hand)

**Waki Download** (`waki-download`), archetype `android-app`, family `life`.
Save shared links and media on Android to Downloads, cloud drives, or a selected folder.

- Conventions version 1. Registry, policies and templates live in `~/workspaces/wakilabs`
  (`registry/projects.json`, `policies/*.yaml`, prose in `docs/standards/`).
- Canonical checkout: `~/workspaces/waki-download`.
- Foundation pin: not pinned yet (recorded by `waki adopt`).

Three rules:
1. Start sessions for this project here, at this repo's root.
2. Never do this project's work from `~/workspaces/wakilabs` or a worktree of it.
3. Never work under `~/workspaces/_archive/`; if the session guard says "archived path", stop and ask.

Deviating: when work has to depart from a policy, record it instead of hiding it:
`waki note deviation "<what and why>" --rule <rule id>` (ideas: `waki note proposal "<text>"`).

`waki check` here enforces: registry row, flat tree, naming, themes CDN, docs, managed block, .wakilabs/.
Run it before reporting work as done. Regenerate this block with `waki adopt --apply`.
<!-- wakilabs:managed end -->
