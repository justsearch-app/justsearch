# WP2: release safety (downgrade, broken release, compatibility truth)

Type: mixed. 2a and 2b are code, 2c and 2d are verification, 2e is docs. 2d changes how a
§16 row may be carried, so it is recorded through §17.6 (a dated §0 line plus §16).

## Findings

Read-only audit of `7f469d044` against `origin/main`. **V** = the reviewer re-checked it.

| Store | Branch change | What an older (pre-Lane-F) build does after a downgrade |
|---|---|---|
| `jobs.db` | v12 to v21 | refuses cleanly (an existing future-version guard); derived, so it rebuilds |
| index (`state.json`, generation manifest) | `state.json` unchanged at v2; new recorded manifest v2 under the same filename | tolerant reader, so it probably opens. Lucene codec compatibility was not checked |
| `ui/settings.json` | schema 2 to **4** | fail-loud read, then **quarantined to `.corrupt-*` and silently reset to defaults** |
| `operations.db` | new | ignored, so operation history is orphaned |
| `runtime/*` | new ephemeral files | reset by policy |

- **The updater has no downgrade direction.** `validate_store_compatibility`
  (`modules/shell/src-tauri/src/updater.rs`) only checks that a newer candidate can read the
  installed stores. A manually installed older `.exe` bypasses it.
- **Register drift (V):** `governance/store-recoverability.v1.json` `ui-settings` has
  `currentVersion: 1`, while `UiSettingsStore.CURRENT_SCHEMA_VERSION` is 4 (on `origin/main`:
  1 against 2, so this predates Lane F and Lane F widens it). `updater.rs` embeds this
  register, and the release-assets generator derives the published compatibility table from
  it. `check-store-recoverability` does not compare register versions with code constants.
- **Recovery policy:** design §15 is fix-forward through the updater and depends on the
  dead-Engine path. Its signed round (`upgrade-dead-engine-recovery`,
  `governance/sandbox-coverage.v1.json`) has never run, and §16 allows stage F to carry it as
  a named gap if main-only signing blocks the artifact.

## Design

- **2a. One source of truth for store versions.**
  1. First establish what the register's `currentVersion` means for `ui-settings` (read
     `updater.rs`'s use of `currentVersion` and `readableLegacyVersions`). If it is meant to
     equal the code's schema constant, fix the row (4, with readable legacy versions matching
     `READABLE_LEGACY_VERSIONS`). If it deliberately differs, document that meaning in the
     register schema.
  2. Then make the check prevent the drift: register rows gain an optional `versionSource`
     (`file` plus `symbol`), and `check-store-recoverability` extracts the constant and
     compares it. Apply it to every SQLite store and JSON store with a code version constant.
- **2b. No silent loss on a version mismatch, from now on.** Old builds can't be changed, but
  every build from Lane F onward can behave well:
  1. **Pre-migration backup.** Before `UiSettingsStore` upgrades a readable legacy file to a
     newer schema, write `settings.v<old>.bak.json` (same directory, atomic, kept once per
     version). This gives a user who downgrades to a pre-Lane-F build a file that build can
     read. The restore steps go in the release note (2e).
  2. **A data-version marker.** At boot, write `data-version.json` (app version plus the store
     versions) under the data directory, registered in the recoverability register. At boot,
     if the marker is newer than this build, show a recovery notice through the existing
     no-API recovery UI (7.3 host path) or readiness reason, naming the newer version and the
     preserved files. Quarantine stays, but it is never *silent*. The exact UX is the
     implementer's call within the existing notice surfaces; a new UI surface needs a
     ui-check pass.
- **2c. A downgrade sandbox round.** Add `--downgrade-from` to
  `scripts/sandbox/sandbox-launch.py`, mirroring `--upgrade-from`, and register a `mustWatch`
  row `downgrade-after-lane-f` in `governance/sandbox-coverage.v1.json`.

  Scenario: install `main`'s current release, then the Lane F candidate, add a setting,
  ingest, run a durable operation, then install `main`'s release again. Expected and asserted:
  - the app boots;
  - settings are reset but the `.bak` and `.corrupt-*` files exist, and following the
    documented restore brings them back;
  - `jobs.db` rebuilds;
  - the index opens or rebuilds;
  - `operations.db` is left untouched.
- **2d. The dead-Engine signed round becomes a hard merge blocker (recommended).** It is the
  only tested recovery path for a Lane F regression. Amend §16 so the named-gap carry-over no
  longer applies to this row. If main-only signing blocks a signed candidate, ask the owner
  for a one-off signed build of the candidate (`build-installer.yml`) rather than carrying
  the gap. **Orchestrator decision under §17.6.** If you keep the carry-over, record why in
  §16.
- **2e. State the policy.**
  1. Add to `docs/how-to/cut-a-release.md` and the 0.3.x release-note template: fix-forward
     only; what a downgrade does per store (the table above); the settings restore steps.
  2. Disambiguate design §17.1 (WP5).

## Implementation plan

| # | Item | R/I | Acceptance | When |
|---|---|---|---|---|
| 2a.1 | Settle the register semantics; fix the `ui-settings` row | R | `check-store-recoverability` green; updater compatibility unit tests green | next batch boundary |
| 2a.2 | `versionSource` check | R | negative control: the check fails on the current mismatch (or on a planted one if 2a.1 already fixed it), then passes. Self-test updated | with 2a.1 |
| 2b.1 | Pre-migration settings backup | R | unit test: a v2 file upgraded yields `settings.v2.bak.json` identical to the original bytes; a second boot doesn't overwrite it | before E |
| 2b.2 | Data-version marker and newer-data notice | R | test: a marker newer than the build produces the notice and reason code, with no silent default; register row plus runtime closure check | before E |
| 2c | Downgrade sandbox round | R | the scenario above passes on the candidate; record in `evidence/E/` | E (merge gate, with the dead-Engine round) |
| 2d | Dead-Engine signed round as a blocker | R (recommended; orchestrator decides) | §16 amended; round executed on the final candidate | E |
| 2e | Release documentation | R | `docs-validate` green | F |

**Estimate:** about 2 sessions of code plus the sandbox time at E. **Re-plan trigger:** if
2a.1 shows the register is intentionally decoupled from code versions *and* the updater
depends on that, stop and write the finding up for the owner before changing either.
