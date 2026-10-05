#!/usr/bin/env node
/**
 * Tempdoc 735 G6 — campaign-length lease hold.
 *
 * The shared dev stack's ownership lease is renewed every 10s against a fixed 30s TTL. The
 * presence-aware renewer (tempdoc 606 1d) pauses that renewal when the owning session's activity
 * looks stale — which is the CORRECT behavior for an abandoned session, but also fires during a
 * long, legitimate measurement campaign where the agent is busy running jseval/Gradle for minutes
 * without touching the Claude Code session. With a fixed 30s TTL, that pause lapses the lease
 * almost immediately and invites a takeover mid-campaign (observed three times this session as
 * apparent "backend deaths").
 *
 * G6's fix: let the starter declare `leaseDurationSec`, clamped to [30, 7200]s, and thread it
 * into BOTH the initial lease record and every renewal — see scripts/dev/dev-runner.cjs
 * clampLeaseDurationSec + cmdStart's lease/renewal blocks. These tests exercise the pure clamp
 * function and the CLI arg parsing/threading in isolation (no live stack available to this test
 * run — see docs/tempdocs/735-agent-surface-seam-consolidation.md §G6).
 */

import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const require = createRequire(import.meta.url);
const devRunnerModule = require(path.join(__dirname, 'dev-runner.cjs'));
const {
  clampLeaseDurationSec,
  parseArgs,
  DEFAULT_LEASE_DURATION_SEC,
  MIN_LEASE_DURATION_SEC,
  MAX_LEASE_DURATION_SEC,
  createPresenceMonitor,
} = devRunnerModule.__test;

// --- clampLeaseDurationSec: pure clamp behavior -----------------------------------------------

function testClampDefaultsWhenAbsent() {
  assert.equal(clampLeaseDurationSec(undefined), DEFAULT_LEASE_DURATION_SEC, 'undefined -> default (unchanged behavior)');
  assert.equal(clampLeaseDurationSec(null), DEFAULT_LEASE_DURATION_SEC, 'null -> default');
  assert.equal(clampLeaseDurationSec(''), DEFAULT_LEASE_DURATION_SEC, 'empty string -> default');
  assert.equal(DEFAULT_LEASE_DURATION_SEC, 30, 'default must be the pre-735 hardcoded 30s TTL');
  console.log('test-dev-runner-lease-duration: clampLeaseDurationSec defaults when absent — PASS');
}

function testClampWithinRangePassesThrough() {
  assert.equal(clampLeaseDurationSec(3600), 3600, '3600 (1h) is inside [30,7200] and must pass through unchanged');
  assert.equal(clampLeaseDurationSec(60), 60);
  assert.equal(clampLeaseDurationSec('120'), 120, 'numeric strings (as arrive from CLI --flag=value) must coerce');
  console.log('test-dev-runner-lease-duration: clampLeaseDurationSec passes through in-range values — PASS');
}

function testClampLowerBound() {
  assert.equal(clampLeaseDurationSec(1), MIN_LEASE_DURATION_SEC, 'below-min clamps up to 30');
  assert.equal(clampLeaseDurationSec(0), MIN_LEASE_DURATION_SEC);
  assert.equal(clampLeaseDurationSec(-100), MIN_LEASE_DURATION_SEC, 'negative values clamp up to 30, not reject');
  assert.equal(clampLeaseDurationSec(29), MIN_LEASE_DURATION_SEC, 'just-below-min clamps up');
  assert.equal(clampLeaseDurationSec(30), MIN_LEASE_DURATION_SEC, 'exactly-min passes through as the boundary itself');
  console.log('test-dev-runner-lease-duration: clampLeaseDurationSec clamps at the lower bound (30) — PASS');
}

function testClampUpperBound() {
  assert.equal(clampLeaseDurationSec(999999), MAX_LEASE_DURATION_SEC, 'above-max clamps down to 7200 (2h)');
  assert.equal(clampLeaseDurationSec(7201), MAX_LEASE_DURATION_SEC, 'just-above-max clamps down');
  assert.equal(clampLeaseDurationSec(7200), MAX_LEASE_DURATION_SEC, 'exactly-max passes through as the boundary itself');
  console.log('test-dev-runner-lease-duration: clampLeaseDurationSec clamps at the upper bound (7200) — PASS');
}

function testClampRoundsFractional() {
  assert.equal(clampLeaseDurationSec(45.6), 46, 'fractional seconds round to the nearest integer');
  assert.equal(clampLeaseDurationSec(45.4), 45);
  console.log('test-dev-runner-lease-duration: clampLeaseDurationSec rounds fractional input — PASS');
}

function testClampNonFiniteDefaults() {
  assert.equal(clampLeaseDurationSec(NaN), DEFAULT_LEASE_DURATION_SEC);
  assert.equal(clampLeaseDurationSec(Infinity), DEFAULT_LEASE_DURATION_SEC, 'non-finite input must not silently pass through as the (uncapped) TTL');
  assert.equal(clampLeaseDurationSec('not-a-number'), DEFAULT_LEASE_DURATION_SEC);
  console.log('test-dev-runner-lease-duration: clampLeaseDurationSec defaults on non-finite input — PASS');
}

