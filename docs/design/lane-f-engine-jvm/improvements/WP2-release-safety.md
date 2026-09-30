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

## 2026-09-25 2a implementation correction

`updater.rs` compares `currentVersion` to the successor's readable source versions, so
`ui-settings` must declare v4 and readable 0–3. The first gated run with `versionSource`
failed on the real 1-versus-4 mismatch; after correction, the register gate and 78
self-test assertions passed. Literal version constants on the SQLite and JSON rows are
bound through the same optional source check.

Independent refutation found that the updater also exact-compares the register's
`reconciliation` string before version readability. Renaming the historical
`READ_V0_OR_V1_AND_WRITE_V1` token to include v4 would make released v0.2.0/v0.3.0
updaters reject this successor despite its ability to read v1. The token is therefore a
frozen strategy identifier for read-in-place upcast and full rewrite; the row's format,
`currentVersion`, predecessors and `versionSource` state the actual version. The Rust
legacy-local positive/negative test and release-descriptor projection test cover this
boundary. The production release wrapper still needs an inherited-register baseline
argument before stage E, since its current invocation omits the generator's optional
`--compat-baseline`; this remains WP2 work, not a completed release claim.

## 2026-09-30 inherited-baseline owner design (wrapper implemented; retirement open)

Extend `derive-release-sequence.mjs`, the existing published-release authority,
with optional `--compat-baseline-out`. When requested, acquire the complete
recoverability register from the same highest-sequence predecessor tag used for
sequence derivation, excluding the tag being rebuilt. Validate nonempty unique
installed store rows and cross-check the predecessor descriptor's compatibility
identities and formats against that tag register. Descriptor projection alone
cannot supply the complete installed predecessor owner set. Missing, ambiguous
or inconsistent evidence must produce neither a sequence nor a usable baseline.
Write validated exact register bytes atomically to the caller's temporary path.

Require explicit `-CompatibilityBaselinePath` for updater assembly through both
PowerShell wrappers, validate it before build/signing/staging, and pass the existing
generator's `--compat-baseline`. The tag workflow derives sequence and baseline in
one call. Ordinary installer assembly is unaffected. Do not default to the current
register, add another checked-in authority, rename frozen reconciliation tokens,
or publish a release as part of this implementation.

Root owns wrapper/workflow production edits. Independent Sol design/review and
offline negative controls cover a renamed predecessor strategy, duplicate/missing
stores, highest-sequence selection, no sequence/file on failure, and omission of
target-only stores while inherited target formats advance. Required hosted release
asset inspection remains separate from local proof and needs a signed candidate;
the existing signing/Sandbox dependency is not waived.

### Review correction and live predecessor evidence

After two substantive review rounds, root reassessed the acquisition boundary:
one parsed invocation owns its explicit output from argument parsing onward, so
every later failure invalidates old bytes, including a missing repository. Help
remains read-only. Tag metadata and positive safe sequence integers bind selection;
both wrappers invoke the generator's shared compatibility parser before expensive
build/signing/staging. No separate compatibility representation was added.

Offline predecessor tests (56 assertions), wrapper rejection tests (10 checks)
and existing asset-generator tests (12 cases) passed in
`tmp/lane-f-wp2-predecessor-tests-r3.log`,
`tmp/lane-f-wp2-wrapper-negative-controls-r3.log` and
`tmp/lane-f-wp2-assets-tests-r2.log`. The missing-repository stale-output
regression subsequently passed at
`tmp/lane-f-wp2-predecessor-tests-r4.log` (57 assertions). Independent Sol's
final bounded review found no remaining acquisition/wrapper defect. Wrapper
controls and existing generator cases passed again at their r4/r3 logs.

The read-only real acquisition selected published v0.3.0, sequence41, and
derived42 with its exact42-store tag register
(`tmp/lane-f-wp2-live-predecessor-r2.log` and
`tmp/lane-f-wp2-live-predecessor-register-r2.json`). Actual target preflight
refuses the missing `worker-config-snapshot` owner, which stages A/B intentionally
retired. Shared predecessor identities and versions otherwise match. This red
preflight is an unresolved retirement contract, not release acceptance: preserve
the missing-owner guard while designing exact explicit retirement evidence.

### Explicit retired owner contract (implemented locally; installed proof open)

Root selected this design after independent Sol refutation of the actual v0.3.0
Shell and current target. The existing recoverability register gains a disjoint
`retiredDurableStores` list: exact historical id, owner, recovery class, version,
and reconciliation identity, plus `PRESERVE_INERT` byte disposition and the dated
retirement decision. Its first row is `worker-config-snapshot`, HEAD, DERIVED,
version0, `UNCONDITIONALLY_REGENERATE_BEFORE_WORKER_START`, retired by A19 on
2026-09-07. The existing policy ledger remains historical rationale and points to
this runtime authority; no second tuple ledger is created.

Normal release descriptors remain active-only. A predecessor baseline may select
an explicit retired row only when its full historical tuple and version match;
that signed descriptor row retains the frozen identity and readable version0.
Unspecified missing owners, tuple drift, overlap, duplicate ids and wrong versions
still refuse. A retired owner absent from the predecessor is never appended.
The published-predecessor acquisition validator must cross-check descriptor rows
against active plus retired rows, so the first release retaining a legacy
compatibility row can itself become a predecessor. Its exact raw tag register is
still emitted; future generators use only that predecessor's active store set.

