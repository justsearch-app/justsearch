# m1: Owner-only actions (shippability funnel)

Each item below is something only the repo owner can do — the `m1-shippability` branch fixed
everything fixable in-repo. One line each, exact location.

- [ ] **Review and merge/push the branch stack** — `m1-shippability` → `mcpb-packaging` (the latter also carries the MCPB bundle, `docs/comparison.md`, and the narrowed uniqueness claim); both exist only locally in this checkout; nothing was pushed.
- [x] **Verify the installer checksum against the real release asset** — done 2026-07-14: the v0.1.0 asset was downloaded from GitHub and hashes to the exact value in `SHA256SUMS` / README (identical to the local build).
- [ ] **Upload `SHA256SUMS` to the v0.1.0 release** — repo → Releases → v0.1.0 → Edit → attach the file.
- [ ] **Cut a new release from current main (v0.2.x) — REQUIRED before any MCP-related visibility work.** The shipped v0.1.0 build (2026-04-28 jars) contains **no MCP endpoint** (`/mcp` hits a catch-all empty 200), so the README's MCP sections and the MCPB bundle only work against current code. Bump `gradle.properties` + `packaging/mcpb/server.json` to the release version, then dispatch `build-installer.yml` against the `v0.2.x` tag: the pipeline (`scripts/ci/build-release-assets.ps1`) stages + attaches installer + generated `SHA256SUMS` + `justsearch-mcp.mcpb` automatically, behind a fail-closed MCPB consistency gate. Full runbook: `docs/how-to/cut-a-release.md`. Mark it non-prerelease so `/releases/latest` resolves. Merely un-prereleasing v0.1.0 would point strangers at an app that contradicts the README.
- [ ] **Set the repo homepage field** — repo front page → gear icon next to About → Website (currently empty; `homepageUrl: ""`).
- [ ] **Decide on Discussions** — Settings → General → Features → tick "Discussions" to enable it, then optionally point SUPPORT.md/CONTRIBUTING.md links back at it; otherwise keep the Issues routing this branch installed (Discussions is currently disabled and the old links 404'd).
- [x] **Add the README hero screenshots** — the current app is shown in the [README](../README.md); capture instructions retired.
- [x] **Resolve the two TODO comments in README** — the RAM row (conservative estimate, not measured) and the Windows 10 note (only Windows 11 verified) in the System requirements table. — done in-repo 2026-07-14 (726)

Delete this file when the list is done.
