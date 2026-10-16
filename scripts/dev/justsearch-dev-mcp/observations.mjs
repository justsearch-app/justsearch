import fsp from 'node:fs/promises';
import http from 'node:http';

import { readJsonFileNoSymlinks } from './files.mjs';

export const FILE_OBSERVATION = Object.freeze({
  PRESENT: 'PRESENT',
  ABSENT: 'ABSENT',
  INVALID: 'INVALID',
  UNREADABLE: 'UNREADABLE',
});

export const PROBE_OBSERVATION = Object.freeze({
  REACHABLE: 'REACHABLE',
  REFUSED: 'REFUSED',
  TIMED_OUT: 'TIMED_OUT',
  ERROR: 'ERROR',
});

const INVALID_FILE_CODES = new Set([
  'DEV_MCP_FILE_SYMLINK',
  'DEV_MCP_FILE_NOT_FILE',
  'DEV_MCP_FILE_TOO_LARGE',
  'ENOTDIR',
  'EISDIR',
]);

function boundedMessage(error, maxChars = 800) {
  const message = String(error?.message || error || 'unknown error').replace(/\s+/g, ' ').trim();
  return message.length <= maxChars ? message : `${message.slice(0, maxChars)}…`;
}

function errorDetail(error) {
  return {
    code: typeof error?.code === 'string' ? error.code : 'UNKNOWN',
    message: boundedMessage(error),
  };
}

export function classifyOptionalFileError(error) {
  if (error?.code === 'ENOENT') return { state: FILE_OBSERVATION.ABSENT };
  if (error instanceof SyntaxError || INVALID_FILE_CODES.has(error?.code)) {
    return { state: FILE_OBSERVATION.INVALID, error: errorDetail(error) };
  }
  return { state: FILE_OBSERVATION.UNREADABLE, error: errorDetail(error) };
}

export async function observeOptionalJsonFile({
  repoRoot,
  relPosix,
  maxBytes = 2_000_000,
  read = readJsonFileNoSymlinks,
} = {}) {
  try {
    const value = await read({ repoRoot, relPosix, maxBytes });
    if (value == null || typeof value !== 'object' || Array.isArray(value)) {
      return {
        state: FILE_OBSERVATION.INVALID,
        error: { code: 'DEV_MCP_FILE_INVALID_JSON_SHAPE', message: `${relPosix} must contain a JSON object` },
      };
    }
    return { state: FILE_OBSERVATION.PRESENT, value };
  } catch (error) {
    return classifyOptionalFileError(error);
  }
}

export async function observePath(targetPath, { expected = 'file', lstat = fsp.lstat } = {}) {
  try {
    const stat = await lstat(targetPath);
    if (stat.isSymbolicLink()) {
      return {
        state: FILE_OBSERVATION.INVALID,
        error: { code: 'DEV_MCP_PATH_SYMLINK', message: `${targetPath} must not be a symlink` },
      };
    }
    const expectedMatches = expected === 'directory' ? stat.isDirectory() : stat.isFile();
    if (!expectedMatches) {
      return {
        state: FILE_OBSERVATION.INVALID,
        error: { code: 'DEV_MCP_PATH_WRONG_TYPE', message: `${targetPath} is not a ${expected}` },
      };
    }
    return { state: FILE_OBSERVATION.PRESENT };
  } catch (error) {
    return classifyOptionalFileError(error);
  }
}

export async function observeDirectoryEntries(targetPath, { readdir = fsp.readdir } = {}) {
  try {
    const entries = await readdir(targetPath);
    return { state: FILE_OBSERVATION.PRESENT, value: entries };
  } catch (error) {
    return classifyOptionalFileError(error);
  }
}

function probeError(error) {
  return { code: typeof error?.code === 'string' ? error.code : 'UNKNOWN', message: boundedMessage(error) };
}

