---
title: Verify an Update Preserves the User's Models
type: how-to
status: stable
description: "Run the Update Preserves Models CI lane, which installs a published release, seeds model files, upgrades to the next release, and asserts the seeded bytes survive both the upgrade and the uninstall."
---

# Verify an Update Preserves the User's Models

[ADR-0024](../decisions/0024-app-packaging-nsis-per-user-download.md) ships the
app without model weights and downloads them after install. Tempdoc 617 D2 then chose
**monolithic full-installer updates** on one condition: the models already on disk
are reused in place and are never re-downloaded or deleted by an update.

A monolithic update runs uninstall-then-install over an existing install, so the two ways to break
that condition are both one careless line: the packaging surface starts carrying model weights, or
an uninstall hook recursively deletes a user-data root. Neither shows up until a real user upgrades.

Two things guard it, at different strengths:

| Guard | What it proves | Limit |
|---|---|---|
| `node scripts/ci/check-update-preserves-models.mjs` | The **declared** surface is clean: no model weights in `tauri.conf.json` `bundle.resources`, none staged by `bundleSidecarResources`, no recursive `RMDir /r` and no delete under a user-data root in `installer-hooks.nsh`. | Static. Says nothing about a built installer. |
| `.github/workflows/update-preserves-models.yml` | A **real** published installer N, upgraded by a real installer N+1, leaves seeded model bytes identical — and so does the uninstaller. | Only the installer-over-release half; the app is never launched. |

## Run the lane

`workflow_dispatch` only ([ADR-0026](../decisions/0026-manual-ci-triggering.md)), on
`windows-latest`, ~15 min:

```bash
gh workflow run update-preserves-models.yml
gh run list --workflow=update-preserves-models.yml --limit=1
```

Inputs:

| Input | Default | Meaning |
|---|---|---|
| `base_release_tag` | `v0.1.0` | Release whose published installer plays version **N**. |
| `candidate_source` | `release` | `release`, or `run-artifact` to gate an unpublished build. |
| `candidate_release_tag` | `v0.2.0` | Release whose published installer plays **N+1**. |
| `candidate_run_id` | *(empty)* | `build-installer.yml` run id supplying N+1 when `candidate_source: run-artifact`. Artifacts expire after 7 days. |

The defaults test the historical v0.1.0 → v0.2.0 pair. Set explicit inputs to test the intended upgrade.

For a draft candidate, use its original `build-installer.yml` run's retained
`windows-installer` artifact; a tag build creates a draft, without publishing it:

```bash
gh workflow run update-preserves-models.yml --ref main \
  -f base_release_tag=<previous-vX.Y.Z> -f candidate_source=run-artifact -f candidate_run_id=<candidate-run-id>
```

The run uploads `update-preserves-models-evidence` containing
`update-preserves-models.v1.json` (schema `justsearch.update-preserves-models.v1`: both installer
versions, every seeded path with its sha256/size/mtime at three points in time, per-step pass/fail,
the before/after deletion diffs, and a `verdict`). The job summary carries a one-line verdict.

## What the lane does

1. Refuses to proceed unless the runner has no pre-existing JustSearch install — an ambiguous
   machine cannot produce a meaningful measurement.
2. Installs N with `/S`. `installMode: currentUser` means no admin prompt and no interactive page,
   so this works unattended on a hosted runner.
3. Seeds model files and records each one's sha256, size and mtime:
   - **Gating** — `%APPDATA%\io.justsearch.shell\models\…`. The packaged shell passes
     `-Djustsearch.data.dir=<app_data_dir>` and `JUSTSEARCH_HOME=<app_data_dir>` to the backend
     (`spawn_headless_backend` in `modules/shell/src-tauri/src/lib.rs`) and creates
     `<app_data_dir>\models` itself, so this is where a real user's weights live.
   - **Observational** — `%LOCALAPPDATA%\JustSearch\models\…`. This is the contract default data
     dir (`platform_paths.rs`, `ResolvedPathResolver.java:66`) **and** the NSIS `currentUser`
     `$INSTDIR`. The two collide, so anything seeded there sits inside the directory the
     uninstaller owns; the lane records the outcome instead of asserting one.
   The large blob is created by writing a random header then `SetLength`, so a 256 MB "weight
   file" costs milliseconds and near-zero disk while still having a distinctive hash.
4. Installs N+1 with `/S` over it, and asserts this was a genuine **upgrade** — exactly one
   Add/Remove Programs entry, with `DisplayVersion` advanced. Two entries would mean a
   side-by-side install, which would make a "models survived" pass meaningless.
5. Asserts every gating seed is byte-identical (sha256 **and** mtime).
6. Runs the uninstaller from the registry `UninstallString` with `/S` and asserts the gating seeds
   are *still* identical. ADR-0024 preserves user data on uninstall as well
   (tempdoc 374:1297-1299, 374:2350), so this is asserted, not merely observed.

Every step fails closed, and the evidence JSON is written even on an unexpected exception.

> **Never run `scripts/release/update-preserves-models-test.ps1` on your own machine.**
> Its lookups are host-global: it will find whatever JustSearch
> the machine has registered and silently `/S`-uninstall it. The script refuses to start unless
> `GITHUB_ACTIONS=true`, with no override. Syntax-check it with
> `[System.Management.Automation.Language.Parser]::ParseFile(...)` instead.

## What it does not cover

- **Nothing at runtime.** The installed app is never launched, so this does not prove the app
  refrains from re-downloading models after an update — only that the installer does not remove
  them.
- **Not the in-app updater.** The [clean VM journeys](../../scripts/release/qual/journeys/README.md)
  cover update and interruption/reconciliation separately, with their documented limitations.
- **Not scale.** The multi-gigabyte model set is mimicked by a sparse blob, not reproduced.
- **Not the install-tree collision.** `%LOCALAPPDATA%\JustSearch\models` is recorded, not gated.

## Candidate identity

This supplementary installer-only lane downloads published assets by default;
`candidate_source=run-artifact` selects the original candidate run's installer.
Release signing is unattended in the `release-signing` environment. No temporary
branch permission or environment approval is needed. For the full release
qualification, use the draft's downloaded bytes and the journey suite in the
[release checklist](cut-a-release.md).
