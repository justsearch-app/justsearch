#!/usr/bin/env node
/**
 * capture-evidence-bundle.mjs — EvidenceBundle v1 capture harness.
 *
 * Produces a validator-conformant EvidenceBundle directory from the current dev/CI
 * run state: live API responses (when an API base URL is given), allowlisted log
 * attachments, and declared placeholders for the browser-context artifacts this
 * headless harness cannot capture.
 *
 * Contract (all consumers verified in-repo):
 * - scripts/dev/justsearch-dev-mcp/cli.mjs `runCaptureEvidenceBundle`:
 *   stdout MUST contain EXACTLY ONE non-empty line — the bundle dir. Everything
 *   else goes to stderr. Exit 0 <=> final status is "passed".
 * - scripts/evidence/validate-evidencebundle-v1.mjs:
 *   run-metadata.v1 schema; required artifacts (api-status.json, api-health.json,
 *   browser-console.json, browser-network.json, diagnostics-export.json,
 *   diagnostics.zip, ui-screenshots/); status=passed => zero missing required
 *   paths; status=failed => every missing required path listed in
 *   missing_artifacts; placeholder_artifacts entries must exist as files and
 *   appear in artifacts[]; artifacts[] sorted by path; every file hashed;
 *   browser-network.json must be a JSON array of /api*-scoped entries.
 * - scripts/evidence/validate-determinism-budget-v1.mjs:
 *   determinism_budget { budget, usage, violations } with the four v1 counters.
 * - CI wrappers (scripts/ci/*.ps1, scripts/ci/*.sh, scripts/ops/*.sh):
 *   `--flag=value` form, `--api-base-url=none`, `--external-status`,
 *   `--external-error`, `--attach-dir`. Non-zero exit for external-status=failed
 *   is expected by those callers.
 * - scripts/dev/justsearch-dev-mcp/server.mjs capture_evidence handler:
 *   `--flag value` form, plus --ui-url / --timeout-ms / --include / --session-id.
 *
 * Model identity (tempdoc 842 §2.5): when an API base URL is given, `harness.model` records the
 * realized chat model identity ({ aiActive, chatProfile?, modelPath? }) from a best-effort,
 * short-timeout GET /api/ai/runtime/status. Optional and failure-tolerant — omitted entirely
 * (not a failed/placeholder artifact, not a captured status file) when the endpoint is
 * unreachable or does not (yet) report chat identity.
 *
 * Usage:
 *   node modules/ui-web/scripts/capture-evidence-bundle.mjs \
 *     --scenario <name> --api-base-url <loopback-url|none> [--ui-url <url>] \
 *     [--out-root <dir>] [--run-id <id>] [--session-id <id>] [--timeout-ms <n>] \
 *     [--trace <true|false>] [--include a,b,...] [--attach-label <label>] \
 *     [--attach-file <path>]... [--attach-dir <dir>]... \
 *     [--external-status passed|failed] [--external-error <msg>]
 *
 * Run from the repo root (all callers do): relative paths resolve against cwd,
 * and the durable evidence index is appended at tmp/agent-telemetry/.
 */

import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { createHash } from 'node:crypto';

const REQUIRED_JSON_ARTIFACTS = ['api-status.json', 'api-health.json'];

// The v1 determinism-budget counters (validate-determinism-budget-v1.mjs).
const DETERMINISM_COUNTERS = [
  'sleep.fixed.count',
  'log.scrape_unstructured.count',
  'assert.screenshot_only.count',
  'wait.unbounded.count',
];

// include token -> API endpoint (only endpoints documented in
// docs/reference/api-contract-map.md are mapped; unmapped tokens are skipped
// with a stderr note rather than inventing endpoints).
const INCLUDE_ENDPOINTS = {
  debug: '/api/debug/state',
  effective_config: '/api/debug/effective-config',
  policy: '/api/debug/session-policies',
  inference: '/api/inference/status',
};

