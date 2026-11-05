---
schema: test-intent.v1
task: fu-1
author-role: builder
author-session: 01a1aa6d-c8aa-75d0-b675-76e7ae21f707
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncCoverageEvidenceTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "D1-D5: complete current whole-root enumeration clears persisted incomplete coverage through periodic sync, Verify folder, and recorded Reindex with either force setting; a single repaired file does not. R1: empty-message failures and entry interruption remain explicit failures. R2/R6: legitimate exclusions and ownership boundaries certify the full eligible scope; unchanged nonempty roots retain indexed completion and empty roots complete empty. R3: repeated identical incomplete observations invalidate older clean results, including boot. No new saved fields. R6: a successful old-peer response without explicit coverage evidence cannot clear or save completed-empty. Unchanged content needs no admission yet clears the durable warning as indexed. R2: boot, periodic, Verify folder and recorded walks compare the complete exclusion-policy digest at start and completion.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "a recorded walk carries, in memory, a digest of the complete effective exclusion policy (frozen globs plus the literal exclusions) taken when that walk starts enumerating; its clean completion clears INCOMPLETE_COVERAGE only if that digest equals the digest of the current complete policy at completion.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "a watched folder's INCOMPLETE_COVERAGE warning clears when a complete whole-root check succeeds (periodic sync, Verify folder, recorded Reindex), without an Engine restart, and never on partial, skipped, unverified, failed or stale evidence.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "Nonempty repaired roots do not become falsely empty; empty/all-excluded roots become completed-empty. Unchanged indexed files need no new content write to permit clearing.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "Legacy and current watched-roots files retain membership, labels, completion semantics and removal markers through clearing and reload.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "a narrower request scope (single file, subtree, extra request exclusions, policy changed since preparation) is not whole-root evidence.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-1",
          "scenario": "R2",
          "quote": "Single-file, subtree and additionally restricted recorded walks cannot clear the root warning. Legitimate exclusions and ownership boundaries permit complete eligible coverage.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "the coverage count must have explicit presence; an absent value is not coverage evidence (no clearing, no completed-empty)",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "the policy-identity rule applies to every producer that can clear (boot, periodic, Verify folder, recorded Reindex): digest at walk start must equal the current digest at completion, else no clearing for that walk",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "checks must observe behaviour, not API presence",
          "location": "task fu-1 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/fu-1.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#D1PeriodicCoverageClearsPersistedWarning",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#D2VerifyFolderClearsPersistedWarning",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#D3RecordedReindexClearsPersistedWarning",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#D4OneRepairedFileCannotClearButWholeRootCan",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#D5LegacyReindexRejectsUnverifiedResponseIndependentlyOfSkipped",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R3RepeatedIncompleteObservationInvalidatesOlderCleanCompletion",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R6EmptyRepairedRootCompletesWithoutTimestamp",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R2LegitimatePolicyAndOwnershipExclusionsPermitWholeEligibleCoverage",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncCoverageEvidenceTest.java#R1EmptyMessageExceptionIsAnExplicitSyncFailure",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncCoverageEvidenceTest.java#R1CancelledAndInterruptedWalksNeverCompleteCoverage",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R6UnchangedEligibleFilesRemainCoverageEvidenceWithoutAdmission",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R6AbsentCoverageCannotClearOrCompleteNonemptyRootAsEmpty",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R2LiteralPolicyExpansionCannotClearPartialWalk"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java"
      ],
      "class": "New",
      "behaviour": "R1/R2: skipped, deferred, unverified, failed, interrupted, missing-volume and narrower-scope results cannot certify root coverage. A digest of frozen globs and live literal exclusions is captured at enumeration start; a policy change before completion prevents clearing, Stable literal exclusions still permit clearing. R4: a removed/re-added registration rejects a result awaiting application, and its new boot walk can complete. R5/R7: persisted warning, labels and completion reload unchanged until fresh boot coverage succeeds. R8: other walk errors and unrelated roots remain independent.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "a recorded walk carries, in memory, a digest of the complete effective exclusion policy (frozen globs plus the literal exclusions) taken when that walk starts enumerating; its clean completion clears INCOMPLETE_COVERAGE only if that digest equals the digest of the current complete policy at completion.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "a watched folder's INCOMPLETE_COVERAGE warning clears when a complete whole-root check succeeds (periodic sync, Verify folder, recorded Reindex), without an Engine restart, and never on partial, skipped, unverified, failed or stale evidence.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "Nonempty repaired roots do not become falsely empty; empty/all-excluded roots become completed-empty. Unchanged indexed files need no new content write to permit clearing.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "Legacy and current watched-roots files retain membership, labels, completion semantics and removal markers through clearing and reload.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-words",
          "quote": "a narrower request scope (single file, subtree, extra request exclusions, policy changed since preparation) is not whole-root evidence.",
          "location": "task fu-1 request"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-1",
          "scenario": "R2",
          "quote": "Single-file, subtree and additionally restricted recorded walks cannot clear the root warning. Legitimate exclusions and ownership boundaries permit complete eligible coverage.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/fu-1-preservation.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R1NonqualifyingSyncCannotClear",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R2RestrictedRecordedScopeCannotClear",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R2LiteralPolicyChangedDuringEnumerationCannotClear",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R4RemovedRegistrationRejectsAResultAwaitingApplication",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R5ReloadWhileUnreadableRetainsWarningAndBootAfterRepairClears",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java#R8SuccessfulSyncLeavesOtherWalkErrorsAndUnrelatedRootsAlone"
      ]
    }
  ]
}
```

## Repair evidence commands

This repair gives `eligible_files` explicit protobuf presence. Missing coverage leaves the
warning and durable completion state alone. Boot, periodic sync, Verify folder and recorded
Reindex use the same digest of the complete effective glob/literal exclusion policy.
The helper uses the existing empty effective policy when no configuration store is published,
as the live matcher already does; an invalid policy cannot certify coverage.

R6's unchanged-file check moved from the worker class to the engine class. It retains the
success, skipped, verification, zero-admission and queue non-write assertions, and replaces
the rejected getter-presence/count oracle with observable warning clearing, nonempty indexed
completion and a store reload. The mock indexed row includes its owning collection. The
SQLite queue stays real; only the sync admission observer delegates to it. All other existing
assertions remain. R1/D5 mock replies include wire field 7 so the absent-count guard cannot
mask their existing negative-outcome assertions; the same bytes parse on the base.

The new R6 old-peer and R2 live-policy-expansion checks turn verifier-1's two counterexamples
into tracked tests. R6 tests periodic, Verify and legacy Reindex with the actual old-schema
wire response; R2 tests boot, periodic and Verify; the existing recorded-policy test remains.
Every repair selector observes behaviour on the base. No getter-presence assertion remains.

`.agents/preflight` is absent. No shared dev stack is started. Checkouts use their own build
folders, test profiles, filesystem fixtures, SQLite queues and watched-roots stores. Shared
resources touched are Gradle caches, reusable daemons and js-gradle-slot/admission locks.
Nothing is committed and acceptance records are reserved for the independent acceptor.

Create separate shared clones in `$TMPDIR` (already done in this session), copy only the two
test sources, and run one background Gradle command at a time:

```bash
git clone --quiet --shared --no-checkout . "$TMPDIR/before"
git -C "$TMPDIR/before" checkout --quiet --detach bbcb4090fc9ae41e520b7e7bd3fb7142b6275285
git clone --quiet --shared --no-checkout . "$TMPDIR/fu1-base"
git -C "$TMPDIR/fu1-base" checkout --quiet --detach b21749f0e07917daceb0df14e57bd2e9163cb91b
for tree in "$TMPDIR/before" "$TMPDIR/fu1-base"; do
  for file in modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIncompleteCoverageTest.java modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncCoverageEvidenceTest.java; do
    cp "$file" "$tree/$file"
  done
