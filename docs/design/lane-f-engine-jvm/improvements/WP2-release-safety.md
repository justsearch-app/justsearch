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

## 2026-09-30 root amendment to 2b.2

The marker means this directory may contain data written by build X with store
versions Y, rather than certifying that every store opened successfully. Write it
under the instance lock before any versioned store opens or migrates, so a crash
after partial migration still warns a later older build. Retain the maximum app
version and each maximum store version, using the register-bound code constants;
degraded startup does not affect this evidence. Corrupt or unreadable marker bytes
are logged once, treated as absent for comparison, and atomically rewritten with
this build's values without blocking boot. Newer-data warnings use the existing
Health condition surface and name settings.v<old>.bak.json and .corrupt-* files.

Implementation choice: the existing pack-version comparator discards prerelease
precedence, so the marker compares numeric SemVer components and prerelease
identifiers locally. Store numbers remain direct references to all 18 bound code
constants; their visibility is public only to let the composition root read them
without opening stores. The marker is unversioned derived metadata (register
currentVersion 0), with unreadable-byte reconstruction rather than future-schema
refusal. Health condition data.newer_build uses the same notice-only exemption as
settings.reset_from_corrupt; no frontend mapping or new UI surface is required.

## 2026-09-30 2b implementation and local proof

2b.1 preserves original bytes through AtomicFileWrites.replaceStrict immediately
before the writable legacy migration. An existing backup is retained; an invalid
backup destination fails preservation without quarantining valid settings.
2b.2 records all 18 register-bound constants (currently settings 5, operations 5,
jobs 21), retains unknown store fields, and publishes data.newer_build on Health
without changing readiness. Writable corrupt markers are rewritten; unreadable
and unwritable markers log once and boot continues. The pre-store call remains
after instance-lock acquisition and before SqliteOperationStore and resolveConfig.

Local proof on codex/lane-f-wp2b, without a dev stack or publication:

| Check | Result | Local evidence under tmp/wp2b/ |
|---|---|---|
| Backup negative control: remove preservation, then restore exact bytes | Missing-backup and preservation-failure regressions red (2/3); restored green (3/3) | backup-red-local-temp.log, backup-green.log and their XML directories |
| Marker negative control: disable comparison, then restore exact bytes | Three newer-data notice regressions red (3/8); restored green (8/8) | marker-red.log, marker-green.log and their XML directories |
| Existing settings classes: ContextLengthMigration, PersistenceMode, RecoveryEvidence, Revision | 8 + 44 + 5 + 13 passed | UiSettingsStore*Test.log and corresponding XML directories |
| SettingsWriterArchitectureTest | 4 passed; UI main/test compilation passed transitively | settings-architecture.log and settings-architecture-xml/ |
| Spotless in all eight touched Java modules | All BUILD SUCCESSFUL | spotless-*.log |
| Recoverability, runtime closure, readiness vocabulary | 52 durable authorities; 3768 files, zero violations; all 62 codes have producers | store-recoverability.log, runtime-closure.log, reason-codes.log |
| Release register projection tests | 19 passed | release-projection.log |

Commands: every Gradle invocation first checked the grant file named in the
assignment. All ran sequentially with -PskipWebBuild=true, --offline and
-Dorg.gradle.java.installations.paths pointing to the installed JDK 21 and 25.
GRADLE_USER_HOME and JVM temporary files were inside tmp/wp2b/. A cached wrapper
and copied dependency cache avoided denied network access; an initial temp-dir
cleanup failure was rerun in the worktree and is excluded from regression proof.

