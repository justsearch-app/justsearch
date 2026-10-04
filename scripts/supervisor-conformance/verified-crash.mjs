/** Shared actual-death mechanic for real-writer replay and paired Stage E. */
import identity from '../dev/lib/process-identity.cjs';
export function verifyOwned(record, expectedData, table = identity.readProcessTable()) {
  if (!record?.cmdlineFingerprint?.includes(expectedData)) throw new Error('Crash target does not name the owned data directory');
  const result = identity.verifyProcessIdentity({ record, table });
  if (!identity.isVerifiedMatch(result)) throw new Error(`Refusing unverified fault: ${result.reason}`);
  return result;
}
export function killOwned(record, expectedData, { table, kill = process.kill, now = Date.now } = {}) {
  verifyOwned(record, expectedData, table);
  const issuedAtMs = now(); kill(record.pid, 'SIGKILL');
  return { pid: record.pid, creationFileTimeUtc: record.creationFileTimeUtc, issuedAtMs,
    issuedAt: new Date(issuedAtMs).toISOString(), signal: 'SIGKILL' };
}
