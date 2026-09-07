#!/usr/bin/env node
//
// Tempdoc 844 §4.2 — unit tests for the hot-reload repair, under §12.2's criterion:
// "a dev tool must not report state it did not verify, and must not report success it did not
// confirm; where it cannot verify something, it says so."
//
// Covered:
//   R1  resolveReloadTarget        — the compile root comes from the RUN RECORD, not the caller's
//                                    cwd; an unresolvable run tree FAILS instead of falling back.
//   R2  checkRunMutationOwnership  — a non-owner is refused with OWNER_CONFLICT in the existing
//                                    verdict vocabulary; the owner proceeds.
//   R3  classifyHotSwapOutcome     — an identity refusal, and an unverified push, are not success.
//   R5  classifyHotSwapOutcome     — exit 0 with zero classes redefined is NOT success; "nothing
//                                    changed" and "changed but nothing landed" are distinct.
//   R5  signal gating              — source-structural (labelled): the reload signal write is
//                                    gated on hotSwapOk, and every non-REDEFINED outcome is
//                                    hotSwapOk:false, so a failed push cannot reconstruct services.
//
// The four defects the 2026-08-19 live pass found, each a violation of the same criterion:
//   F1  classifyHotSwapOutcome     — the structural-change gate matched only HotSwapPush's own
//                                    phrasing, so the JVM's real message ("HotSwap not supported by
//                                    target VM: add method not implemented") fell through to a
//                                    generic HOTSWAP_FAILED and lost the restart remedy.
//   F2  classifyHotSwapOutcome     — a FAILED push reported classesRedefined: 3.
//   F3  HotSwapPush.confirmIdentity — the identity refusal asserted "cross-tree injection" for a VM
//                                    launched from the right tree with a stale dist.
//   F4  dev-runner.cjs             — `start` logged "Ensuring distribution is up-to-date" and ran
//                                    `assemble`, which does not refresh the dist it launches.
//
// The pre-merge review findings, same criterion again:
//   M3  assessHotReloadClasspath — R4 puts the classes dir FIRST, which is only sound when it and
//                                  the installDist jars are one build. `skipBuild` (124 of 179
//                                  measured starts) cannot establish that, so the pairing is
//                                  checked and hot reload is turned OFF with a recorded reason
//                                  rather than a silent half-new classpath.
//   M5  reloadModuleFromClassesDir — reload may only push the module the run's classpath carries;
//                                  any other module is refused instead of reported REDEFINED.
//   S1  classifyHotSwapOutcome    — a KILLED pusher is an explicit unknown, not "nothing pushed".
//
// Pure unit tests: no dev stack, no JVM, no network. The async helpers touch only tmp fixture
// trees; `dev-runner.cjs` is required for its `__test` exports, as its own tests already do.
//
// Run: node scripts/dev/test-dev-mcp-hot-reload.mjs
//

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import {
  checkRunMutationOwnership,
  classifyHotSwapOutcome,
  reloadModuleFromClassesDir,
  resolveReloadTarget,
} from './justsearch-dev-mcp/server.mjs';

const require = createRequire(import.meta.url);

// server.mjs installs process-level handlers that LOG rather than exit, so a top-level abort here
// would otherwise leave exit code 0 — a green that ran nothing. Fail closed until the runner clears it.
process.exitCode = 1;

const HERE = import.meta.dirname;

/* ── fixture: a main repo with two worktrees, the shape 125 of 162 starts ran in ───────────── */

async function makeFixture() {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'jsdev-844-hr-'));
  const main = path.join(root, 'main');
  for (const dir of [main, path.join(main, '.claude', 'worktrees', 'tree-a'), path.join(main, '.claude', 'worktrees', 'tree-b')]) {
    await fsp.mkdir(path.join(dir, 'scripts', 'dev'), { recursive: true });
    await fsp.writeFile(path.join(dir, 'scripts', 'dev', 'dev-runner.cjs'), '// fixture\n', 'utf8');
  }
  return { root, main, treeA: path.join(main, '.claude', 'worktrees', 'tree-a'), treeB: path.join(main, '.claude', 'worktrees', 'tree-b') };
}

const fx = await makeFixture();
const posix = (p) => p.replace(/\\/g, '/');

/** A run record as dev-runner.cjs writes it, launched from `tree`. */
const runRecord = (tree, over = {}) => ({
  schemaVersion: 1,
  runId: 'run-1',
  repoRoot: posix(tree),
  dataDir: posix(path.join(fx.root, 'data')),
  pids: { runnerPid: process.pid },
  hotReload: {
    enabled: true,
    debugPort: 5011,
    classesDir: posix(path.join(tree, 'modules', 'worker-services', 'build', 'classes', 'java', 'main')),
  },
  ...over,
});

/* ── R1: the compile root is the run's tree, not the caller's ──────────────────────────────── */