```powershell
# For each module: app-services, app-agent, app-api, app-engine,
# app-observability, configuration, ui, worker-core
./gradlew.bat :modules:<module>:spotlessApply -PskipWebBuild=true --offline
# Common installed-JDK option described above was present on every successful run.
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStoreMigrationBackupTest -PskipWebBuild=true --offline
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.DataVersionMarkerTest -PskipWebBuild=true --offline
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStoreContextLengthMigrationTest -PskipWebBuild=true --offline
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStorePersistenceModeTest -PskipWebBuild=true --offline
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStoreRecoveryEvidenceTest -PskipWebBuild=true --offline
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStoreRevisionTest -PskipWebBuild=true --offline
./gradlew.bat :modules:ui:test --tests io.justsearch.ui.SettingsWriterArchitectureTest -PskipWebBuild=true --offline
node scripts/ci/check-store-recoverability.mjs
node scripts/ci/check-runtime-manifest-closure.mjs
node scripts/ci/check-readiness-reason-codes.mjs
node --test scripts/release/app-release-assets.test.mjs
```

No frontend files changed, so frontend typecheck/unit commands do not apply.
These close the bounded 2b local checks; the separate installed downgrade round
at 2c/E remains required on the release candidate. No commit or push was made.

## 2026-09-30 2b review correction plan

Review at 172df0767 found two surviving defects: LauncherEnvironment opens
operations.db without the marker, and separate Engines may share a settings path
despite holding different data-directory locks. The launcher already loads the
EngineProcessResources app-api SPI; BoundaryRulesTest forbids a concrete Engine
import. Add a pre-store marker hook to that existing contract, with build values
and comparison owned by DataVersionMarker, and invoke it under the launcher lock
before operations.db. HeadlessApp uses the same Engine hook directly because its
process resources are assembled later. Audit all register-bound production entry
paths and prove the real CLI SPI call precedes migration, including failed boot.

Publish backups through the existing AtomicFileWrites owner using a forced sibling
temp and atomic hard-link creation. Unlike ATOMIC_MOVE without REPLACE_EXISTING,
whose collision behavior is provider-specific, link creation fails if the target
already exists. This protects independent JVMs without another persistent lock
authority. Unsupported link creation aborts preservation without migrating or
quarantining valid settings; do not fall back to replacement. Use a deterministic
two-writer regression with differing prepared bytes to prove the first published
snapshot survives, then rerun existing store/helper and boundary checks. Show each
regression red with its production fix temporarily removed, then restored green.

Entry-path audit at 172df0767, independently re-read before implementation:
H is HeadlessApp.java:1114; C is LauncherEnvironment.java:188. C calls the permitted
EngineProcessResources SPI, whose sole provider delegates at
DefaultEngineProcessResources.java:57 to the same DataVersionMarker.java:41 hook
used by H. No dependency edge, second provider, or duplicated version projection
was added. Process-resource construction before C opens no registered store.

| Boot/entry path | Marker raised before stores |
|---|---|
| Desktop/normal HeadlessApp.main; supervisor restart and dead-Engine relaunch | H, under AppInstanceLock before operations and resolveConfig |
| Headless evaluation boot (same main, retained corpus selected later) | H |
| LauncherBootstrap.main -> Launcher.main -> reindex (Launcher.java:108-115) | C |
| Launcher seed (Launcher.java:118-125) | C |
| Launcher verify (Launcher.java:128-135) | C |
| Launcher snapshot (Launcher.java:138-145) | C |
| Launcher smoke, --smoke and option-only smoke (SmokeDriver.java:38) | C |

All 18 versionSource rows were traced to their production opening owners. H/C
below means the same HeadAssembly/authority owner is also used by the CLI; stores
opened later by requests inherit the earlier boot marker.