export function probeLoopbackHttpStatus(
  urlStr,
  { timeoutMs = 2_000, request = http.request } = {},
) {
  return new Promise((resolve) => {
    let url;
    try {
      url = new URL(urlStr);
      const host = String(url.hostname || '').toLowerCase();
      if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '::1', '[::1]'].includes(host)) {
        throw new Error('probe URL must be loopback http');
      }
    } catch (error) {
      resolve({ state: PROBE_OBSERVATION.ERROR, error: probeError(error) });
      return;
    }

    let settled = false;
    let timedOut = false;
    const finish = (result) => {
      if (settled) return;
      settled = true;
      resolve(result);
    };
    let req;
    try {
      const requestHostname = url.hostname.startsWith('[') && url.hostname.endsWith(']')
        ? url.hostname.slice(1, -1)
        : url.hostname;
      req = request(
        {
          hostname: requestHostname,
          port: url.port ? Number(url.port) : 80,
          path: url.pathname + url.search,
          method: 'GET',
          timeout: timeoutMs,
        },
        (res) => {
          res.resume();
          const statusCode = typeof res.statusCode === 'number' ? res.statusCode : null;
          finish({ state: PROBE_OBSERVATION.REACHABLE, statusCode });
        },
      );
    } catch (error) {
      finish({ state: PROBE_OBSERVATION.ERROR, error: probeError(error) });
      return;
    }
    req.on('timeout', () => {
      timedOut = true;
      const error = new Error('timeout');
      error.code = 'ETIMEDOUT';
      req.destroy(error);
    });
    req.on('error', (error) => {
      if (timedOut || error?.code === 'ETIMEDOUT') {
        finish({ state: PROBE_OBSERVATION.TIMED_OUT, error: probeError(error) });
      } else if (error?.code === 'ECONNREFUSED') {
        finish({ state: PROBE_OBSERVATION.REFUSED });
      } else {
        finish({ state: PROBE_OBSERVATION.ERROR, error: probeError(error) });
      }
    });
    req.end();
  });
}

/**
 * A port probe proves listener presence, not process identity. Until a repository-owned process
 * record identifies the listener, a reachable inference-port service is not a proven orphan.
 */
export function classifyInferenceOrphan({
  inferenceObservation,
} = {}) {
  if (!inferenceObservation) return undefined;
  if (inferenceObservation.state === PROBE_OBSERVATION.REFUSED) return false;
  return null;
}

/**
 * Probe each recorded llama-server (`recordedLlamaServers`) on its own recorded port. llama-server
 * has no fixed port (ADR-0051 decision 2), so a fixed-port probe would miss every orphan. The
 * first answer wins and names the port and recorded pid; otherwise any failed probe makes the
 * result unknown; all refused, or nothing recorded, is REFUSED with the ports looked at.
 */
export async function observeRecordedInference({
  recorded = [],
  probe = probeLoopbackHttpStatus,
  timeoutMs = 2_000,
} = {}) {
  let unknown = null;
  for (const { port, pid } of recorded) {
    const observation = await probe(`http://127.0.0.1:${port}/health`, { timeoutMs });
    if (observation.state === PROBE_OBSERVATION.REACHABLE) return { ...observation, port, pid };
    if (observation.state !== PROBE_OBSERVATION.REFUSED && !unknown) {
      unknown = { ...observation, port, pid };
    }
  }
  return unknown ?? { state: PROBE_OBSERVATION.REFUSED, recordedPorts: recorded.map((r) => r.port) };
}

/** Adapter for the older foreign-run reader: unknown probes throw so it returns null, not []. */
export async function probeStatusCodeOrThrow(urlStr, timeoutMs) {
  const result = await probeLoopbackHttpStatus(urlStr, { timeoutMs });
  if (result.state === PROBE_OBSERVATION.REACHABLE) return result.statusCode;
  if (result.state === PROBE_OBSERVATION.REFUSED) return null;
  const error = new Error(result.error?.message || `probe ${result.state}`);
  error.code = result.error?.code || `DEV_MCP_PROBE_${result.state}`;
  throw error;
}