done
```

From `$TMPDIR/before` (expected assertion failures), save the log as
`tmp/fu1-repair/pre-repair-2.log` in the candidate and the engine JUnit XML under
`tmp/fu1-repair/pre-repair/`:

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:app-engine:test --tests '*EngineIncompleteCoverageTest.R6AbsentCoverageCannotClearOrCompleteNonemptyRootAsEmpty' --tests '*EngineIncompleteCoverageTest.R2LiteralPolicyExpansionCannotClearPartialWalk' --tests '*EngineIncompleteCoverageTest.R6UnchangedEligibleFilesRemainCoverageEvidenceWithoutAdmission' -PskipWebBuild=true --continue
```

Run this command from `$TMPDIR/fu1-base` (expected assertion failures), then from the
candidate. Save the respective logs as `tmp/fu1-repair/base-focused.log` and
`tmp/fu1-repair/candidate-evidence.log`. Copy both classes' JUnit XML files to
`tmp/fu1-repair/base-focused/` and `tmp/fu1-repair/after-focused/` immediately after each run.
The class selectors execute every method named by the entries above; parameterized names
include their method names and resolve with the repository's unchanged XML selector.

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:app-engine:test --tests '*EngineIncompleteCoverageTest' --rerun :modules:worker-services:test --tests '*SyncCoverageEvidenceTest' --rerun -PskipWebBuild=true --continue
```

The request explicitly permits writing the evidence JSON. The following collector regenerates
both files from those saved commands and XML using the existing schema, selector, summary,
classification and digest helpers. Its extra `producer.writer` states how it was produced;
`producer.script` and its digest identify the gate's required evidence format implementation.
No run or outcome is inferred from API availability. Execute from the candidate:

```bash
node --input-type=module <<'JS'
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
const load = rel => import(pathToFileURL(path.resolve(rel)));
const { EVIDENCE_SCHEMA, EVIDENCE_PRODUCER, contentDigest, evidenceSelfDigest, checkFile } =
  await load('scripts/governance/gates/test-intent/evidence.mjs');