const targetTests = [
  ['the compile root is the RUN RECORD\'s tree, not the caller\'s cwd (§5.6 case (c))', async () => {
    // The caller (this process) is nowhere near tree-a; the run was launched from tree-a.
    const r = await resolveReloadTarget({ mainRepoRoot: fx.main, runJson: runRecord(fx.treeA) });
    assert.equal(r.ok, true);
    assert.equal(r.runRoot, fx.treeA);
    assert.notEqual(r.runRoot, process.cwd());
    assert.notEqual(r.runRoot, fx.treeB);
  }],
  ['the per-run JDWP port and identity token come from the record, not a hardcoded 5005', async () => {
    const r = await resolveReloadTarget({ mainRepoRoot: fx.main, runJson: runRecord(fx.treeA) });
    assert.equal(r.debugPort, 5011);
    assert.match(r.identityClassesDir, /tree-a/);
    assert.match(r.identityClassesDir, /worker-services/);
  }],
  ['a run launched from the main repo resolves to the main repo', async () => {
    const r = await resolveReloadTarget({ mainRepoRoot: fx.main, runJson: runRecord(fx.main) });
    assert.equal(r.ok, true);
    assert.equal(r.runRoot, fx.main);
  }],
  ['a run record with no repoRoot FAILS — it does not fall back to the caller\'s tree', async () => {
    const rec = runRecord(fx.treeA);
    delete rec.repoRoot;
    const r = await resolveReloadTarget({ mainRepoRoot: fx.main, runJson: rec });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'RUN_ROOT_UNRESOLVED');
    assert.match(r.error.message, /Refusing to guess/);
  }],
  ['a run whose tree no longer exists FAILS, naming the tree', async () => {
    const r = await resolveReloadTarget({
      mainRepoRoot: fx.main,
      runJson: runRecord(path.join(fx.main, '.claude', 'worktrees', 'removed-tree')),
    });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'RUN_ROOT_UNRESOLVED');
    assert.match(r.error.message, /removed-tree/);
  }],
  ['a run record pointing outside the repo is refused (not silently accepted)', async () => {
    const r = await resolveReloadTarget({ mainRepoRoot: fx.main, runJson: runRecord(path.join(fx.root, 'elsewhere')) });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'RUN_ROOT_UNRESOLVED');
  }],
  ['hotReload: false is a recorded fact, and reload says so instead of pushing at 5005', async () => {
    const r = await resolveReloadTarget({
      mainRepoRoot: fx.main,
      runJson: runRecord(fx.treeA, { hotReload: { enabled: false, debugPort: null, classesDir: null } }),
    });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'HOT_RELOAD_NOT_ENABLED');
    assert.match(r.error.message, /no JDWP listener/);
  }],
  ['a pre-844 run record (no hotReload block) is an explicit unknown, not an assumed 5005', async () => {
    const rec = runRecord(fx.treeA);
    delete rec.hotReload;
    const r = await resolveReloadTarget({ mainRepoRoot: fx.main, runJson: rec });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'HOT_RELOAD_NOT_ENABLED');
    assert.match(r.error.message, /5005/);
  }],
  ['hot reload on but no recorded port is refused rather than defaulted', async () => {
    const r = await resolveReloadTarget({
      mainRepoRoot: fx.main,
      runJson: runRecord(fx.treeA, { hotReload: { enabled: true, debugPort: null, classesDir: null } }),
    });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'HOT_RELOAD_NOT_ENABLED');
  }],
];

/* ── R2: reload mutates a run, so it is ownership-gated like start/stop ────────────────────── */

const OWNER = 'session-owner-A';
const activeRecord = (over = {}) => ({
  kind: 'backend-shared-lease.v1',
  runId: 'run-1',
  holder: { source: 'test', agentSessionId: OWNER },
  takeoverPolicy: 'warn',
  ownershipEpoch: 3,
  lease: { durationSec: 60, expiresAt: new Date(Date.now() + 60_000).toISOString(), sequence: 1 },
  ...over,
});

const ownershipArgs = (over = {}) => ({
  mainRepoRoot: fx.main,
  callerRepoRoot: fx.treeB,
  callerSessionId: 'session-intruder-B',
  takeover: 'deny',
  active: activeRecord(),
  runJson: runRecord(fx.treeA),
  tool: 'reload',
  ...over,
});

