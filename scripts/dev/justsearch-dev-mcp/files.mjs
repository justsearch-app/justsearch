import fsp from 'node:fs/promises';
import path from 'node:path';

import { resolveUnderRepo } from './paths.mjs';

function toPosix(relOrAbs) {
  return String(relOrAbs).split(path.sep).join('/');
}

function fileContractError(code, message) {
  const error = new Error(message);
  error.code = code;
  return error;
}

function allowedRunFileRelPosix(runId, kind) {
  const base = `runs/${runId}`;
  switch (kind) {
    case 'backend_stdout':
      return `${base}/logs/backend.stdout.log`;
    case 'backend_stderr':
      return `${base}/logs/backend.stderr.log`;
    case 'frontend_stdout':
      return `${base}/logs/frontend.stdout.log`;
    case 'frontend_stderr':
      return `${base}/logs/frontend.stderr.log`;
    case 'stop_report':
      return `${base}/stop-report.json`;
    case 'run_json':
      return `${base}/run.json`;
    default:
      throw new Error(`Unknown run file kind: ${kind}`);
  }
}

export function resolveAllowedRunFile({ repoRoot, runId, kind }) {
  const relPosix = allowedRunFileRelPosix(runId, kind);
  const relNative = relPosix.split('/').join(path.sep);
  const rel = resolveUnderRepo(repoRoot, relNative, 'runFile');
  const abs = path.resolve(repoRoot, rel);
  return { relPosix: toPosix(rel), abs };
}

export async function readJsonFileNoSymlinks({ repoRoot, relPosix, maxBytes = 2_000_000 }) {
  const relNative = String(relPosix).split('/').join(path.sep);
  const rel = resolveUnderRepo(repoRoot, relNative, 'jsonFile');
  const abs = path.resolve(repoRoot, rel);

  const st = await fsp.lstat(abs);
  if (st.isSymbolicLink()) {
    throw fileContractError('DEV_MCP_FILE_SYMLINK', `File must not be a symlink: ${relPosix}`);
  }
  if (!st.isFile()) {
    throw fileContractError('DEV_MCP_FILE_NOT_FILE', `Expected file: ${relPosix}`);
  }
  if (st.size > maxBytes) {
    throw fileContractError(
      'DEV_MCP_FILE_TOO_LARGE',
      `File too large: ${relPosix} bytes=${st.size} maxBytes=${maxBytes}`,
    );
  }

  const real = await fsp.realpath(abs);
  resolveUnderRepo(repoRoot, real, 'jsonFileRealPath');

  const text = await fsp.readFile(abs, 'utf8');
  return JSON.parse(text);
}

export async function tailTextFileNoSymlinks({ repoRoot, relPosix, maxBytes, maxLines }) {
  const relNative = String(relPosix).split('/').join(path.sep);
  const rel = resolveUnderRepo(repoRoot, relNative, 'tailFile');
  const abs = path.resolve(repoRoot, rel);

  let st;
  try {
    st = await fsp.lstat(abs);
  } catch {
    return { ok: false, truncated: false, bytesRead: 0, text: '' };
  }
  if (st.isSymbolicLink()) throw new Error(`File must not be a symlink: ${relPosix}`);
  if (!st.isFile()) throw new Error(`Expected file: ${relPosix}`);

  const real = await fsp.realpath(abs).catch(() => null);
  if (real) resolveUnderRepo(repoRoot, real, 'tailFileRealPath');

  const size = Number(st.size || 0);
  const readBytes = Math.min(Math.max(0, maxBytes), size);
  const start = Math.max(0, size - readBytes);

  const fh = await fsp.open(abs, 'r');
  try {
    const buf = Buffer.alloc(readBytes);
    if (readBytes > 0) {
      await fh.read(buf, 0, readBytes, start);
    }

    let text = buf.toString('utf8');
    let truncated = start > 0;

    // If we started mid-file, drop the first partial line for cleaner tails.
    if (start > 0) {
      const idx = text.indexOf('\n');
      if (idx !== -1) text = text.slice(idx + 1);
    }

    const lines = text.split(/\r?\n/);
    if (lines.length > maxLines) {
      truncated = true;
      text = lines.slice(lines.length - maxLines).join('\n');
    }

    const bytesRead = Buffer.byteLength(text, 'utf8');
    return { ok: true, truncated, bytesRead, text };
  } finally {
    await fh.close().catch(() => {});
  }
}