function logErr(msg) {
  process.stderr.write(`[capture-evidence] ${msg}\n`);
}

function usageAndExit(code) {
  logErr(
    'Usage: node modules/ui-web/scripts/capture-evidence-bundle.mjs --scenario <name> ' +
      '--api-base-url <loopback-url|none> [--ui-url <url>] [--out-root <dir>] [--run-id <id>] ' +
      '[--session-id <id>] [--timeout-ms <n>] [--trace true|false] [--include a,b] ' +
      '[--attach-label <label>] [--attach-file <path>]... [--attach-dir <dir>]... ' +
      '[--external-status passed|failed] [--external-error <msg>]',
  );
  process.exit(code);
}

function parseArgs(argv) {
  const out = {
    scenario: null,
    apiBaseUrl: null,
    uiUrl: null,
    outRoot: path.join('tmp', 'agent-evidence'),
    runId: null,
    sessionId: null,
    timeoutMs: 60_000,
    trace: false,
    include: [],
    attachLabel: 'attachments',
    attachFiles: [],
    attachDirs: [],
    externalStatus: null,
    externalError: null,
  };

  const takeValue = (args, i, token) => {
    const eq = token.indexOf('=');
    if (eq !== -1) return { value: token.slice(eq + 1), next: i };
    const v = args[i + 1];
    if (v === undefined) throw new Error(`Missing value for ${token}`);
    return { value: v, next: i + 1 };
  };

  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (token === '-h' || token === '--help') usageAndExit(0);
    if (!token.startsWith('--')) throw new Error(`Unexpected positional arg: ${token}`);
    const name = token.includes('=') ? token.slice(0, token.indexOf('=')) : token;
    const { value, next } = takeValue(argv, i, token);
    i = next;
    switch (name) {
      case '--scenario':
        out.scenario = value;
        break;
      case '--api-base-url':
        out.apiBaseUrl = value;
        break;
      case '--ui-url':
        out.uiUrl = value;
        break;
      case '--out-root':
        out.outRoot = value;
        break;
      case '--run-id':
        out.runId = value;
        break;
      case '--session-id':
        out.sessionId = value;
        break;
      case '--timeout-ms':
        out.timeoutMs = Number(value);
        break;
      case '--trace':
        out.trace = String(value).trim().toLowerCase() === 'true';
        break;
      case '--include':
        out.include.push(...String(value).split(',').map((s) => s.trim()).filter(Boolean));
        break;
      case '--attach-label':
        out.attachLabel = value;
        break;
      case '--attach-file':
        out.attachFiles.push(value);
        break;
      case '--attach-dir':
        out.attachDirs.push(value);
        break;
      case '--external-status':
        out.externalStatus = value;
        break;
      case '--external-error':
        out.externalError = value;
        break;
      default:
        throw new Error(`Unknown option: ${name}`);
    }
  }

  if (!out.scenario) throw new Error('--scenario is required');
  if (!out.apiBaseUrl) throw new Error('--api-base-url is required (use "none" when no API runs)');
  if (!Number.isFinite(out.timeoutMs) || out.timeoutMs <= 0) {
    throw new Error(`Invalid --timeout-ms: ${out.timeoutMs}`);
  }
  if (out.externalStatus !== null && out.externalStatus !== 'passed' && out.externalStatus !== 'failed') {
    throw new Error(`--external-status must be "passed" or "failed", got: ${out.externalStatus}`);
  }
  return out;
}

function assertLoopbackUrl(urlStr, label) {
  let u;
  try {
    u = new URL(urlStr);
  } catch {
    throw new Error(`${label} is not a valid URL: ${urlStr}`);
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') {
    throw new Error(`${label} must be http(s): ${urlStr}`);
  }
  const host = u.hostname.toLowerCase();
  const loopback = host === '127.0.0.1' || host === 'localhost' || host === '::1' || host === '[::1]';
  if (!loopback) {
    // Hard invariant: loopback-only network. This harness never talks to a non-loopback host.
    throw new Error(`${label} must be loopback (127.0.0.1/localhost): ${urlStr}`);
  }
  return u;
}