| Register IDs | Opening owner (source line at audited base) | Marker |
|---|---|---|
| ui-settings | HeadlessApp.java:776 (CLI settings are in memory) | H |
| operations-db | HeadlessApp.java:1117 / LauncherEnvironment.java:188 at base, immediately after the new hooks | H/C |
| durable-grants, watched-roots | OperationAuthority.java:80 / WatchedRootsState.java:22-25 | H/C |
| plugin-allowlist | LocalApiServer.java:186-188 | H |
| installed-packs | ServicePhase.java:289 -> AiPackImportService.java:123 | H |
| conversations | ConversationApiAssembly.java:271 / ResourceApiModule.java:335 | H |
| memories | HeadAssembly.java:915-916 | H/C |
| agent-runs, run-events | HeadAssembly.java:597 / AgentRunStore.java:72 | H/C |
| file-operation-journal | AgentToolFactory.java:117 | H/C |
| feedback-capture-preference, feedback-records | HeadAssembly.java:764-770 / AgentDispositionWiring.java:56-59; KnowledgeSearchController.java:218,238 | H/C (search-controller path H only) |
| jobs-db | KnowledgeServer.java:1042,1056 | H |
| index-generations | KnowledgeServer.java:1116 | H |
| entity-clusters | KnowledgeServer.java:4284-4285 | H |
| ai-install-contract | KnowledgeServer.java:3432,3718,4065,4616; AiInstallService.java:709; RuntimeActivationService.java:1671 | H |
| ai-install-attempt-memory | AiInstallService.java:1334 | H |

Other main methods were checked: offline SSOT/OpenAPI tools, AOT class-touch
training, benchmarks, validators, and extraction sandbox children do not open
these registered durable stores. No standalone Worker boot remains. API and MCP
request handlers are downstream of H, not independent unmarked store entry points.

## 2026-09-30 2b review correction proof

Both review defects are fixed on base 172df0767, without a commit, push, or dev
stack. Independent read-only review found no additional registered-store entry
path or substantive defect. The tables above enumerate every production entry
path and all 18 versionSource authorities; the hook lines remain H=1114 and C=188.
CLI notices are logged by the shared Engine hook, before store opening. The
existing Headless Health notice and marker high-water semantics are unchanged.

Negative controls restored production source byte-for-byte before green runs:
removing the CLI SPI call made LauncherDataVersionMarkerTest fail 4/4 (including
missing marker before real operations.db opening); restoring it passed 4/4.
Replacing createLink with the former atomic replacement made
AtomicFileWritesCreateOnceTest fail 3/3: the ordered concurrent regression read
the second candidate instead of the first. Restoring create-once passed 3/3.
The concurrent regression uses two threads and real file publication; this is
not a multi-process execution claim. Unsupported hard links produce IOException,
clean the temp, and refuse migration while preserving valid settings.

Local evidence is retained in tmp/wp2b2: cli-red.log, cli-green.log,
backup-red.log, backup-green.log, each class-named log and copied *-xml directory,
spotless-<module>.log, gate logs, results.json, and changes.patch (including the
two new test files). No required local check is unresolved. This correction does
not substitute for the separate installed downgrade release-candidate exercise.

The grant was absent from 20:26 until 21:02 Europe/Berlin and polled every 60s.
Every Gradle invocation checked gradle-wp2b2 immediately before launch; all ran
sequentially. Commands below used the existing local cache via
GRADLE_USER_HOME=tmp/wp2b/gradle-home, workspace TMP/TEMP and java.io.tmpdir, and
this additional toolchain argument (no dependency or lockfile changes):
`-Dorg.gradle.java.installations.paths=C:/Users/Elias/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2,F:/scoop/apps/temurin25-jdk/current`.

Commands and summary lines (each command executed separately):