async function listBundleRoots({ rootDirAbs, repoRoot }) {
  const out = [];
  const rootReal = await fsp.realpath(rootDirAbs).catch(() => null);
  if (rootReal) resolveUnderRepo(repoRoot, rootReal, 'agentEvidenceRootRealPath');

  const walk = async (dirAbs) => {
    let ents;
    try {
      ents = await fsp.readdir(dirAbs, { withFileTypes: true });
    } catch {
      return;
    }

    // If this directory contains run-metadata.json, treat it as a bundle root and do not descend.
    if (ents.some((e) => e.isFile() && e.name === 'run-metadata.json')) {
      out.push(dirAbs);
      return;
    }

    for (const ent of ents) {
      if (!ent.isDirectory()) continue;
      const childAbs = path.join(dirAbs, ent.name);

      let st;
      try {
        st = await fsp.lstat(childAbs);
      } catch {
        continue;
      }
      if (st.isSymbolicLink()) continue;

      const real = await fsp.realpath(childAbs).catch(() => null);
      if (real) resolveUnderRepo(repoRoot, real, 'agentEvidenceWalkRealPath');

      await walk(childAbs);
    }
  };

  await walk(rootDirAbs);
  return out;
}

export async function pruneAgentEvidence({ repoRoot, keepLastN }) {
  const keep = Number(keepLastN);
  if (!Number.isFinite(keep) || keep <= 0) throw new Error(`Invalid keepLastN: ${keepLastN}`);

  const rootRel = resolveUnderRepo(repoRoot, path.join('tmp', 'agent-evidence'), 'agentEvidenceRoot');
  const rootRelPosix = toPosix(rootRel);
  if (!(rootRelPosix === 'tmp/agent-evidence' || rootRelPosix.startsWith('tmp/agent-evidence/'))) {
    throw new Error(`agent evidence root must be tmp/agent-evidence. got=${rootRelPosix}`);
  }
  const rootAbs = path.resolve(repoRoot, rootRel);

  const bundleDirs = await listBundleRoots({ rootDirAbs: rootAbs, repoRoot });

  const items = [];
  for (const dirAbs of bundleDirs) {
    const runMetaAbs = path.join(dirAbs, 'run-metadata.json');
    try {
      const st = await fsp.lstat(runMetaAbs);
      if (!st.isFile()) continue;
      if (st.isSymbolicLink()) continue;
      items.push({ dirAbs, mtimeMs: st.mtimeMs });
    } catch {
      // ignore
    }
  }

  items.sort((a, b) => b.mtimeMs - a.mtimeMs);

  const toDelete = items.slice(keep);
  const warnings = [];
  let deleted = 0;

  for (const it of toDelete) {
    const dirAbs = it.dirAbs;
    try {
      const relPosix = toPosix(path.relative(repoRoot, dirAbs));
      if (!relPosix || relPosix === '.' || relPosix === 'tmp/agent-evidence') continue;
      if (!(relPosix.startsWith('tmp/agent-evidence/'))) continue;

      const st = await fsp.lstat(dirAbs);
      if (st.isSymbolicLink()) continue;

      const real = await fsp.realpath(dirAbs).catch(() => null);
      if (real) resolveUnderRepo(repoRoot, real, 'agentEvidenceDeleteRealPath');

      await fsp.rm(dirAbs, { recursive: true, force: true });
      deleted += 1;
    } catch (err) {
      warnings.push(String(err?.message || err));
    }
  }

  return { keepLastN: keep, found: items.length, deleted, ...(warnings.length > 0 ? { warnings } : {}) };
}