const { gradleTarget, parseJUnitXml, selectJUnitCases, summarize, classifyFailBefore } =
  await load('scripts/governance/gates/test-intent/evidence-runners.mjs');
const git = (args, env = process.env) => {
  const r = spawnSync('git', ['-c', 'core.splitIndex=false', '-c', 'core.fsmonitor=false', ...args], { env, encoding: 'utf8' });
  if (r.status !== 0) throw Error(r.stderr);
  return r.stdout.trim();
};
const changeset = fs.readFileSync('gates/test-intent/.changesets/fu-1.md', 'utf8');
const { entries } = JSON.parse(/```json\n([\s\S]*?)\n```/.exec(changeset)[1]);
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'fu1-evidence-tree-'));
let tree;
try {
  const objects = path.resolve(git(['rev-parse', '--git-path', 'objects']));
  fs.mkdirSync(path.join(scratch, 'objects'));
  const env = { ...process.env, GIT_INDEX_FILE: path.join(scratch, 'index'),
    GIT_OBJECT_DIRECTORY: path.join(scratch, 'objects'), GIT_ALTERNATE_OBJECT_DIRECTORIES: JSON.stringify(objects) };
  git(['read-tree', 'HEAD'], env);
  const changed = git(['diff', '--name-only', 'HEAD']).split('\n').filter(Boolean);
  if (changed.length) git(['add', '--', ...changed], env);
  tree = git(['write-tree'], env);
} finally { fs.rmSync(scratch, { recursive: true, force: true }); }
const directory = 'tmp/fu1-repair';
const command = "env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:app-engine:test --tests '*EngineIncompleteCoverageTest' --rerun :modules:worker-services:test --tests '*SyncCoverageEvidenceTest' --rerun -PskipWebBuild=true --continue";
const run = (kind, exitCode) => ({ command, cwd: kind === 'after-focused' ? '.' : '$TMPDIR/fu1-base', exitCode,
  output: fs.readFileSync(`${directory}/${kind === 'after-focused' ? 'candidate-evidence' : 'base-focused'}.log`, 'utf8') });
