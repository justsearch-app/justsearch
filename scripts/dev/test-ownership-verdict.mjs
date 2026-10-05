#!/usr/bin/env node
//
// Tempdoc 606 — unit tests for the single ownership-verdict authority
// (scripts/dev/lib/ownership-verdict.cjs). Pure function → exhaustive, fast.
// Covers: NO_OWNER, self/USE, RECLAIM_DEAD, the 542 criticality dispatch
// (preserved verbatim), and the NEW presence grades (abandoned / idle / active)
// including the hook-unreliability safety case (no stamp → treated active).
//

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const {
  computeOwnershipVerdict, classifyActivity, readSessionActivity, mergeSessionActivity,
  computeDisplacedNotice, computeProvenanceMismatch, recommendedTakeoverFor, DEFAULT_THRESHOLDS,
} = require(path.join(__dirname, 'lib', 'ownership-verdict.cjs'));

const NOW = 1_000_000_000_000;
const OTHER = 'sess-OTHER';
const SELF = 'sess-SELF';

const emptyOps = { byCriticality: { mustComplete: [], unsafeToInterrupt: [], interruptibleWithLoss: [] }, entries: [] };
function ops(part) {
  return {
    byCriticality: { mustComplete: [], unsafeToInterrupt: [], interruptibleWithLoss: [], ...part },
    entries: [].concat(part.mustComplete || [], part.unsafeToInterrupt || [], part.interruptibleWithLoss || []),
  };
}
const lease = (op, crit) => ({ opId: op, opClass: 'x', criticality: crit });
const ownedActive = { runId: 'r1', holder: { source: 'claude', agentSessionId: OTHER }, lease: { expiresAt: new Date(NOW + 30_000).toISOString() } };

// Activity helpers relative to NOW
const fresh = (msAgo) => new Date(NOW - msAgo).toISOString();
const ACT_ACTIVE = { lastActivityAt: fresh(1_000), lastDevStackTouchAt: fresh(1_000) };
const ACT_IDLE = { lastActivityAt: fresh(60_000), lastDevStackTouchAt: fresh(20 * 60_000) };
const ACT_ABANDONED = { lastActivityAt: fresh(10 * 60_000), lastDevStackTouchAt: fresh(10 * 60_000) };

function base(extra) {
  return {
    active: ownedActive,
    callerSessionId: OTHER === extra?.callerSessionId ? OTHER : (extra?.callerSessionId ?? null),
    supervisorAlive: true,
    leaseExpired: false,
    ownerActivity: ACT_ACTIVE,
    opLeases: emptyOps,
    takeover: 'deny',
    now: NOW,
    ...extra,
  };
}