function toPosix(p) {
  return p.split(path.sep).join('/');
}

function nowCompact() {
  return new Date().toISOString().replace(/[-:]/g, '').replace(/\..*$/, '').replace('T', '-');
}

async function sha256File(filePath) {
  const buf = await fsp.readFile(filePath);
  return { sha256: createHash('sha256').update(buf).digest('hex'), bytes: buf.byteLength };
}

async function listFilesRecursive(rootDir) {
  const out = [];
  const walk = async (dir) => {
    const entries = await fsp.readdir(dir, { withFileTypes: true });
    for (const ent of entries) {
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) await walk(full);
      else if (ent.isFile()) out.push(full);
    }
  };
  await walk(rootDir);
  return out;
}

async function fetchJson(baseUrl, endpointPath, timeoutMs) {
  const url = new URL(endpointPath, baseUrl).toString();
  const res = await fetch(url, {
    signal: AbortSignal.timeout(timeoutMs),
    headers: { accept: 'application/json' },
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`GET ${url} -> HTTP ${res.status}`);
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`GET ${url} returned non-JSON (${text.slice(0, 120)})`);
  }
}

/** Copy a source file into attachments/<label>/, de-duplicating basename collisions. */
async function copyAttachment(bundleDir, label, srcAbs, usedNames) {
  const base = path.basename(srcAbs);
  let name = base;
  let n = 1;
  while (usedNames.has(name)) {
    const ext = path.extname(base);
    name = `${path.basename(base, ext)}-${n}${ext}`;
    n += 1;
  }
  usedNames.add(name);
  const destDir = path.join(bundleDir, 'attachments', label);
  await fsp.mkdir(destDir, { recursive: true });
  await fsp.copyFile(srcAbs, path.join(destDir, name));
  return toPosix(path.join('attachments', label, name));
}

async function copyAttachmentDir(bundleDir, label, srcDirAbs) {
  const destRoot = path.join(bundleDir, 'attachments', label, path.basename(srcDirAbs));
  const copied = [];
  const walk = async (srcDir, destDir) => {
    await fsp.mkdir(destDir, { recursive: true });
    const entries = await fsp.readdir(srcDir, { withFileTypes: true });
    for (const ent of entries) {
      const s = path.join(srcDir, ent.name);
      const d = path.join(destDir, ent.name);
      if (ent.isDirectory()) await walk(s, d);
      else if (ent.isFile()) {
        await fsp.copyFile(s, d);
        copied.push(toPosix(path.relative(bundleDir, d)));
      }
    }
  };
  await walk(srcDirAbs, destRoot);
  return copied;
}

/** Minimal valid empty ZIP: the 22-byte end-of-central-directory record. */
function emptyZipBytes() {
  const buf = Buffer.alloc(22);
  buf.writeUInt32LE(0x06054b50, 0);
  return buf;
}

function artifactTypeFor(relPosix) {
  if (relPosix.startsWith('api-')) return 'api-response';
  if (relPosix.startsWith('browser-')) return 'browser';
  if (relPosix.startsWith('diagnostics')) return 'diagnostics';
  if (relPosix.startsWith('ui-screenshots/')) return 'screenshot';
  if (relPosix.startsWith('attachments/')) return 'attachment';
  if (relPosix.startsWith('include-')) return 'api-response';
  return 'file';
}

/**
 * Append one line to the durable evidence index (tmp/agent-telemetry/evidence-index.ndjson,
 * same dir convention as session-merges.ndjson — see scripts/agent-analytics/record-merge.mjs).
 * Best-effort: indexing must never fail a capture.
 */
