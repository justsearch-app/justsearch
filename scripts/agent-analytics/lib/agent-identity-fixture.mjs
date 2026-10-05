/**
 * Test support: simulate an agent session for code that resolves identity through
 * `scripts/dev/lib/agent-identity.cjs`, without depending on whichever harness runs the tests.
 *
 * `writeHarnessFixture` writes a `JUSTSEARCH_PROCESS_TABLE_FIXTURE` file whose walk starts at a
 * fake `selfPid` and reaches a fake harness process, and returns the owner block that resolution
 * yields. `identityEnv` builds a child environment that uses it and strips every inherited identity
 * input (override, hand-off, harness labels, CI), so the result is the same under Claude, Codex,
 * a plain shell or CI.
 */
import fs from 'node:fs';
import path from 'node:path';

export const IDENTITY_ENV_KEYS = Object.freeze([
  'JUSTSEARCH_AGENT_IDENTITY',
  'JUSTSEARCH_AGENT_IDENTITY_HANDOFF',
  'JUSTSEARCH_PROCESS_TABLE_FIXTURE',
  'CLAUDE_CODE_SESSION_ID',
  'CLAUDE_PID',
  'CODEX_THREAD_ID',
  'CODEX_SESSION_ID',
  'CODEX_HOME',
  'JUSTSEARCH_AGENT_SESSION_ID',
  'CI',
]);

let counter = 0;

/**
 * @returns {{ file: string, owner: {harness:string,pid:number,creationTime:string,key:string}, rows: object[] }}
 */
export function writeHarnessFixture(dir, {
  harness = 'claude',
  pid = 900000 + (counter += 10),
  creationTime = String(134000000000000000n + BigInt(pid)),
  selfPid = pid + 1,
  extraRows = [],
  name = null,
} = {}) {
  fs.mkdirSync(dir, { recursive: true });
  const harnessName = name || (harness === 'codex' ? 'codex.exe' : 'claude.exe');
  const rows = [
    { ProcessId: pid, ParentProcessId: 4, Name: harnessName, CommandLine: harnessName, CreationFileTimeUtc: creationTime },
    { ProcessId: selfPid, ParentProcessId: pid, Name: 'node.exe', CommandLine: 'node test', CreationFileTimeUtc: String(BigInt(creationTime) + 1000n) },
    ...extraRows,
  ];
  const file = path.join(dir, `process-table-${harness}-${pid}.json`);
  fs.writeFileSync(file, JSON.stringify({ selfPid, rows }), 'utf8');
  return { file, owner: { harness, pid, creationTime, key: `${harness}-${pid}-${creationTime}` }, rows };
}

/** A fixture table with no harness at all (the "no agent" case). */
export function writeNoHarnessFixture(dir, { selfPid = 990001 } = {}) {
  fs.mkdirSync(dir, { recursive: true });
  const rows = [
    { ProcessId: selfPid - 1, ParentProcessId: 4, Name: 'explorer.exe', CommandLine: 'explorer', CreationFileTimeUtc: '134000000000000000' },
    { ProcessId: selfPid, ParentProcessId: selfPid - 1, Name: 'node.exe', CommandLine: 'node test', CreationFileTimeUtc: '134000000000001000' },
  ];
  const file = path.join(dir, `process-table-none-${selfPid}.json`);
  fs.writeFileSync(file, JSON.stringify({ selfPid, rows }), 'utf8');
  return { file };
}

/** A child environment resolving identity from `fixtureFile` (or nothing), plus `extra`. */
export function identityEnv(fixtureFile, extra = {}, base = process.env) {
  const env = { ...base };
  for (const key of IDENTITY_ENV_KEYS) delete env[key];
  if (fixtureFile) env.JUSTSEARCH_PROCESS_TABLE_FIXTURE = fixtureFile;
  return { ...env, ...extra };
}