Installed v0.3.0 persists only owner id/version in its upgrade intent and requires
an exact42-row descriptor. Therefore no new descriptor field can carry target
disposition through it. The target Shell reads its embedded retired list, consumes
only exact inherited id/version with `PRESERVE_INERT`, then expands and reconciles
all51 active owners. Head remains all-and-only active. Existing stale snapshot
bytes are never opened, changed, removed, migrated or attested as a healthy active
store. A fake READY active row, inference of retirement from absence, and a new
descriptor disposition field were rejected for those ownership failures.

Root owns the register, generator, predecessor validator and Rust integration.
Bounded tests can be delegated after the seam is fixed. Required local proof:
real v0.3 baseline yields42 exact compatibility rows; wrong/absent retirement and
arbitrary missing ids refuse; non-inherited retirement is omitted; a future
active51/retired1 tag accepts its inherited42 descriptor and emits exact raw bytes;
Rust consumes only worker id/version0 and returns active51, refusing wrong version,
unknown/duplicate/overlapping ids; Head's exact active-owner tests remain green.
Installed signed v0.3-to-target proof is still required under E and the signing
dependency remains explicit. This design adds no store, producer, public endpoint,
operation registry, cleanup writer or snapshot resurrection.

Local execution passes19 asset-generator cases,61 predecessor assertions,84 gate
assertions and all93 Shell unit cases at
`tmp/lane-f-wp2-retirement-{assets,predecessor,gate-tests}-r1.log` and
`tmp/lane-f-wp2-retirement-rust-all.log`. The first focused Rust run failed because
the new test retained its unrelated `preferences` mock owner; the test now derives
active owners from the actual embedded register, preserving the rejection guard.
Both the original red log and corrected36-case focused output are retained at
`tmp/lane-f-wp2-retirement-rust-r{1,2}.log`.

The actual raw v0.3 predecessor projection is captured in
`tmp/lane-f-wp2-real-retirement-contract-proof-r1.log`, with its readable proof
script beside it:42 exact inherited owners,51 active targets,10 target-only owners
omitted, the frozen snapshot tuple, and a future descriptor/tag validation deriving43
with exact raw bytes and51 active-only future baseline rows. This is local contract
proof; it is not a signed installed update. The real register gate and11 Head
upgrade-contract cases pass at `tmp/lane-f-wp2-retirement-register-r1.log` and
`tmp/lane-f-pre-green-delete-focused-r1.log` with archived XML. Independent Sol
review found no actionable source defect.

Hosted wrapper checkpoint9d7451d37 ran its10 negative checks successfully, then
failed because GitHub's dot-sourced pwsh wrapper inherited the last expected child
exit1. The test script now explicitly exits0 only after every assertion and cleanup
succeeds; its original hosted failure and matching local invocation are
`tmp/lane-f-wp2-wrapper-hosted-native-failure.log` and
`tmp/lane-f-wp2-wrapper-hosted-shell-regression.log`. This correction still needs a
fresh hosted run. Required signed predecessor-to-target qualification remains open.


## 2026-09-30 WP2 2c tooling (local proof; installed round open)

On `codex/lane-f-wp2c`, based on `ca12f00e6`, the launcher now shares the
previous-release resolver/staging with a direction parameter. The mutually
exclusive `--downgrade-from` resolves `downgrade-to-release` and instructions
seed main's release, exercise the Lane F candidate, then over-install the older
release. `mustWatch:downgrade-after-lane-f` projects the exact five WP2 2c
assertions and phase-labelled evidence checklist into the existing brief/plan.
The existing generic mode filter and verdict checker accept the row without a
new schema, mode registry, or lifecycle redesign. Candidate mustTouch evidence
is captured before downgrade; a legacy setting is seeded before migration so
the backup/restore observation cannot pass vacuously.

Local checks and readable output are under `tmp/wp2c-tooling/`:
- `python -m pytest scripts/sandbox/test_sandbox_launch_upgrade.py scripts/sandbox/test_sandbox_coverage_mode_filter.py -q`: 35 passed, 27 subtests passed (`tests.log`).
- `python -m pytest scripts/sandbox/test_derive_round_plan.py scripts/sandbox/test_sandbox_launch_charter.py -q`: 57 passed (`plan-charter.log`).
- `python scripts/sandbox/gen_coverage_brief.py --check`: all 21 cohorts, 16 surfaces and 5 shapes classified (`register.log`).
- `node scripts/ci/check-sandbox-authorization-field.mjs`: passed (`authorization.log`).
- Negative control: temporarily removing the downgrade row makes the new
  end-to-end brief/plan test fail specifically on its missing id (`red.log`);
  restoring the exact register bytes makes it pass (`green.log`). The test also
  proves that omitting its verdict fails the finalization checker.

No `.js`/`.mjs` edits; script lint is not applicable. No governance registry gate
references this register. `check_coverage.py` is a round finalization checker,
requiring a manifest and real evidence; its mustWatch acceptance/rejection is
exercised by the new test. A local log wrapper initially failed to print Unicode
under cp1252; rerunning with explicit UTF-8 output produced the logs above.
No Gradle, dev stack, Sandbox, installer, commit or push was run. Signed installed
proof at stage E remains open; WP2 2e's documented restore is a dependency of
that proof, and the generated checklist records missing documentation as a gap.
These tooling checks do not close the installed-round acceptance item.