```text
./gradlew.bat :modules:app-api:spotlessApply -PskipWebBuild=true --offline
./gradlew.bat :modules:app-engine:spotlessApply -PskipWebBuild=true --offline
./gradlew.bat :modules:app-launcher:spotlessApply -PskipWebBuild=true --offline
./gradlew.bat :modules:app-services:spotlessApply -PskipWebBuild=true --offline
./gradlew.bat :modules:configuration:spotlessApply -PskipWebBuild=true --offline
./gradlew.bat :modules:ui:spotlessApply -PskipWebBuild=true --offline
# All six: BUILD SUCCESSFUL
./gradlew.bat :modules:app-launcher:test --tests io.justsearch.applauncher.LauncherDataVersionMarkerTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 4 tests, 0 failures/errors
./gradlew.bat :modules:configuration:test --tests io.justsearch.configuration.persistence.AtomicFileWritesCreateOnceTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 3 tests, 0 failures/errors
./gradlew.bat :modules:configuration:test --tests io.justsearch.configuration.persistence.AtomicFileWritesTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 12 tests, 0 failures/errors
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.DataVersionMarkerTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 8 tests, 0 failures/errors
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.DefaultEngineProcessResourcesTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 3 tests, 0 failures/errors
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStoreMigrationBackupTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 3 tests, 0 failures/errors
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStoreContextLengthMigrationTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 8 tests, 0 failures/errors
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStorePersistenceModeTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 44 tests, 0 failures/errors
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStoreRecoveryEvidenceTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 5 tests, 0 failures/errors
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStoreRevisionTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 13 tests, 0 failures/errors
./gradlew.bat :modules:app-launcher:test --tests io.justsearch.app.launcher.BoundaryRulesTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 1 tests, 0 failures/errors
./gradlew.bat :modules:app-launcher:test --tests io.justsearch.app.launcher.LayeringEnforcementTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 14 tests, 0 failures/errors
./gradlew.bat :modules:app-launcher:test --tests io.justsearch.applauncher.LauncherEnvironmentCloseTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 12 tests, 0 failures/errors
./gradlew.bat :modules:app-api:test --tests io.justsearch.app.api.ArchitectureRulesTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 3 tests, 0 failures/errors
./gradlew.bat :modules:ui:test --tests io.justsearch.ui.SettingsWriterArchitectureTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 4 tests, 0 failures/errors
# Total green: 137 tests across 15 focused classes, 0 failures/errors
# First two focused commands also ran with the temporary negative controls:
# CLI: BUILD FAILED, 4 tests / 4 failures; backup: BUILD FAILED, 3 tests / 3 failures.
node scripts/ci/check-store-recoverability.mjs
# gate OK: 52 durable authorities, 28 corruption policies, 0 awaiting a row
node scripts/ci/check-runtime-manifest-closure.mjs
# 3772 files scanned, 0 violations
node scripts/ci/check-readiness-reason-codes.mjs
# gate OK: 62 emittable codes, 57 worded rows, all producers present
node --test scripts/release/app-release-assets.test.mjs
# tests 19; pass 19; fail 0
node scripts/architecture/module-deps.mjs --check-canonical
# module-deps check: OK (files=1)
git diff --check
# no output
git diff --stat
git diff | grep -P '^\+.*[^\x00-\x7F]'
# no output; complete patch including new files independently has 0 added non-ASCII lines
```

No frontend changes; frontend typecheck and unit tests do not apply. All Java
writes used UTF-8-safe tools. Existing settings migration, persistence, revision,
recovery, marker, resource ownership and architecture tests passed alongside the
new regressions. Final evidence records the current patch and tested base.

Closeout: the worktree remains dirty for the requested parent review; publication
is explicitly excluded by the no-commit/no-push instruction. World-state re-read
shows this lane ACTIVE with 14 changed paths. The shared helper cleanup/lifecycle
mutation was not run because its shared register is outside this worktree; no
helper or dev stack was started by this correction. World-state reported one
ownerless singleton and two identity-refused ui-shot records, without mutation.

## 2026-09-30 backup filesystem compatibility amendment

Owner review supersedes the hard-link-only refusal above: backups are a safety
net and must not prevent valid legacy settings from loading and migrating.
AtomicFileWrites keeps forced sibling-temp hard-link publication, falling back on
UnsupportedOperationException or a non-collision FileSystemException to
CREATE_NEW + WRITE + force. The fallback never replaces an incumbent, but crash
or write failure may leave a partial backup; it is not all-or-nothing publication.
UiSettingsStore logs one WARN naming the backup path and cause for any backup I/O
failure, including a non-regular existing destination, and continues migration.
No new injection seam is needed: FileAccess exercises filesystem behavior and a
static writer fake exercises load while all backup writes throw IOException.
The former preservation-failure rethrow is retired. No marker or entry-path
behavior changes in this amendment.