const ownershipTests = [
  ['a NON-OWNER is refused — bytecode cannot be pushed into a peer\'s stack silently', async () => {
    const g = await checkRunMutationOwnership(ownershipArgs());
    assert.equal(g.allowed, false);
    assert.equal(g.refusal.error.code, 'OWNER_CONFLICT');
    assert.equal(g.decision.verdict, 'CONTENTION');
    assert.match(g.refusal.error.message, /reload mutates the running stack/);
    assert.equal(g.refusal.ownership.holder.agentSessionId, OWNER);
  }],
  ['the refusal says the takeover does not transfer the lease', async () => {
    const g = await checkRunMutationOwnership(ownershipArgs());
    assert.match(g.refusal.actionRequired, /does not transfer the lease/);
    assert.match(g.refusal.actionRequired, /takeover/);
  }],
  ['the OWNER proceeds', async () => {
    const g = await checkRunMutationOwnership(ownershipArgs({ callerSessionId: OWNER }));
    assert.equal(g.allowed, true);
    assert.equal(g.decision.verdict, 'USE');
  }],
  ['an authorized takeover:"force" proceeds — the vocabulary is the existing one', async () => {
    const g = await checkRunMutationOwnership(ownershipArgs({ takeover: 'force' }));
    assert.equal(g.allowed, true);
    assert.equal(g.decision.verdict, 'CONTENTION');
  }],
  ['a dead supervisor is reclaimable, not a permanent refusal', async () => {
    const g = await checkRunMutationOwnership(ownershipArgs({
      runJson: runRecord(fx.treeA, { pids: { runnerPid: 0 } }),
    }));
    assert.equal(g.allowed, true);
    assert.equal(g.decision.verdict, 'RECLAIM_DEAD');
  }],
  ['no holder at all → no refusal to invent', async () => {
    const g = await checkRunMutationOwnership(ownershipArgs({ active: { runId: 'run-1' } }));
    assert.equal(g.allowed, true);
    assert.equal(g.ownership, null);
  }],
];

/* ── R3/R5: what the push actually did ─────────────────────────────────────────────────────── */

const REDEFINED_OK = { exitCode: 0, stdout: 'CHANGED 2\nIDENTITY_OK F:/t/classes\nREDEFINED 2\nNOT_LOADED 0\n' };

