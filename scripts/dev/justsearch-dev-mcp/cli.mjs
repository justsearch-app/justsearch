import { spawn } from 'node:child_process';
import fsp from 'node:fs/promises';
import path from 'node:path';

import { logWarn } from './log.mjs';

function tail(str, maxChars) {
  const s = String(str || '');
  if (s.length <= maxChars) return s;
  return s.slice(s.length - maxChars);
}

function splitNonEmptyLines(s) {
  return String(s || '')
    .split(/\r?\n/)
    .map((x) => x.trim())
    .filter(Boolean);
}

function spawnDevRunner({ repoRoot, devRunnerPath, args, env }) {
  return spawn(process.execPath, [devRunnerPath, ...args], {
    cwd: repoRoot,
    // The caller's identity hand-off (and nothing else) is added per call; see agent-identity.cjs.
    env: env ? { ...process.env, ...env } : process.env,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
}

export async function runCliJson({ repoRoot, devRunnerPath, args, timeoutMs, mode, env = null }) {
  if (mode !== 'oneshot' && mode !== 'supervisor_first_line') {
    throw new Error(`Invalid runCliJson mode: ${mode}`);
  }
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new Error(`Invalid timeoutMs: ${timeoutMs}`);
  }

  const child = spawnDevRunner({ repoRoot, devRunnerPath, args, env });

  let stdoutBuf = '';
  let stderrBuf = '';
  let settled = false;

  if (child.stdout) child.stdout.setEncoding('utf8');
  if (child.stderr) child.stderr.setEncoding('utf8');

  const kill = () => {
    try {
      child.kill();
    } catch (_) {}
  };

  return await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      kill();
      reject(new Error(`dev-runner timed out after ${timeoutMs}ms (${mode})`));
    }, timeoutMs);

    const finishErr = (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      kill();
      reject(err);
    };

    const finishOk = (payload) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(payload);
    };

    if (child.stdout) {
      child.stdout.on('data', (chunk) => {
        stdoutBuf += chunk;

        if (mode === 'oneshot') return;

        // supervisor_first_line: resolve on the first complete line.
        const idx = stdoutBuf.indexOf('\n');
        if (idx === -1) return;

        const firstLine = stdoutBuf.slice(0, idx).trim();
        if (!firstLine) {
          return finishErr(new Error('dev-runner produced empty first stdout line (expected JSON)'));
        }

        let obj;
        try {
          obj = JSON.parse(firstLine);
        } catch (e) {
          return finishErr(
            new Error(`dev-runner first stdout line was not JSON: ${tail(firstLine, 500)}`),
          );
        }

        // Drain any further stdout to avoid backpressure (dev-runner should not write more in --json mode).
        try {
          child.stdout.removeAllListeners('data');
          child.stdout.on('data', () => {});
          child.stdout.resume();
        } catch (_) {}

        finishOk({ exitCode: null, json: obj, stderr: tail(stderrBuf, 8000), pid: child.pid });
      });
    }

    if (child.stderr) {
      child.stderr.on('data', (chunk) => {
        stderrBuf += chunk;
        // keep only a tail to avoid unbounded memory
        stderrBuf = tail(stderrBuf, 32_000);
      });
    }

    child.on('error', (err) => {
      finishErr(new Error(`dev-runner spawn failed: ${err?.message || String(err)}`));
    });

    child.on('exit', (code) => {
      if (mode === 'supervisor_first_line') {
        if (!settled) {
          clearTimeout(timer);
          settled = true;
          return reject(
            new Error(
              `dev-runner exited before producing JSON (${code ?? 'unknown'}). stderr=${tail(
                stderrBuf,
                2000,
              )}`,
            ),
          );
        }
        return;
      }

      // oneshot mode: parse exactly one JSON line from stdout.
      const lines = splitNonEmptyLines(stdoutBuf);
      if (lines.length !== 1) {
        return finishErr(
          new Error(
            `dev-runner stdout must contain exactly one JSON line (got ${lines.length}). ` +
              `stdoutTail=${tail(stdoutBuf, 1200)} stderrTail=${tail(stderrBuf, 2000)}`,
          ),
        );
      }
      const line = lines[0];
      let obj;
      try {
        obj = JSON.parse(line);
      } catch (e) {
        return finishErr(
          new Error(
            `dev-runner stdout was not JSON. stdout=${tail(line, 500)} stderrTail=${tail(
              stderrBuf,
              2000,
            )}`,
          ),
        );
      }

      finishOk({ exitCode: code ?? 0, json: obj, stderr: tail(stderrBuf, 8000), pid: child.pid });
    });
  });
}

