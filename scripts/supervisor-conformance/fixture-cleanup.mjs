import fs from 'node:fs';
import path from 'node:path';
import actuator from '../dev/lib/engine-actuator.cjs';

export function fixtureIdentities(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter(name => /^\d+-\d+\.json$/.test(name))
    .map(name => ({ ...JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8')), file: path.join(dir, name) }));
}

/**
 * A process creation time may trail the precise clock the fixture read by up to one system clock
 * tick (15.6 ms by default), so a birth this close below the held-at bound is not attributed.
 */
const HELD_AT_MARGIN_TICKS = 200000n; // 20 ms

/**
 * The fixture recorded its PID at a moment it held it. A live process under that PID born
 * before that moment is the fixture (no two processes hold one PID at once); one born after it
 * reuses the PID. A birth within the clock margin is unknown and never authorizes a stop.
 */
function resolveBirth(identity, row) {
  if (identity.creationFileTimeUtc) return actuator.sameBirth(identity.creationFileTimeUtc, row.creationFileTime) ? 'owned' : 'other';
  if (!/^[1-9][0-9]*$/.test(identity.heldAtFileTimeUtc ?? '')) return 'unknown';
  const birth = BigInt(row.creationFileTime), heldAt = BigInt(identity.heldAtFileTimeUtc);
  if (birth > heldAt) return 'other';
  return birth < heldAt - HELD_AT_MARGIN_TICKS ? 'resolved' : 'unknown';
}

/** The resolved birth is durable before any stop can use it, written aside and renamed. */
function recordBirth(identity, birth) {
  const { file, ...fields } = identity;
  fs.writeFileSync(`${file}.tmp`, JSON.stringify({ ...fields, creationFileTimeUtc: birth }));
  fs.renameSync(`${file}.tmp`, file);
}

/**
 * Positive identity queries. Owned fixtures get `graceMs` to finish their orderly exit; after that,
 * held-handle stops go only to still matching births, never to a PID alone or a process tree.
 */
export async function awaitFixtureExit(dir, { sleep, graceMs = 5000, timeoutMs = 10000,
  lookup = actuator.lookupProcessIdentities, terminate = actuator.terminateWithCode } = {}) {
  const started = Date.now();
  for (;;) {
    const identities = fixtureIdentities(dir);
    const observed = await lookup(identities.map(i => i.pid));
    if (!observed) throw new Error('fixture exit identity query failed; artifacts retained');
    const live = identities.filter(i => {
      const row = observed.get(i.pid);
      if (!row || (row.present && !row.creationFileTime)) throw new Error('fixture identity unknown; artifacts retained');
      if (!row.present) return false;
      const verdict = resolveBirth(i, row);
      if (verdict === 'unknown') throw new Error('fixture identity unknown; artifacts retained');
      if (verdict === 'resolved') {
        recordBirth(i, row.creationFileTime);
        i.creationFileTimeUtc = row.creationFileTime;
      }
      return verdict !== 'other';
    });
    if (!live.length) return;
    const elapsed = Date.now() - started;
    if (elapsed >= graceMs) {
      for (const identity of live) {
        const result = await terminate(identity.pid, identity.creationFileTimeUtc, 1);
        if (!result.ok && !['gone', 'mismatch'].includes(result.outcome)) {
          throw new Error('fixture identity-bound stop failed; artifacts retained');
        }
      }
    }
    if (elapsed >= graceMs + timeoutMs) throw new Error('fixture exit unconfirmed; artifacts retained');
    await sleep(100);
  }
}

/** False only when no running process holds the PID; a denied or invalid query counts as running. */
function processRunning(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error.code !== 'ESRCH';
  }
}

/**
 * The common teardown: the runner has ended and its job has ended the fixtures it started. A
 * live fixture still holds its recorded PID, so a PID no running process holds confirms that
 * fixture's exit in process, without a birth query. False when any PID is still taken after
 * `windowMs`; those identities are left to `awaitFixtureExit`.
 */
export async function fixturesExited(dir, { windowMs = 1000, pollMs = 10, running = processRunning } = {}) {
  const deadline = Date.now() + windowMs;
  for (;;) {
    if (!fixtureIdentities(dir).some(identity => running(identity.pid))) return true;
    if (Date.now() >= deadline) return false;
    await new Promise(resolve => setTimeout(resolve, pollMs));
  }
}

export function removeFixtureDirectory(directory) {
  const root = path.resolve(directory);
  if (root === path.parse(root).root) throw new Error('refusing fixture filesystem root');
  const unlinkLinks = dir => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const file = path.join(dir, entry.name);
      if (fs.lstatSync(file).isSymbolicLink()) fs.unlinkSync(file);
      else if (entry.isDirectory()) unlinkLinks(file);
    }
  };
  if (!fs.existsSync(root)) return;
  if (fs.lstatSync(root).isSymbolicLink()) { fs.unlinkSync(root); return; }
  unlinkLinks(root);
  fs.rmSync(root, { recursive: true, force: true, maxRetries: 5 });
}