const tests = [
  ['no owner → NO_OWNER proceed', () => {
    const d = computeOwnershipVerdict(base({ active: null }));
    assert.equal(d.action, 'proceed');
    assert.equal(d.verdict, 'NO_OWNER');
  }],

  ['self owner → USE proceed (no disposition)', () => {
    const d = computeOwnershipVerdict(base({ callerSessionId: OTHER, selfCheck: true }));
    assert.equal(d.action, 'proceed');
    assert.equal(d.verdict, 'USE');
    assert.equal(d.disposition, undefined);
  }],

  ['self owner + provenance mismatch → USE rebuildFirst', () => {
    const d = computeOwnershipVerdict(base({ callerSessionId: OTHER, selfCheck: true, provenance: { mismatch: true } }));
    assert.equal(d.verdict, 'USE');
    assert.equal(d.rebuildFirst, true);
  }],

  ['gate (selfCheck:false) does NOT treat caller as self', () => {
    // Same caller id as holder, but selfCheck off → falls through to grade logic.
    const d = computeOwnershipVerdict(base({ callerSessionId: OTHER, selfCheck: false }));
    assert.notEqual(d.verdict, 'USE');
  }],

  ['dead supervisor → RECLAIM_DEAD stale_reclaim', () => {
    const d = computeOwnershipVerdict(base({ supervisorAlive: false }));
    assert.equal(d.action, 'proceed');
    assert.equal(d.verdict, 'RECLAIM_DEAD');
    assert.equal(d.disposition, 'stale_reclaim');
  }],

  ['expired lease → RECLAIM_DEAD', () => {
    const d = computeOwnershipVerdict(base({ leaseExpired: true }));
    assert.equal(d.verdict, 'RECLAIM_DEAD');
  }],

  // --- 542 criticality (preserved) ---
  ['UNSAFE + deny → conflict fresh_owner', () => {
    const d = computeOwnershipVerdict(base({ opLeases: ops({ unsafeToInterrupt: [lease('u1', 'UNSAFE_TO_INTERRUPT')] }) }));
    assert.equal(d.action, 'conflict');
    assert.equal(d.reason, 'fresh_owner');
    assert.equal(d.verdict, 'WAIT_CRITICAL_OP');
  }],
  ['UNSAFE + warn → conflict handshake_required', () => {
    const d = computeOwnershipVerdict(base({ takeover: 'warn', opLeases: ops({ unsafeToInterrupt: [lease('u1', 'UNSAFE_TO_INTERRUPT')] }) }));
    assert.equal(d.reason, 'handshake_required');
  }],
  ['UNSAFE + force without token → REQUIRES_CONFIRMATION', () => {
    const d = computeOwnershipVerdict(base({ takeover: 'force', opLeases: ops({ unsafeToInterrupt: [lease('u1', 'UNSAFE_TO_INTERRUPT')] }) }));
    assert.equal(d.action, 'conflict');
    assert.equal(d.reason, 'requires_confirmation');
  }],
  ['UNSAFE + force + matching token → proceed forcibly_interrupted_critical_op', () => {
    const d = computeOwnershipVerdict(base({ takeover: 'force', confirmInterrupt: 'u1', opLeases: ops({ unsafeToInterrupt: [lease('u1', 'UNSAFE_TO_INTERRUPT')] }) }));
    assert.equal(d.action, 'proceed');
    assert.equal(d.disposition, 'forcibly_interrupted_critical_op');
    assert.equal(d.criticalOpsInterrupted.length, 1);
  }],
  ['MUST_COMPLETE + deny → conflict fresh_owner', () => {
    const d = computeOwnershipVerdict(base({ opLeases: ops({ mustComplete: [lease('m1', 'MUST_COMPLETE')] }) }));
    assert.equal(d.reason, 'fresh_owner');
  }],
  ['MUST_COMPLETE + warn → conflict handshake_required', () => {
    const d = computeOwnershipVerdict(base({ takeover: 'warn', opLeases: ops({ mustComplete: [lease('m1', 'MUST_COMPLETE')] }) }));
    assert.equal(d.reason, 'handshake_required');
  }],
  ['MUST_COMPLETE + force → proceed forcibly_interrupted_critical_op', () => {
    const d = computeOwnershipVerdict(base({ takeover: 'force', opLeases: ops({ mustComplete: [lease('m1', 'MUST_COMPLETE')] }) }));
    assert.equal(d.disposition, 'forcibly_interrupted_critical_op');
  }],

  // --- 606 presence grades ---
  ['ABANDONED (general stale) + deny → proceed abandoned_reclaim (KEY: deny proceeds)', () => {
    const d = computeOwnershipVerdict(base({ ownerActivity: ACT_ABANDONED }));
    assert.equal(d.action, 'proceed');
    assert.equal(d.verdict, 'TAKEOVER_ABANDONED');
    assert.equal(d.disposition, 'abandoned_reclaim');
  }],
  ['IDLE (general fresh, dev stale) + deny → conflict idle_owner (soft, no silent steal)', () => {
    const d = computeOwnershipVerdict(base({ ownerActivity: ACT_IDLE }));
    assert.equal(d.action, 'conflict');
    assert.equal(d.verdict, 'IDLE_HOLD');
    assert.equal(d.reason, 'idle_owner');
  }],
  ['IDLE + warn → proceed idle_takeover (notify)', () => {
    const d = computeOwnershipVerdict(base({ takeover: 'warn', ownerActivity: ACT_IDLE }));
    assert.equal(d.action, 'proceed');
    assert.equal(d.verdict, 'IDLE_HOLD');
    assert.equal(d.disposition, 'idle_takeover');
    assert.equal(d.notify, true);
  }],
  ['ACTIVE (both fresh) + deny → conflict fresh_owner CONTENTION', () => {
    const d = computeOwnershipVerdict(base({ ownerActivity: ACT_ACTIVE }));
    assert.equal(d.action, 'conflict');
    assert.equal(d.verdict, 'CONTENTION');
    assert.equal(d.reason, 'fresh_owner');
  }],
  ['ACTIVE + warn → proceed warned_takeover', () => {
    const d = computeOwnershipVerdict(base({ takeover: 'warn', ownerActivity: ACT_ACTIVE }));
    assert.equal(d.disposition, 'warned_takeover');
  }],
  ['ACTIVE + force → proceed forced_reclaim', () => {
    const d = computeOwnershipVerdict(base({ takeover: 'force', ownerActivity: ACT_ACTIVE }));
    assert.equal(d.disposition, 'forced_reclaim');
  }],
  ['ACTIVE + warn + interruptible-with-loss → tags loss', () => {
    const d = computeOwnershipVerdict(base({ takeover: 'warn', ownerActivity: ACT_ACTIVE, opLeases: ops({ interruptibleWithLoss: [lease('i1', 'INTERRUPTIBLE_WITH_LOSS')] }) }));
    assert.equal(d.disposition, 'warned_takeover');
    assert.equal(d.interruptibleWithLossInterrupted.length, 1);
  }],

  // --- SAFETY: hook-unreliability — no activity stamp must NOT be read as abandoned ---
  ['UNKNOWN activity (no stamp) + deny → conflict CONTENTION (NOT abandoned)', () => {
    const d = computeOwnershipVerdict(base({ ownerActivity: null }));
    assert.equal(d.action, 'conflict');
    assert.equal(d.verdict, 'CONTENTION');
    assert.equal(d.reason, 'fresh_owner');
  }],
  ['UNKNOWN activity + warn → proceed (warned), never abandoned_reclaim', () => {
    const d = computeOwnershipVerdict(base({ takeover: 'warn', ownerActivity: null }));
    assert.equal(d.disposition, 'warned_takeover');
  }],

  // --- classifyActivity unit ---
  ['classifyActivity: no stamp → known:false', () => {
    const c = classifyActivity(null, NOW, DEFAULT_THRESHOLDS);
    assert.equal(c.known, false);
    assert.equal(c.generalStale, false);
  }],
  ['classifyActivity: fresh general, stale dev → idle', () => {
    const c = classifyActivity(ACT_IDLE, NOW, DEFAULT_THRESHOLDS);
    assert.equal(c.known, true);
    assert.equal(c.generalStale, false);
    assert.equal(c.devStale, true);
  }],
  ['classifyActivity: stale general → abandoned', () => {
    const c = classifyActivity(ACT_ABANDONED, NOW, DEFAULT_THRESHOLDS);
    assert.equal(c.generalStale, true);
  }],

  // --- integration: readSessionActivity reads the exact file shape the hooks write ---
  ['readSessionActivity round-trip (hook file format → verdict grade)', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sess-'));
    try {
      const sid = 'sess-X';
      // Activity-stamp shape (as the retired session hooks wrote it).
      fs.writeFileSync(path.join(dir, `${sid}.json`),
        JSON.stringify({ lastActivityAt: fresh(1_000), lastDevStackTouchAt: fresh(20 * 60_000) }));
      const act = readSessionActivity(dir, sid);
      assert.ok(act, 'activity read');
      const d = computeOwnershipVerdict(base({ ownerActivity: act }));
      assert.equal(d.verdict, 'IDLE_HOLD'); // fresh general + stale dev
      assert.equal(readSessionActivity(dir, 'missing'), null);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  }],

  // --- 606 3a: mergeSessionActivity round-trip (ownedEpoch persistence + merge) ---
  ['mergeSessionActivity preserves existing fields and adds ownedEpoch', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'merge-'));
    try {
      const sid = 'm1';
      fs.writeFileSync(path.join(dir, `${sid}.json`), JSON.stringify({ lastActivityAt: fresh(1000) }));
      mergeSessionActivity(dir, sid, { ownedEpoch: 7 });
      const a = readSessionActivity(dir, sid);
      assert.equal(a.ownedEpoch, 7);
      assert.ok(a.lastActivityAt, 'existing field preserved');
    } finally { fs.rmSync(dir, { recursive: true, force: true }); }
  }],

  // --- 606 3a: displaced-owner notice ---
  ['computeDisplacedNotice: displaced (owned<current, other holder) → message', () => {
    const n = computeDisplacedNotice(3, 5, 'other', 'me');
    assert.ok(n && n.includes('epoch 3') && n.includes('epoch 5'));
  }],
  ['computeDisplacedNotice: still owner (holder===caller) → null', () => {
    assert.equal(computeDisplacedNotice(5, 5, 'me', 'me'), null);
  }],
  ['computeDisplacedNotice: never owned (no epoch) → null', () => {
    assert.equal(computeDisplacedNotice(null, 5, 'other', 'me'), null);
  }],

  // --- 606 3c: recommendedTakeoverFor ---
  ['recommendedTakeoverFor: proceed → deny', () => {
    assert.equal(recommendedTakeoverFor({ action: 'proceed', verdict: 'TAKEOVER_ABANDONED' }), 'deny');
  }],
  ['recommendedTakeoverFor: idle conflict → warn', () => {
    assert.equal(recommendedTakeoverFor({ action: 'conflict', verdict: 'IDLE_HOLD' }), 'warn');
  }],
  ['recommendedTakeoverFor: active contention → null (not acquirable)', () => {
    assert.equal(recommendedTakeoverFor({ action: 'conflict', verdict: 'CONTENTION' }), null);
  }],
  ['recommendedTakeoverFor: critical op → null', () => {
    assert.equal(recommendedTakeoverFor({ action: 'conflict', verdict: 'WAIT_CRITICAL_OP' }), null);
  }],

  // --- 913 T1: computeProvenanceMismatch ---
  // THE DEFECT. quick_health reported rebuildFirst:true / "runs different code than your
  // worktree" for a stack the caller had itself launched with distFrom, from exactly that tree.
  ['provenance: distFrom launch matching the running root is NOT a mismatch', () => {
    assert.equal(
      computeProvenanceMismatch(
        { repoRoot: 'F:/repo/.claude/worktrees/lane-x', distFromRoot: 'F:/repo/.claude/worktrees/lane-x' },
        'F:/repo'),
      false);
  }],
  // Separator/trailing-slash normalization must not resurrect the false positive on Windows,
  // where the caller path arrives with backslashes and the record is stored POSIX.
  ['provenance: distFrom match survives separator + trailing-slash differences', () => {
    assert.equal(
      computeProvenanceMismatch(
        { repoRoot: 'F:/repo/.claude/worktrees/lane-x/', distFromRoot: 'F:\\repo\\.claude\\worktrees\\lane-x' },
        'F:\\repo'),
      false);
  }],
  // The check must still bite: a running root that is neither the caller's nor the one the
  // launch asked for is the stale/foreign-dist case tempdoc 606 Piece 2 added it for.
  ['provenance: running root is neither the caller nor the requested root → mismatch', () => {
    assert.equal(
      computeProvenanceMismatch(
        { repoRoot: 'F:/repo/.claude/worktrees/lane-OLD', distFromRoot: 'F:/repo/.claude/worktrees/lane-x' },
        'F:/repo'),
      true);
  }],
  ['provenance: no distFrom recorded + different root → still a mismatch', () => {
    assert.equal(
      computeProvenanceMismatch({ repoRoot: 'F:/repo/.claude/worktrees/lane-x', distFromRoot: null }, 'F:/repo'),
      true);
  }],
  ['provenance: same root → no mismatch (the ordinary case)', () => {
    assert.equal(computeProvenanceMismatch({ repoRoot: 'F:/repo' }, 'F:/repo'), false);
  }],
  // Unknowns must not be reported as a mismatch — an absent fact is not a negative finding.
  ['provenance: missing repoRoot or callerRepoRoot → no mismatch', () => {
    assert.equal(computeProvenanceMismatch(null, 'F:/repo'), false);
    assert.equal(computeProvenanceMismatch({ repoRoot: 'F:/repo' }, null), false);
    assert.equal(computeProvenanceMismatch({}, 'F:/repo'), false);
  }],
];

let pass = 0, fail = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log(`  PASS  ${name}`); pass++; }
  catch (e) { console.error(`  FAIL  ${name}: ${e.message}`); fail++; }
}
console.log(`test-ownership-verdict: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