// --- parseArgs: CLI --lease-duration-sec threading + default -------------------------------

function testParseArgsDefaultWhenFlagAbsent() {
  const out = parseArgs(['start', '--api-port=0', '--ui-port=5173']);
  assert.equal(out.leaseDurationSec, DEFAULT_LEASE_DURATION_SEC, 'omitting --lease-duration-sec must preserve current (30s) behavior');
  console.log('test-dev-runner-lease-duration: parseArgs default when flag absent — PASS');
}

function testParseArgsThreadsDeclaredValue() {
  const out = parseArgs(['start', '--api-port=0', '--ui-port=5173', '--lease-duration-sec=3600']);
  assert.equal(out.leaseDurationSec, 3600, 'an in-range --lease-duration-sec must thread through unclamped');
  console.log('test-dev-runner-lease-duration: parseArgs threads a declared in-range value — PASS');
}

function testParseArgsClampsOutOfRangeAtParseTime() {
  const tooLow = parseArgs(['start', '--api-port=0', '--ui-port=5173', '--lease-duration-sec=5']);
  assert.equal(tooLow.leaseDurationSec, MIN_LEASE_DURATION_SEC, 'CLI-supplied below-min value clamps at parse time');

  const tooHigh = parseArgs(['start', '--api-port=0', '--ui-port=5173', '--lease-duration-sec=99999']);
  assert.equal(tooHigh.leaseDurationSec, MAX_LEASE_DURATION_SEC, 'CLI-supplied above-max value clamps at parse time');
  console.log('test-dev-runner-lease-duration: parseArgs clamps out-of-range CLI values at parse time — PASS');
}

// --- Regression guard: the two write-sites (initial lease + renewal) must use the same --------
// --- clamped value the starter declared, not a re-hardcoded 30. Static source check, not a ----
// --- behavioral probe (no live stack in this test run) — read the exact write-sites and assert
// --- neither reverted to a literal `30` / `30_000` for durationSec/expiresAt. ------------------

function testLeaseWriteSitesUseDeclaredDuration() {
  const fs = require('node:fs');
  const src = fs.readFileSync(path.join(__dirname, 'dev-runner.cjs'), 'utf8');

  // Initial lease record (cmdStart, first writeJsonAtomic(activePath, ...)).
  assert.match(
    src,
    /durationSec:\s*opts\.leaseDurationSec,\s*\n\s*renewedAt:\s*leaseNow,\s*\n\s*expiresAt:\s*new Date\(Date\.now\(\)\s*\+\s*opts\.leaseDurationSec\s*\*\s*1000\)/,
    'initial lease write must use opts.leaseDurationSec for both durationSec and expiresAt, not a hardcoded 30/30_000',
  );

  // Periodic renewal (setInterval callback).
  assert.match(
    src,
    /durationSec:\s*opts\.leaseDurationSec,\s*\n\s*renewedAt:\s*now,\s*\n\s*expiresAt:\s*new Date\(Date\.now\(\)\s*\+\s*opts\.leaseDurationSec\s*\*\s*1000\)/,
    'periodic renewal write must reassert the SAME opts.leaseDurationSec, not a hardcoded 30/30_000 — '
      + 'this is what gives a campaign-length hold its teeth when renewal later pauses on stale activity',
  );

  console.log('test-dev-runner-lease-duration: both lease write-sites use the declared duration, not a hardcoded 30s — PASS');
}

// --- The abandoned-stack REAPER honors the declared hold (735 G6 completion), now keyed by the
// --- owner's harness process (lib/owner-presence.cjs). Behavioural: the real supervisor tick
// --- (createPresenceMonitor) driven by a fake clock, an injected process table and touch record,
// --- and env-shortened thresholds. A quiet-but-declared owner must not be reaped inside its
// --- declared window - proven gap 2026-07-14: a 1k-doc enrichment wait was reaped_abandoned at
// --- ~10 min despite the stack doing its started job.

const OWNER = { harness: 'claude', pid: 900_109, creationTime: '134000000000900109', key: 'claude-900109-134000000000900109' };
const PRESENCE_ENV = { JUSTSEARCH_DEV_REAPER_GRACE_MS: '2000', JUSTSEARCH_DEV_IDLE_SHUTDOWN_MS: '6000' };

/** A supervisor tick over a scripted world: `world.alive`, `world.touchAt`, `world.now`. */
function monitorOver(world) {
  const evaluate = createPresenceMonitor({
    env: PRESENCE_ENV,
    now: () => world.now,
    pidPresence: () => (world.cheap ?? (world.alive ? 'present' : 'absent')),
    readTable: async () => ({
      ok: true,
      readAt: world.now,
      table: [
        { ProcessId: 4, ParentProcessId: 0, Name: 'System', CreationFileTimeUtc: '133000000000000000' },
        ...(world.alive ? [{ ProcessId: OWNER.pid, ParentProcessId: 4, Name: 'claude.exe', CreationFileTimeUtc: OWNER.creationTime }] : []),
      ],
    }),
    readTouch: () => (world.touchAt == null ? null : { lastDevStackTouchAt: new Date(world.touchAt).toISOString() }),
  });
  return (holder, declaredHoldMs) => evaluate({ holder }, declaredHoldMs);
}
const HOLDER = { source: 'claude', agentSessionId: 'label', owner: OWNER };

