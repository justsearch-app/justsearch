import fs from 'node:fs';
import path from 'node:path';
import actuator from '../dev/lib/engine-actuator.cjs';

export function fixtureIdentities(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter(name => /^\d+-\d+\.json$/.test(name))
    .map(name => JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8')));
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
      return row.present && actuator.sameBirth(i.creationFileTimeUtc, row.creationFileTime);
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