function appendEvidenceIndex(repoRoot, record) {
  try {
    const file = path.join(repoRoot, 'tmp', 'agent-telemetry', 'evidence-index.ndjson');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.appendFileSync(file, JSON.stringify(record) + '\n', 'utf8');
  } catch (err) {
    logErr(`evidence-index append failed (non-fatal): ${err?.message || String(err)}`);
  }
}

async function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (err) {
    logErr(String(err?.message || err));
    usageAndExit(2);
  }

  const repoRoot = process.cwd(); // all callers run from the repo root
  const startedAt = new Date().toISOString();

  const hasApi = opts.apiBaseUrl !== 'none';
  if (hasApi) assertLoopbackUrl(opts.apiBaseUrl, 'apiBaseUrl');
  if (opts.uiUrl) assertLoopbackUrl(opts.uiUrl, 'uiUrl');

  const runId = opts.runId || `capture-${nowCompact()}-${process.pid}`;
  const outRootAbs = path.resolve(repoRoot, opts.outRoot);
  const bundleName = `${opts.scenario}-${nowCompact()}-${String(process.pid)}`;
  const bundleDir = path.join(outRootAbs, bundleName);
  await fsp.mkdir(bundleDir, { recursive: true });

  const missingArtifacts = [];
  const placeholderArtifacts = [];
  const errors = [];
  const skippedAttachments = [];
  const perRequestTimeout = Math.min(opts.timeoutMs, 30_000);

  const writeJson = async (rel, obj) => {
    await fsp.writeFile(path.join(bundleDir, rel), JSON.stringify(obj, null, 2) + '\n', 'utf8');
  };

  // 1. Live API captures (required artifacts) — or declared placeholders/missing.
  let apiCaptureFailed = false;
  if (hasApi) {
    const endpoints = { 'api-status.json': '/api/status', 'api-health.json': '/api/health' };
    for (const [rel, ep] of Object.entries(endpoints)) {
      try {
        const body = await fetchJson(opts.apiBaseUrl, ep, perRequestTimeout);
        await writeJson(rel, body);
        if (opts.trace) logErr(`captured ${rel} from ${ep}`);
      } catch (err) {
        apiCaptureFailed = true;
        missingArtifacts.push(rel);
        errors.push(`${rel}: ${err?.message || String(err)}`);
        logErr(`FAILED to capture ${rel}: ${err?.message || String(err)}`);
      }
    }
  } else {
    // No API in this context (CI wrappers pass --api-base-url=none): required
    // artifacts become declared placeholders so a passed bundle still validates.
    for (const rel of REQUIRED_JSON_ARTIFACTS) {
      await writeJson(rel, { placeholder: true, reason: 'api-base-url=none (no HTTP API in this context)' });
      placeholderArtifacts.push({ path: rel, reason: 'api-base-url=none (no HTTP API in this context)' });
    }
  }

  // 2. Browser-context artifacts: this harness runs without a browser, so they are
  //    written as DECLARED placeholders per the validator's placeholder rules
  //    (file exists + listed in placeholder_artifacts + hashed in artifacts[]).
  const noBrowser = 'no browser context in this harness (headless capture)';
  await writeJson('browser-console.json', []); // shape: array of console events
  placeholderArtifacts.push({ path: 'browser-console.json', reason: noBrowser });
  await writeJson('browser-network.json', []); // validator requires a JSON array
  placeholderArtifacts.push({ path: 'browser-network.json', reason: noBrowser });
  await writeJson('diagnostics-export.json', { placeholder: true, reason: noBrowser });
  placeholderArtifacts.push({ path: 'diagnostics-export.json', reason: noBrowser });
  await fsp.writeFile(path.join(bundleDir, 'diagnostics.zip'), emptyZipBytes());
  placeholderArtifacts.push({ path: 'diagnostics.zip', reason: `${noBrowser}; valid empty zip` });
  await fsp.mkdir(path.join(bundleDir, 'ui-screenshots'), { recursive: true }); // required dir

  // 3. Optional includes (extra API captures; non-required, so failures don't gate).
  for (const inc of opts.include) {
    const ep = INCLUDE_ENDPOINTS[inc];
    if (!ep) {
      logErr(`include '${inc}' has no mapped endpoint; skipped`);
      continue;
    }
    if (!hasApi) {
      logErr(`include '${inc}' skipped (api-base-url=none)`);
      continue;
    }
    try {
      const body = await fetchJson(opts.apiBaseUrl, ep, perRequestTimeout);
      await writeJson(`include-${inc.replace(/_/g, '-')}.json`, body);
    } catch (err) {
      logErr(`include '${inc}' capture failed (non-fatal): ${err?.message || String(err)}`);
    }
  }

  // 3b. Realized chat model identity (tempdoc 842 §2.5) — best-effort, non-required, folded
  //    into the harness/environment block. GET /api/ai/runtime/status may not yet report chat
  //    identity fields at all (a parallel Java slice adds them); extract defensively, the same
  //    shape as the dev-mcp quick_health projection, and omit the whole `model` object rather
  //    than guess when the endpoint is unreachable or silent on chat identity.
  let modelInfo;
  if (hasApi) {
    try {
      const st = await fetchJson(opts.apiBaseUrl, '/api/ai/runtime/status', Math.min(perRequestTimeout, 3000));
      // Tempdoc 842 review D3: activation-completed alone is a false negative for autostarted
      // engines; realized identity (active.modelPath) is projected only while the engine is
      // online, so its presence is an equally authoritative online signal.
      const aiActive =
        (st?.activation?.state === 'completed' && !!st?.active?.activeVariantId) ||
        st?.active?.modelPath != null;
      const chatProfile = st?.active?.chatProfile ?? st?.chatProfile ?? null;
      const modelPath = st?.active?.modelPath ?? st?.active?.llmModelPath ?? st?.modelPath ?? null;
      modelInfo = {
        aiActive,
        ...(chatProfile != null ? { chatProfile } : {}),
        ...(modelPath != null ? { modelPath } : {}),
      };
    } catch (err) {
      if (opts.trace) logErr(`model identity capture skipped (non-fatal): ${err?.message || String(err)}`);
    }
  }

  // 4. Attachments (allowlisting is the caller's concern — the MCP server enforces
  //    its allowlist before spawning; CI wrappers pass their own log paths).
  const usedNames = new Set();
  const attachedPaths = [];
  for (const p of opts.attachFiles) {
    const abs = path.isAbsolute(p) ? p : path.resolve(repoRoot, p);
    let st = null;
    try {
      st = await fsp.lstat(abs);
    } catch {
      st = null;
    }
    if (!st || !st.isFile() || st.isSymbolicLink()) {
      skippedAttachments.push(toPosix(p));
      logErr(`attachment skipped (missing / not a regular file): ${p}`);
      continue;
    }
    attachedPaths.push(await copyAttachment(bundleDir, opts.attachLabel, abs, usedNames));
  }
  for (const d of opts.attachDirs) {
    const abs = path.isAbsolute(d) ? d : path.resolve(repoRoot, d);
    let st = null;
    try {
      st = await fsp.lstat(abs);
    } catch {
      st = null;
    }
    if (!st || !st.isDirectory()) {
      skippedAttachments.push(toPosix(d));
      logErr(`attachment dir skipped (missing / not a directory): ${d}`);
      continue;
    }
    attachedPaths.push(...(await copyAttachmentDir(bundleDir, opts.attachLabel, abs)));
  }

  // 5. Final status. external-status=failed always forces failed; a failed required
  //    capture forces failed regardless of external-status (a passed bundle must
  //    have zero missing required artifacts — validator invariant).
  const status = opts.externalStatus === 'failed' || apiCaptureFailed ? 'failed' : 'passed';
  if (opts.externalError) errors.push(opts.externalError);
  const finishedAt = new Date().toISOString();

  // 6. artifacts[]: every file in the bundle (run-metadata.json excluded), hashed,
  //    sorted lexicographically by path (validator treats unsorted as an error).
  const files = await listFilesRecursive(bundleDir);
  const rels = files
    .map((abs) => toPosix(path.relative(bundleDir, abs)))
    .filter((rel) => rel !== 'run-metadata.json')
    .sort((a, b) => a.localeCompare(b));
  const artifacts = [];
  for (const rel of rels) {
    const { sha256, bytes } = await sha256File(path.join(bundleDir, rel.split('/').join(path.sep)));
    artifacts.push({ type: artifactTypeFor(rel), path: rel, sha256, bytes });
  }

  const zeroCounters = Object.fromEntries(DETERMINISM_COUNTERS.map((k) => [k, 0]));
  const meta = {
    schema: 'run-metadata.v1',
    evidence_bundle_version: 'EvidenceBundle/v1',
    scenario: opts.scenario,
    run_id: runId,
    ...(opts.sessionId ? { session_id: opts.sessionId } : {}),
    started_at: startedAt,
    finished_at: finishedAt,
    status,
    inputs: {
      api_base_url: opts.apiBaseUrl,
      ...(opts.uiUrl ? { ui_url: opts.uiUrl } : {}),
      out_root: toPosix(opts.outRoot),
      timeout_ms: opts.timeoutMs,
      trace: opts.trace,
      include: opts.include,
      attach_label: opts.attachLabel,
      attachments: attachedPaths,
      ...(skippedAttachments.length > 0 ? { skipped_attachments: skippedAttachments } : {}),
      ...(opts.externalStatus ? { external_status: opts.externalStatus } : {}),
    },
    harness: {
      name: 'capture-evidence-bundle',
      version: '1.0.0',
      node: process.version,
      platform: process.platform,
      ...(modelInfo ? { model: modelInfo } : {}),
    },
    // This harness performs no fixed sleeps, log scraping, screenshot-only
    // assertions, or unbounded waits — all four v1 counters are 0/0.
    determinism_budget: { budget: zeroCounters, usage: { ...zeroCounters }, violations: [] },
    missing_artifacts: missingArtifacts,
    placeholder_artifacts: placeholderArtifacts,
    artifacts,
    ...(errors.length > 0 ? { errors } : {}),
  };
  await fsp.writeFile(path.join(bundleDir, 'run-metadata.json'), JSON.stringify(meta, null, 2) + '\n', 'utf8');

  // 7. Durable index (append-only NDJSON; lives in the capture script so plain CLI
  //    invocations index too, not only the MCP handler path).
  appendEvidenceIndex(repoRoot, {
    schema: 'evidence-index.v1',
    session_id: opts.sessionId || null,
    run_id: runId,
    scenario: opts.scenario,
    bundle_path: toPosix(path.relative(repoRoot, bundleDir)),
    status,
    started_at: startedAt,
    finished_at: finishedAt,
    artifact_count: artifacts.length,
    ts: new Date().toISOString(),
  });

  // 8. The stdout contract: EXACTLY ONE line — the bundle dir (repo-relative posix
  //    when under the repo root, else absolute). Exit 0 iff status=passed.
  const relBundle = path.relative(repoRoot, bundleDir);
  const printable = relBundle && !relBundle.startsWith('..') ? toPosix(relBundle) : bundleDir;
  process.stdout.write(printable + '\n');
  // Let Node drain fetch/Undici teardown and the stdout pipe before exiting. Forced
  // process.exit() on affected Windows Node releases can race delayed libuv cleanup
  // (and produced UV_HANDLE_CLOSING after this bundle was written successfully).
  process.exitCode = status === 'passed' ? 0 : 1;
}

main().catch((err) => {
  logErr(`capture crashed: ${err?.stack || err?.message || String(err)}`);
  process.exitCode = 2;
});