async function testEndedOwnerPausesThenReapsAfterGrace() {
  const world = { now: 1_000_000, alive: false, touchAt: 1_000_000 };
  const tick = monitorOver(world);
  assert.equal((await tick(HOLDER, 0)).action, 'pause', 'an ended owner pauses renewal first (the lease lapses; admission may reclaim)');
  world.now += 1500;
  assert.equal((await tick(HOLDER, 0)).action, 'pause', 'still inside the grace period');
  world.now += 1000;
  const reap = await tick(HOLDER, 0);
  assert.equal(reap.action, 'reap', 'past the grace period an ended owner is reaped');
  assert.equal(reap.disposition, 'reaped_abandoned');
  console.log('test-dev-runner-lease-duration: an ended owner is paused, then reaped after the grace period - PASS');
}

async function testDeclaredHoldExtendsTheEndedGrace() {
  const world = { now: 1_000_000, alive: false, touchAt: null };
  const tick = monitorOver(world);
  await tick(HOLDER, 10_000);
  world.now += 5000;
  assert.equal((await tick(HOLDER, 10_000)).action, 'pause', 'a declared 10s hold keeps an ended owner unreaped past the 2s grace');
  world.now += 5001;
  assert.equal((await tick(HOLDER, 10_000)).action, 'reap', 'and reaps once the declared hold has passed');
  console.log('test-dev-runner-lease-duration: the declared hold extends the ended-owner grace - PASS');
}

async function testRunningOwnerIdleShutdown() {
  const world = { now: 1_000_000, alive: true, touchAt: 1_000_000 - 1000 };
  const tick = monitorOver(world);
  assert.equal((await tick(HOLDER, 0)).action, 'renew', 'a running owner that used the stack recently keeps it');
  world.touchAt = world.now - 6001;
  const idle = await tick(HOLDER, 0);
  assert.equal(idle.action, 'reap', 'a running owner idle past the shutdown bound is reaped');
  assert.equal(idle.disposition, 'reaped_idle');
  assert.equal((await monitorOver({ ...world })(HOLDER, 5000)).action, 'renew', 'a declared hold raises the idle-shutdown bound');
  world.touchAt = null;
  assert.equal((await tick(HOLDER, 0)).action, 'renew', 'a running owner with no touch record (unknown use) is never reaped');
  console.log('test-dev-runner-lease-duration: a running owner idle past the shutdown bound is reaped, inside its hold it is not - PASS');
}

async function testReapIsConfirmedAgainstAFullTable() {
  // The cheap pid probe says present, but the full table shows the pid was reused: the idle reap
  // must not fire on the cheap answer; the owner reads as ended and renewal pauses instead.
  const world = { now: 1_000_000, alive: false, cheap: 'present', touchAt: 1_000_000 - 60_000 };
  const decision = await monitorOver(world)(HOLDER, 0);
  assert.equal(decision.action, 'pause');
  console.log('test-dev-runner-lease-duration: a reap is confirmed against a full process table first - PASS');
}

async function testUnknownOwnerIsNeverReaped() {
  const world = { now: 1_000_000, alive: false, touchAt: 0 };
  const tick = monitorOver(world);
  const override = { source: 'unknown', agentSessionId: 'x', owner: { harness: 'unknown', pid: null, creationTime: null, key: 'override-x' } };
  const legacy = { source: 'claude', agentSessionId: 'old-label' };
  for (let i = 0; i < 3; i += 1) {
    world.now += 60_000;
    assert.equal((await tick(override, 0)).action, 'renew', 'an override owner (no process identity) is never reaped');
    assert.equal((await tick(legacy, 0)).action, 'renew', 'a pre-change holder (no owner block) is never reaped');
  }
  console.log('test-dev-runner-lease-duration: an owner with no process identity is never reaped - PASS');
}

async function main() {
  testClampDefaultsWhenAbsent();
  testClampWithinRangePassesThrough();
  testClampLowerBound();
  testClampUpperBound();
  testClampRoundsFractional();
  testClampNonFiniteDefaults();
  testParseArgsDefaultWhenFlagAbsent();
  testParseArgsThreadsDeclaredValue();
  testParseArgsClampsOutOfRangeAtParseTime();
  testLeaseWriteSitesUseDeclaredDuration();
  await testEndedOwnerPausesThenReapsAfterGrace();
  await testDeclaredHoldExtendsTheEndedGrace();
  await testRunningOwnerIdleShutdown();
  await testReapIsConfirmedAgainstAFullTable();
  await testUnknownOwnerIsNeverReaped();
  console.log('test-dev-runner-lease-duration: ALL PASS');
}

await main();