Compatibility amendment verification: both required regressions are red then
green. AtomicFileWritesCreateOnceTest ran against the hard-link-only behavior:
4 tests, 2 failures (UnsupportedOperationException wrapped as IOException and
FileSystemException with Incorrect function). The fallback passed all 4. With
only the backup warning temporarily reverted to UncheckedIOException, the
corrected schema-v1 UiSettingsStoreMigrationBackupTest had 4 tests, 2 failures
(invalid destination and all writes denied). The production source was restored
byte-for-byte and all 4 passed, including contextLength 4096 -> 0 migration.
Two fixture issues were corrected before final proof: a duplicate override and a
schema-v2 assertion for a migration that applies only before v2, as independently
confirmed by migrate and UiSettingsStoreContextLengthMigrationTest. Neither is
counted as a required negative control. The green settings XML contains exactly
one backup WARN per failing scenario, with the path and IOException cause.

Current amendment evidence is under tmp/wp2b3: backup-red/green.log and copied
selected-class XML, settings-v1-red.log, settings-green.log and XML, class-named
logs/XML, spotless logs, store-recoverability.log, runtime-closure.log,
results.json and changes.patch. XML totals select the named class and its nested
suites; unrelated output left by an earlier compile attempt is excluded. All 101
tests across 9 classes passed. This was local fake-driven filesystem coverage;
no actual exFAT volume, installed Engine, or dev stack was run. No commit/push,
no sub-agents, no grant polling. The owner's explicit Gradle grant covered
sequential focused commands. Existing local offline cache/toolchain and workspace
temp setup from the preceding proof were reused.

Exact focused commands and summary lines (same toolchain argument as above):

```text
./gradlew.bat :modules:configuration:spotlessApply -PskipWebBuild=true --offline
./gradlew.bat :modules:app-services:spotlessApply -PskipWebBuild=true --offline
# Both: BUILD SUCCESSFUL
./gradlew.bat :modules:configuration:test --tests io.justsearch.configuration.persistence.AtomicFileWritesCreateOnceTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 4 tests, 0 failures/errors
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStoreMigrationBackupTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 4 tests, 0 failures/errors
./gradlew.bat :modules:configuration:test --tests io.justsearch.configuration.persistence.AtomicFileWritesTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 12 tests, 0 failures/errors
./gradlew.bat :modules:configuration:test --tests io.justsearch.configuration.persistence.ContendedFileReadsTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 7 tests, 0 failures/errors
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStoreContextLengthMigrationTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 8 tests, 0 failures/errors
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStorePersistenceModeTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 44 tests, 0 failures/errors
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStoreRecoveryEvidenceTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 5 tests, 0 failures/errors
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.UiSettingsStoreRevisionTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 13 tests, 0 failures/errors
./gradlew.bat :modules:ui:test --tests io.justsearch.ui.SettingsWriterArchitectureTest -PskipWebBuild=true --offline
# BUILD SUCCESSFUL; 4 tests, 0 failures/errors
# Total: 101 tests, 0 failures/errors
node scripts/ci/check-store-recoverability.mjs
# gate OK: 52 durable authorities, 28 corruption policies, 0 awaiting a row
node scripts/ci/check-runtime-manifest-closure.mjs
# 3772 files scanned, 0 violations
git diff --check
# no output
git diff --stat
git diff | grep -P '^\+.*[^\x00-\x7F]'
# no output; complete patch including new files also has 0 added non-ASCII lines
```

No required local amendment check remains unresolved. The partial-backup crash
limitation of CREATE_NEW fallback is intentional and documented above. Existing
marker and entry-path behavior is unchanged; previous proof for those paths
remains applicable. The dirty worktree is retained for parent review under the
standing no-commit/no-push rule.
