# 617 evidence: predecessor-session transcript triage (2026-07-31)

Moved from [617 §10.7](../617-in-place-app-update-mechanism.md) to keep the tempdoc under its size cap.

The prior Codex session's full transcript was reviewed (3,428 lines). Its last
formal verdict was **"NO-GO for merge or release. Final confidence: 4/10"** —
materially more cautious than this doc's frontmatter, which read
`implemented; release qualification pending`. The doc had drifted optimistic
relative to its author's judgment; the frontmatter is corrected above.

The session ended mid-flow, on *"then I'll run the integrated verification and
independent review"* — neither ran. That, not carelessness, is why §10.4's three
gate violations survived: they were never given a chance to fail. Leaving the
tree uncommitted was likewise a stated policy (*"I'll leave all changes
uncommitted"*), not an oversight — but it is what made §10.1 necessary.

Its confidence trajectory is worth preserving: 6/10 → 7/10 after derisk →
**3/10** after an independent refute-first review that found ten P0/P1 blockers
→ 4/10 after remediation. Confidence fell under scrutiny, twice. The
"version equality is only an observation" rule in §7.5 is a *remediation* of one
of those P0s, not original design — the review loop earned it.

One dated claim needs retiring: the session recorded *"Rust test execution is
blocked locally by Windows Smart App Control."* That diagnosis was wrong. The
real blocker was the unstaged `resources/headless` bundle glob; with SAC still
enforcing, staging a placeholder ran all 39 tests (§10.3). This is the
`ai-offline-isnt-a-wall` handle: a tier declared environmentally unavailable
while a tool for it was at hand.

Its remaining-risk register, re-checked against current code rather than taken
as read:

| Item | Status |
|---|---|
| P0 no durable-owner reconciliation protocol | **closed** — `updater.rs:931` gates `COMMITTED` on a durable-owner format check, `UpgradeReconciliationProbe` backs it |
| P0 release workflow cannot publish the closed asset set | **closed** — `tauri.updater.conf.json` overlay carries `createUpdaterArtifacts` + `basicUi`, applied at `package-installer-win.ps1:288-298`, signing secrets wired at `build-installer.yml:231` |
| P0 quiescence coverage incomplete | **partly closed** — see §10.8 |
| P0 Sandbox lane does not exercise the in-app updater | **open** — §9 items 3-4; see §10.12 for what is actually reachable |
| P1 inventory completeness unproven | **closed** — `dd749c1d` |
| P1 authored stores at version 0 | **not a defect** — the two remaining (`byo-ai-assets`, `user-plugin-payloads`) are `PRESERVE_EXTERNAL` formats the app never parses or rewrites; version-stamping them would be meaningless. Run events are versioned |
| P1 Tauri exits after `ShellExecuteW` unchecked | **closed** — §7.5's witnessed `ShellExecuteExW` |
| P1 tempdoc contradicts the implemented Tier-A decision | **closed** — the header note |
| P2 `tauri.conf.json` collides with 772 | **open** — §10.6 |