const cases = (kind, id) => {
  const target = gradleTarget(id);
  return selectJUnitCases(parseJUnitXml(fs.readFileSync(
    `${directory}/${kind}/TEST-${target.fqcn}.xml`, 'utf8')), target);
};
for (const entry of entries) {
  const evidence = {
    schema: EVIDENCE_SCHEMA,
    producer: { script: EVIDENCE_PRODUCER, scriptDigest: contentDigest(fs.readFileSync(EVIDENCE_PRODUCER, 'utf8')),
      writer: 'Builder-authorized inline collector in fu-1.md, using the evidence schema/parsers; Gradle commands run separately with js-gradle-slot.' },
    createdAt: new Date().toISOString(), revision: git(['rev-parse', 'HEAD']), tree,
    environment: { platform: process.platform, arch: process.arch, osRelease: os.release(), node: process.version,
      java: spawnSync('java', ['-version'], { encoding: 'utf8' }).stderr.trim().split('\n')[0], ci: false },
    runs: [run('after-focused', 0)],
    checks: entry.newChecks.map(id => ({ id, file: checkFile(id), runner: 'gradle',
      contentDigest: contentDigest(fs.readFileSync(checkFile(id), 'utf8')),
      ...summarize(cases('after-focused', id)), cases: cases('after-focused', id).map(({name, status}) => ({name, status})) })),
    failBefore: entry.repaired ? { ref: 'b21749f0e07917daceb0df14e57bd2e9163cb91b',
      revision: 'b21749f0e07917daceb0df14e57bd2e9163cb91b', overlays: [], patches: [],
      runs: [run('base-focused', 1)], checks: entry.newChecks.map(id => ({ id,
        ...classifyFailBefore({ runner: 'gradle', cases: cases('base-focused', id), buildOutput: fs.readFileSync(`${directory}/base-focused.log`, 'utf8') }) })) } : null,
  };
  if (entry.repaired) evidence.preRepairCounterexamples = {
    revision: 'bbcb4090fc9ae41e520b7e7bd3fb7142b6275285',
    command: "env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:app-engine:test --tests '*EngineIncompleteCoverageTest.R6AbsentCoverageCannotClearOrCompleteNonemptyRootAsEmpty' --tests '*EngineIncompleteCoverageTest.R2LiteralPolicyExpansionCannotClearPartialWalk' --tests '*EngineIncompleteCoverageTest.R6UnchangedEligibleFilesRemainCoverageEvidenceWithoutAdmission' -PskipWebBuild=true --continue",
    exitCode: 1, output: fs.readFileSync(`${directory}/pre-repair-2.log`, 'utf8'),
    checks: entry.newChecks.filter(id => /#(R6AbsentCoverage|R2LiteralPolicyExpansion)/.test(id)).map(id => ({ id,
      ...classifyFailBefore({ runner: 'gradle', cases: cases('pre-repair', id), buildOutput: '' }) })),
  };
  evidence.digest = evidenceSelfDigest(evidence);
  fs.writeFileSync(entry.evidence, JSON.stringify(evidence, null, 2) + '\n');
  for (const c of evidence.checks) if (c.outcome !== 'passed') throw Error(`${c.id}: ${c.outcome}`);
  for (const c of evidence.failBefore?.checks ?? []) if (c.classification !== 'assertion') throw Error(`${c.id}: ${c.classification}`);
  console.log(`${entry.evidence}: ${evidence.checks.length} passing selectors`);
}
JS
```

Required final checks (Gradle runs in the background, one at a time):

```bash
node scripts/governance/run.mjs --gate test-intent --mode gate
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew :modules:app-services:test :modules:app-engine:test :modules:worker-services:test :modules:configuration:test :modules:ipc-common:test -PskipWebBuild=true --continue
env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced
```

`java-prepush.mjs` invokes `js-gradle-slot ./gradlew` itself; no nested slot is taken.

## Repair observations

- Pre-repair HEAD: seven cases executed; the three absent-count routes and boot policy
  expansion failed by assertion (four failures). Periodic/Verify policy-expansion guards and
  the rewritten unchanged-file behaviour passed. No skips or compilation failures.
- Base: engine 40 cases, 18 assertion failures; worker two cases, two assertion failures.
  All 13 repair selectors have a behavioural assertion failure; some parameterized routes
  already passed on the base. All 15 preservation cases passed with none skipped.
- The strengthened old-peer check also fails on the base through legacy Reindex because it
  clears the warning without coverage evidence. The verifier's original completed-empty-only
  oracle passes on that base, which set a nonempty timestamp. Boot policy expansion fails on
  both base and pre-repair HEAD. The unchanged-file check fails on the base because the
  durable warning remains, after the success and zero-admission assertions have passed.
- Final focused candidate: engine 40/40 and worker 2/2 passed, none skipped; command exit 0.
  Both test tasks were freshly executed with `--rerun`.
- Evidence regenerated: 13 repair selectors (27 cases), six preservation selectors (15 cases).
  The request-authorized collector records actual command logs, selected XML and source
  digests; its writer is identified explicitly. No compatibility patch or new production API
  is used to make the base compile.
- Test-intent gate: exit 1, only `acceptance-missing`; no source or evidence errors remain.
- Module suites: exit 1 after 16m01s, exactly the six listed environment failures; no other
  failures. App-services: 5,106 tests, zero failures, 11 skipped. App-engine: 930 tests, two
  failures, nine skipped; all 40 FU-1 engine cases passed. Worker-services XML: 4,204 tests,
  four failures, 24 skipped (console: 4,203 / four / 23, omitting a disabled container); both
  worker repair checks passed. IPC-common: four passed. Configuration's 370-test successful
  result was reused, not freshly executed. Logs: `tmp/fu1-repair/module-suites.log`; exact
  totals, messages and failure XML: `tmp/fu1-repair/module-results/`.
  Failures: LinuxRootMountInfoTest lacks `/dev/disk/by-uuid`; the three named
  WatcherUnavailabilityScopeTest cases; EngineForgetInProcessTest R9 setup/inconclusive at
  line 554 (no exact target projection refusal), and S55 setup lacks real-volume evidence.
  R9 stopped at the same setup location as the verifier's baseline, rather than the later
  migration-terminal assertion its candidate run reached. No known flaky failure appeared.
- `env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced`:
  exit 0, `BUILD SUCCESSFUL in 5m49s`; compilation, PMD and the selected tests completed.
  The existing LinuxWatchedDriveReturnIntegrationTest exclusion stayed in force; no test
  selection or exclusion was changed. Log: `tmp/fu1-repair/java-prepush.log`.
- `git diff --check`: passed. `.agents/preflight` is absent. No known-good tree was supplied.
  All edits remain uncommitted; the shared dev stack and acceptance records are untouched.

## Acceptance records