export async function readRunJson({ repoRoot, runId }) {
  const p = path.join(repoRoot, 'tmp', 'dev-runner', 'runs', runId, 'run.json');
  const raw = await fsp.readFile(p, 'utf8');
  return JSON.parse(raw);
}

export function buildDevRunnerArgsStart({ apiPort, uiPort, clean, dataDir, takeover, skipBuild, hotReload, sessionId, leaseDurationSec, chatProfile, distFromRoot }) {
  const out = [
    'start',
    '--json',
    `--api-port=${apiPort}`,
    `--ui-port=${uiPort}`,
    `--clean=${clean}`,
  ];
  if (dataDir) out.push(`--data-dir=${dataDir}`);
  if (takeover) out.push(`--takeover=${takeover}`);
  if (skipBuild) out.push('--skip-build');
  if (hotReload) out.push('--hot-reload');
  if (sessionId) out.push(`--session-id=${sessionId}`);
  // Tempdoc 735 G6: forward whatever finite value the caller gave — dev-runner.cjs's
  // clampLeaseDurationSec is the single clamp authority (parseArgs), so this layer does not
  // duplicate the [30, 7200] bound.
  if (leaseDurationSec != null && Number.isFinite(Number(leaseDurationSec))) {
    out.push(`--lease-duration-sec=${leaseDurationSec}`);
  }
  // Tempdoc 842 §2.4: forward the chat model profile; dev-runner.cjs owns the "compact" dev
  // default when this is omitted.
  if (chatProfile) out.push(`--chat-profile=${chatProfile}`);
  // Tempdoc 913 T1: the checkout the caller ASKED to launch from, resolved. Forwarded so the run's
  // provenance can say "launched where asked" — without it, a `distFrom` launch is indistinguishable
  // from a stack that drifted onto a foreign checkout, and quick_health called every one of them
  // `rebuildFirst`. Omitted (not empty) when the caller passed no distFrom, so the record does not
  // claim a requested root that was never requested.
  if (distFromRoot) out.push(`--dist-from=${distFromRoot}`);
  return out;
}

export function buildDevRunnerArgsStop({ runId, force, sessionId }) {
  const out = ['stop', '--json', `--run=${runId}`];
  if (force) out.push('--force');
  if (sessionId) out.push(`--session-id=${sessionId}`);
  return out;
}

export function buildDevRunnerArgsStatus({ runId }) {
  const out = ['status', '--json'];
  if (runId) out.push(`--run=${runId}`);
  else out.push('--active');
  return out;
}

export function buildDevRunnerArgsCleanup({ runId, clean, force, sessionId }) {
  const out = ['cleanup', '--json', `--run=${runId}`, `--clean=${clean}`];
  if (force) out.push('--force');
  if (sessionId) out.push(`--session-id=${sessionId}`);
  return out;
}

export function coerceExitAwareOk(json, exitCode) {
  const devRunnerOk = !!json?.ok;
  const ok = devRunnerOk && exitCode === 0;
  if (!ok && devRunnerOk) {
    logWarn('dev-runner exited non-zero despite ok:true (treating as failure)', `exitCode=${exitCode}`);
  }
  return { ...json, ok, ...(devRunnerOk ? { devRunnerOk } : {}), exitCode };
}