const outcomeTests = [
  ['a confirmed push of 2 classes is success, with the counts reported', () => {
    const o = classifyHotSwapOutcome(REDEFINED_OK);
    assert.equal(o.hotSwapOk, true);
    assert.equal(o.outcome, 'REDEFINED');
    assert.equal(o.classesRedefined, 2);
    assert.equal(o.classesChanged, 2);
    assert.equal(o.identityVerified, true);
  }],
  ['§5.6 #4: exit 0 with ZERO classes redefined is NOT success', () => {
    const o = classifyHotSwapOutcome({ exitCode: 0, stdout: 'CHANGED 3\nIDENTITY_OK F:/t/classes\nREDEFINED 0\nNOT_LOADED 3\n' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.outcome, 'NO_CLASSES_REDEFINED');
    assert.equal(o.error.code, 'NO_CLASSES_REDEFINED');
    assert.equal(o.classesRedefined, 0);
  }],
  ['the pusher\'s own exit 4 ("none loaded") is a failure, and names the count', () => {
    const o = classifyHotSwapOutcome({ exitCode: 4, stdout: 'CHANGED 3\nIDENTITY_OK F:/t/classes\nREDEFINED 0\nNOT_LOADED 3\n' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.error.code, 'NO_CLASSES_REDEFINED');
    assert.match(o.error.message, /3 changed class file\(s\)/);
    assert.match(o.error.message, /services were NOT reconstructed/);
  }],
  ['"no class changed since the last push" is a no-op, distinct from a failed push', () => {
    const o = classifyHotSwapOutcome({ exitCode: 3, stdout: 'CHANGED 0\nNo changed classes since last push.\n' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.noOp, true);
    assert.equal(o.outcome, 'NOTHING_CHANGED');
    assert.equal(o.error, undefined);
  }],
  ['R3: an identity refusal reports the cross-tree case, not a generic failure', () => {
    const o = classifyHotSwapOutcome({ exitCode: 5, stderr: 'IDENTITY_REFUSED the VM on port 5011 was NOT launched from the tree this push comes from.' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.error.code, 'TARGET_IDENTITY_MISMATCH');
    assert.equal(o.identityVerified, false);
    assert.match(o.error.message, /cross-tree/);
  }],
  ['R3: exit 0 without an IDENTITY_OK line is unverified, not success (guards an OLD pusher copy)', () => {
    const o = classifyHotSwapOutcome({ exitCode: 0, stdout: 'CHANGED 2\nRedefined 2 class(es):\nREDEFINED 2\n' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.outcome, 'IDENTITY_UNVERIFIED');
    assert.equal(o.error.code, 'TARGET_IDENTITY_UNVERIFIED');
  }],
  ['with no identity token to check, the same output is accepted (and identity is not claimed)', () => {
    const o = classifyHotSwapOutcome({ exitCode: 0, stdout: 'CHANGED 2\nREDEFINED 2\n', identityRequired: false });
    assert.equal(o.hotSwapOk, true);
    assert.equal(o.identityVerified, false);
  }],
  ['a structural change is reported as such (R7: reported, never staged)', () => {
    const o = classifyHotSwapOutcome({ exitCode: 1, stderr: 'HotSwap failed: ...\nIf you added/removed methods or fields, standard HotSwap cannot apply them' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.error.code, 'STRUCTURAL_CHANGE');
    assert.equal(o.structuralChangeDetected, true);
  }],
  // ── F1: the gate must fire on the JVM's wording, which is what a real structural change prints.
  ['F1: the EXACT line the live run produced classifies as STRUCTURAL_CHANGE with the restart remedy', () => {
    // Verbatim from the 2026-08-19 live pass (tempdoc 844 §13.6 step 8), where this returned
    // HOTSWAP_FAILED / "no bytecode was pushed" instead.
    const o = classifyHotSwapOutcome({
      exitCode: 1,
      stdout: 'CHANGED 3\nIDENTITY_OK F:/t/classes\nNOT_LOADED 0\n',
      stderr: 'HotSwap not supported by target VM: add method not implemented',
    });
    assert.equal(o.structuralChangeDetected, true);
    assert.equal(o.error.code, 'STRUCTURAL_CHANGE');
    assert.match(o.error.message, /restart the stack for this change/);
  }],
  ['F1: the rest of the JDI "not implemented" family classifies the same way', () => {
    const family = [
      'HotSwap not supported by target VM: delete method not implemented',
      'HotSwap not supported by target VM: schema change not implemented',
      'HotSwap not supported by target VM: hierarchy change not implemented',
      'HotSwap not supported by target VM: class attribute change not implemented',
      'HotSwap failed: schema change not implemented',
    ];
    for (const stderr of family) {
      const o = classifyHotSwapOutcome({ exitCode: 1, stderr });
      assert.equal(o.error.code, 'STRUCTURAL_CHANGE', `expected STRUCTURAL_CHANGE for: ${stderr}`);
    }
  }],
  ['F1: the widened predicate is NOT a catch-all — unrelated failures stay HOTSWAP_FAILED', () => {
    const unrelated = [
      'Failed to connect to JDWP agent on port 5011\nIs the Worker running with JUSTSEARCH_DEV_DEBUG_PORT=5011?',
      'HotSwap failed: com.sun.jdi.VMDisconnectedException',
      'Error: spawn java ENOENT',
      'com.sun.jdi.InternalException: Unexpected JDWP Error: 103',
      // "not implemented" on its own, in a sentence that is not a redefinition capability, must not
      // be swallowed by the family patterns.
      'HotSwap failed: the connector feature is not implemented on this platform',
    ];
    for (const stderr of unrelated) {
      const o = classifyHotSwapOutcome({ exitCode: 1, stderr });
      assert.equal(o.structuralChangeDetected, false, `unexpected structural verdict for: ${stderr}`);
      assert.equal(o.error.code, 'HOTSWAP_FAILED', `expected HOTSWAP_FAILED for: ${stderr}`);
    }
  }],
  // ── F2: a failed push must never report classes as redefined.
  ['F2: a non-zero exit with "REDEFINED 3" in the output reports classesRedefined 0, not 3', () => {
    // Verbatim shape of the live failed structural push, which returned ok:false WITH
    // classesRedefined: 3 — a false success signal inside a failure result.
    const o = classifyHotSwapOutcome({
      exitCode: 1,
      stdout: 'CHANGED 3\nIDENTITY_OK F:/t/classes\nREDEFINED 3\nNOT_LOADED 0\n',
      stderr: 'HotSwap not supported by target VM: add method not implemented',
    });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.classesRedefined, 0);
    assert.equal(o.classesChanged, 3);
  }],
  ['F2: no non-zero exit code can report a positive classesRedefined', () => {
    const stdout = 'CHANGED 3\nIDENTITY_OK F:/t/classes\nREDEFINED 3\nNOT_LOADED 0\n';
    for (const exitCode of [1, 3, 4, 5, 6, -1]) {
      const o = classifyHotSwapOutcome({ exitCode, stdout });
      assert.equal(o.classesRedefined, 0, `exit ${exitCode} reported ${o.classesRedefined} redefined`);
    }
    // …and a successful push still reports its real count.
    assert.equal(classifyHotSwapOutcome(REDEFINED_OK).classesRedefined, 2);
  }],
  ['F2: HotSwapPush prints REDEFINED only after the redefinition returned (source-structural)', async () => {
    const src = await fsp.readFile(path.join(HERE, 'HotSwapPush.java'), 'utf8');
    const call = src.indexOf('vm.redefineClasses(redefinitions);');
    const print = src.indexOf('System.out.printf("REDEFINED %d%n", redefinitions.size());');
    assert.ok(call > 0, 'redefineClasses call not found');
    assert.ok(print > call, 'the REDEFINED count is printed BEFORE the redefinition it claims');
    assert.equal(
      (src.match(/printf\("REDEFINED %d%n"/g) || []).length, 1,
      'the REDEFINED count is printed from more than one place',
    );
    // The failure paths state the honest count rather than staying silent about it.
    assert.equal(
      (src.match(/System\.out\.println\("REDEFINED 0"\);/g) || []).length, 3,
      'expected REDEFINED 0 on the none-loaded path and on both redefinition-failure paths',
    );
  }],
  // ── F3: the identity refusal must not assert a cause it did not verify.
  ['F3: exit 6 is HOT_RELOAD_CLASSPATH_ABSENT — same tree, stale dist, NOT cross-tree', () => {
    const o = classifyHotSwapOutcome({
      exitCode: 6,
      stderr: 'IDENTITY_CLASSPATH_ABSENT the VM on port 5011 WAS launched from the expected tree '
        + '(183 of 183 classpath entries are under F:/repo), but the hot-reload classes dir is NOT '
        + 'on its classpath',
    });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.outcome, 'CLASSPATH_ABSENT');
    assert.equal(o.error.code, 'HOT_RELOAD_CLASSPATH_ABSENT');
    assert.match(o.error.message, /WAS launched from the tree/);
    assert.match(o.error.message, /installDist/);
    assert.doesNotMatch(o.error.message, /cross-tree/);
  }],
  ['F3: exit 5 stays the cross-tree case, and now names the evidence for it', () => {
    const o = classifyHotSwapOutcome({ exitCode: 5, stderr: 'IDENTITY_REFUSED the VM on port 5011 was NOT launched from the tree this push comes from - none of its classpath entries are under F:/repo' });
    assert.equal(o.error.code, 'TARGET_IDENTITY_MISMATCH');
    assert.match(o.error.message, /cross-tree/);
    assert.match(o.error.message, /none of its classpath entries/);
  }],
  ['a spawn failure (no numeric exit code) is a failure, not an unknown-shaped success', () => {
    const o = classifyHotSwapOutcome({ exitCode: -1, stderr: 'ENOENT' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.error.code, 'HOTSWAP_FAILED');
  }],
  ['every non-REDEFINED outcome is hotSwapOk:false — the property the signal gate rests on', () => {
    const cases = [
      { exitCode: 3 }, { exitCode: 4 }, { exitCode: 5 }, { exitCode: 6 }, { exitCode: 1 }, { exitCode: -1 },
      { exitCode: 0, stdout: 'REDEFINED 0\nIDENTITY_OK x\n' },
      { exitCode: 0, stdout: 'REDEFINED 2\n' },
    ];
    for (const c of cases) {
      assert.equal(classifyHotSwapOutcome(c).hotSwapOk, false, `expected not-ok for ${JSON.stringify(c)}`);
    }
    assert.equal(classifyHotSwapOutcome(REDEFINED_OK).hotSwapOk, true);
  }],
  ['S1: a KILLED pusher reports an explicit unknown, not "no bytecode was pushed"', () => {
    // A kill that lands after redefineClasses returned leaves new bytecode in the VM. The old code
    // mapped the killer's exit code (-1) onto HOTSWAP_FAILED — "no bytecode was pushed" — which is
    // a claim about a state it could not observe.
    const o = classifyHotSwapOutcome({ exitCode: -1, stdout: 'CHANGED 2\nIDENTITY_OK F:/t/classes\n', timedOut: true });
    assert.equal(o.outcome, 'TIMED_OUT');
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.error.code, 'HOTSWAP_TIMED_OUT');
    assert.equal(o.classesRedefined, null, 'classesRedefined must be an explicit unknown, not 0');
    assert.match(o.error.message, /UNKNOWN/);
    assert.match(o.error.message, /must NOT be assumed unchanged/);
    // Facts it CAN still see are kept — the identity check completed before the kill.
    assert.equal(o.classesChanged, 2);
    assert.equal(o.identityVerified, true);
  }],
  ['S1: a timeout is distinguished from a genuine non-zero exit', () => {
    const killed = classifyHotSwapOutcome({ exitCode: -1, stdout: '', timedOut: true });
    const exited = classifyHotSwapOutcome({ exitCode: -1, stdout: '', timedOut: false });
    assert.equal(killed.outcome, 'TIMED_OUT');
    assert.equal(exited.outcome, 'PUSH_FAILED');
    assert.equal(exited.classesRedefined, 0, 'a real non-zero exit IS a verified zero (F2)');
    // A structural refusal that also happened to be killed still reports the unknown, because the
    // kill is the thing that makes the outcome unobservable.
    const structuralAndKilled = classifyHotSwapOutcome({
      exitCode: -1, stderr: 'HotSwap not supported by target VM: add method not implemented', timedOut: true,
    });
    assert.equal(structuralAndKilled.outcome, 'TIMED_OUT');
    assert.equal(structuralAndKilled.structuralChangeDetected, true);
  }],
  ['S1: the handler classifies a kill as timedOut rather than as exit code -1', async () => {
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    assert.match(src, /hsTimedOut = err\.killed === true \|\| typeof err\.signal === 'string';/);
    assert.match(src, /timedOut: hsTimedOut,/);
  }],
];

/* ── R5: the signal gate itself (source-structural — labelled, not disguised) ───────────────
 * The write is three lines inside the tool handler, so this asserts on the handler's SOURCE
 * rather than pretending to exercise it. What IS exercised is the property it depends on: the
 * outcome tests above prove hotSwapOk is false for every outcome except a confirmed redefinition.
 */
const signalGateTests = [
  ['the reload signal write is gated on hotSwapOk (was: gated only on signalFile)', async () => {
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    assert.match(src, /if \(result\.hotSwapOk && signalFile\) \{/);
    assert.doesNotMatch(src, /Continue to signal write so service reconstruction still happens/);
  }],
  ['a skipped signal is stated, not silent', async () => {
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    assert.match(src, /signalSkippedReason/);
    assert.match(src, /services were NOT reconstructed/);
  }],
  ['the build stamp is copied from the RUN\'s tree, never the caller\'s (§5.6 #2)', async () => {
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    assert.match(src, /const stampPath = path\.join\(runRoot,/);
  }],
  ['the pusher is invoked with the recorded identity token when the run has one', async () => {
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    assert.ok(
      src.includes('if (identityClassesDir) hsArgs.push(identityClassesDir, runRoot);'),
      'the identity token (and, since F3, the run root) is not passed to the pusher',
    );
  }],
  ['HotSwapPush refuses a VM whose classpath does not carry the identity entry', async () => {
    const src = await fsp.readFile(path.join(HERE, 'HotSwapPush.java'), 'utf8');
    assert.match(src, /PathSearchingVirtualMachine psvm/);
    assert.match(src, /IDENTITY_REFUSED/);
    // …and the marker is only touched when bytecode actually moved (§5.6 #4).
    assert.match(src, /if \(exitCode == 0\) \{\s*\n\s*Files\.writeString\(markerFile/);
  }],
  // ── F3 (source-structural — the live exit codes were probed against a throwaway JVM; see the
  //    tempdoc). The two refusals are distinct outcomes with distinct exit codes and remedies.
  ['F3: HotSwapPush distinguishes stale-dist (exit 6) from cross-tree (exit 5)', async () => {
    const src = await fsp.readFile(path.join(HERE, 'HotSwapPush.java'), 'utf8');
    assert.match(src, /EXIT_CLASSPATH_ABSENT = 6/);
    assert.match(src, /IDENTITY_CLASSPATH_ABSENT/);
    assert.match(src, /enum Identity \{ CONFIRMED, REFUSED_CROSS_TREE, REFUSED_CLASSPATH_ABSENT \}/);
    // The stale-dist branch is chosen from measured evidence (entries under the expected root),
    // not assumed, and it carries the rebuild remedy.
    assert.match(src, /isUnderRoot\(e, expectedRepoRoot\)/);
    assert.match(src, /installDist/);
    // A worktree lives UNDER the main checkout's path, so it must not count as "same tree".
    assert.match(src, /\.claude\/worktrees\//);
  }],
  ['F3: the server passes the run\'s tree as the expected repo root, so the pusher can tell them apart', async () => {
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    assert.match(src, /hsArgs\.push\(identityClassesDir, runRoot\)/);
  }],
  // ── F4: `start` builds what it launches, so its "up-to-date" claim is true.
  ['F4: the dev-runner build step runs the two installDist tasks, not assemble alone', async () => {
    const src = await fsp.readFile(path.join(HERE, 'dev-runner.cjs'), 'utf8');
    const tasks = src.match(/\[\s*'assemble',\s*'([^']+)',\s*'([^']+)',\s*'-PskipWebBuild=true'\s*\]/);
    assert.ok(tasks, 'the gradle task list for the pre-launch build was not found');
    assert.deepEqual(
      [tasks[1], tasks[2]],
      [':modules:ui:installDist', ':modules:indexer-worker:installDist'],
    );
  }],
  ['F4: the message names what it actually runs (assemble alone did not refresh the launched dist)', async () => {
    const src = await fsp.readFile(path.join(HERE, 'dev-runner.cjs'), 'utf8');
    assert.match(src, /Ensuring distribution is up-to-date \(assemble \+ installDist\)/);
    assert.doesNotMatch(src, /Ensuring distribution is up-to-date \(assemble\)/);
  }],
];

/* ── M3: the classes-dir prefix only when the classes dir and the dist are ONE build ───────── */

const { __test: devRunner } = require(path.join(HERE, 'dev-runner.cjs'));

/** `<root>/classes` with one .class file, and `<root>/lib/worker-services-0.2.0.jar`. */
async function makeArtifacts({ classAgeMs = 0, jarAgeMs = 0, withJar = true, withClass = true } = {}) {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'jsdev-844-m3-'));
  const classesDir = path.join(root, 'classes', 'io', 'justsearch');
  const libDir = path.join(root, 'lib');
  await fsp.mkdir(classesDir, { recursive: true });
  await fsp.mkdir(libDir, { recursive: true });
  const now = Date.now();
  if (withClass) {
    const f = path.join(classesDir, 'Thing.class');
    await fsp.writeFile(f, 'x', 'utf8');
    await fsp.utimes(f, new Date(now - classAgeMs), new Date(now - classAgeMs));
  }
  if (withJar) {
    const f = path.join(libDir, 'worker-services-0.2.0.jar');
    await fsp.writeFile(f, 'x', 'utf8');
    await fsp.utimes(f, new Date(now - jarAgeMs), new Date(now - jarAgeMs));
  }
  return { root, classesDir: path.join(root, 'classes'), libDir };
}

const artifactRoots = [];
const artifacts = async (opts) => { const a = await makeArtifacts(opts); artifactRoots.push(a.root); return a; };

const classpathPairingTests = [
  ['the build step having run IS the pairing — no stamp guessing needed', async () => {
    const a = await artifacts({ classAgeMs: 0, jarAgeMs: 600_000 });
    const r = devRunner.assessHotReloadClasspath({ classesDir: a.classesDir, libDir: a.libDir, buildRan: true });
    assert.equal(r.ok, true);
    assert.equal(r.verdict, 'BUILD_RAN');
  }],
  ['skipBuild + classes NEWER than the installed jar is refused — that is the mixed classpath', async () => {
    // The M3 regression: R4 puts the classes dir FIRST, and with skipBuild installDist never ran,
    // so worker-services would come from build B and every other module from build A's jars —
    // with `freshness.buildArtifact` still saying FRESH because it derives from the dist stamp.
    const a = await artifacts({ classAgeMs: 0, jarAgeMs: 600_000 });
    const r = devRunner.assessHotReloadClasspath({ classesDir: a.classesDir, libDir: a.libDir, buildRan: false });
    assert.equal(r.ok, false);
    assert.equal(r.verdict, 'CLASSES_NEWER_THAN_DIST');
    assert.match(r.reason, /--skip-build/);
    assert.match(r.reason, /worker-services-0\.2\.0\.jar/);
  }],
  ['skipBuild with classes no newer than the jar IS paired — hot reload survives the common path', async () => {
    // 124 of 179 measured starts passed skipBuild. The usual case is a stack restarted on the same
    // artifacts, where the jar was installed FROM those classes: consistent, so the prefix holds.
    const a = await artifacts({ classAgeMs: 600_000, jarAgeMs: 0 });
    const r = devRunner.assessHotReloadClasspath({ classesDir: a.classesDir, libDir: a.libDir, buildRan: false });
    assert.equal(r.ok, true);
    assert.equal(r.verdict, 'STAMPS_CONSISTENT');
  }],
  ['an unreadable dist jar is an explicit unknown, not an assumed pairing', async () => {
    const a = await artifacts({ withJar: false });
    const r = devRunner.assessHotReloadClasspath({ classesDir: a.classesDir, libDir: a.libDir, buildRan: false });
    assert.equal(r.ok, false);
    assert.equal(r.verdict, 'DIST_JAR_UNREADABLE');
  }],
  ['an empty classes dir is refused rather than prefixed as an empty entry', async () => {
    const a = await artifacts({ withClass: false });
    const r = devRunner.assessHotReloadClasspath({ classesDir: a.classesDir, libDir: a.libDir, buildRan: false });
    assert.equal(r.ok, false);
    assert.equal(r.verdict, 'CLASSES_DIR_EMPTY');
  }],
  ['newestClassMtimeMs walks nested packages and ignores non-.class files', async () => {
    const a = await artifacts({ classAgeMs: 600_000 });
    await fsp.writeFile(path.join(a.classesDir, 'io', 'justsearch', 'notes.txt'), 'x', 'utf8');
    const nested = path.join(a.classesDir, 'io', 'justsearch', 'deep');
    await fsp.mkdir(nested, { recursive: true });
    const newer = path.join(nested, 'Deep.class');
    await fsp.writeFile(newer, 'x', 'utf8');
    const got = devRunner.newestClassMtimeMs(a.classesDir);
    assert.ok(got >= (await fsp.stat(newer)).mtimeMs - 5, 'the deepest .class must be seen');
    assert.equal(devRunner.newestClassMtimeMs(path.join(a.root, 'nope')), null);
  }],
  ['start passes the skipBuild fact through — the check cannot be bypassed by the caller', async () => {
    const src = await fsp.readFile(path.join(HERE, 'dev-runner.cjs'), 'utf8');
    assert.match(src, /resolveDevHotReload\(opts\.hotReload,\s*\{\s*buildRan:\s*!opts\.skipBuild\s*\}\)/);
  }],
  ['reload repeats the dev-runner\'s own reason instead of inventing one (F3\'s mistake)', async () => {
    const r = await resolveReloadTarget({
      mainRepoRoot: fx.main,
      runJson: runRecord(fx.treeA, {
        hotReload: {
          enabled: false, requested: true, debugPort: null, classesDir: null,
          classpathVerdict: 'CLASSES_NEWER_THAN_DIST',
          reason: 'the classes dir was compiled after the jar was installed',
        },
      }),
    });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'HOT_RELOAD_NOT_ENABLED');
    assert.match(r.error.message, /turned it off at start/);
    assert.match(r.error.message, /compiled after the jar was installed/);
    assert.match(r.error.message, /CLASSES_NEWER_THAN_DIST/);
    // …and an ordinary opt-out still reads as an opt-out, not as a failure.
    const optOut = await resolveReloadTarget({
      mainRepoRoot: fx.main,
      runJson: runRecord(fx.treeA, { hotReload: { enabled: false, requested: false, debugPort: null, classesDir: null } }),
    });
    assert.match(optOut.error.message, /started with hotReload: false/);
  }],
];

/* ── M5: reload may only push the module the run's classpath actually carries ──────────────── */

const moduleScopeTests = [
  ['M6: the layout the dev-runner records is the layout the reload tool parses back', () => {
    // The cross-side pin the Java test used to claim falsely (it restated WorkerSpawner's own
    // implementation and never referenced this side; item A11 has since deleted WorkerSpawner
    // and the Worker child process it launched). Here both ends are executable in one process:
    // the writer's output must be exactly what the parser understands.
    const dir = devRunner.hotReloadClassesDir('F:/t');
    assert.equal(dir, 'F:/t/modules/worker-services/build/classes/java/main');
    assert.equal(devRunner.HOTRELOAD_MODULE, 'worker-services');
    assert.equal(reloadModuleFromClassesDir(dir), devRunner.HOTRELOAD_MODULE);
    // …and, at the time this was written, WorkerSpawner's Java-side half was pinned to the same
    // segment sequence (WorkerSpawner itself is gone since item A11 — see the note above).
    const java = 'modules/worker-services/build/classes/java/main';
    assert.ok(dir.endsWith(java), `dev-runner must emit ${java}`);
  }],
  ['the recorded classes dir names the module, and an unrecognisable one is an explicit null', () => {
    assert.equal(
      reloadModuleFromClassesDir('F:/t/modules/worker-services/build/classes/java/main'),
      'worker-services');
    assert.equal(
      reloadModuleFromClassesDir('F:\\t\\modules\\worker-services\\build\\classes\\java\\main'),
      'worker-services');
    assert.equal(reloadModuleFromClassesDir('F:/t/modules/app-services/build/classes/java/main'), 'app-services');
    assert.equal(reloadModuleFromClassesDir('F:/t/modules/worker-services/build/libs'), null);
    assert.equal(reloadModuleFromClassesDir(''), null);
    assert.equal(reloadModuleFromClassesDir(null), null);
  }],
  ['reload refuses a module the run\'s classpath does not carry, rather than reporting REDEFINED', async () => {
    // The M5 bug: `module` chose the compile+push source, but the identity args always came from
    // the run record's classesDir — so pushing another module passed IDENTITY_OK while its classes
    // came from a directory the VM never loads from, and later-loaded classes stayed on the stale
    // jar. The refusal below is the whole fix; the assertion pins that the message says why.
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    const site = src.slice(src.indexOf('RELOAD_MODULE_NOT_ON_CLASSPATH'));
    assert.match(site.slice(0, 1200), /Refusing/);
    assert.match(site.slice(0, 1200), /stale jar/);
    // The guard is on `input.module`, i.e. the caller's value, not the resolved default.
    assert.match(src, /if \(input\.module && input\.module !== recordedModule\)/);
    // …and the module actually used comes from the RECORD, never from the input.
    assert.match(src, /const module = recordedModule \|\| 'worker-services';/);
  }],
];

/* ── runner ────────────────────────────────────────────────────────────────────────────────── */

let passed = 0;
let failed = 0;
for (const [name, fn] of [...targetTests, ...ownershipTests, ...outcomeTests, ...signalGateTests,
  ...classpathPairingTests, ...moduleScopeTests]) {
  try {
    await fn();
    passed += 1;
    console.log(`  ok   ${name}`);
  } catch (err) {
    failed += 1;
    console.error(`  FAIL ${name}\n       ${err.message}`);
  }
}
await fsp.rm(fx.root, { recursive: true, force: true });
for (const root of artifactRoots) await fsp.rm(root, { recursive: true, force: true });
console.log(`\n${passed} passed, ${failed} failed`);
process.exitCode = failed === 0 ? 0 : 1;
