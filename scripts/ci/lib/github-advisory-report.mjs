import { createHash } from 'node:crypto';
import semver from 'semver';

export const GITHUB_ADVISORY_REPORT_SCHEMA = 'github-advisory-report.v2';
export const GITHUB_ADVISORY_PROVIDER = 'github-global-security-advisories';
export const GITHUB_API_VERSION = '2026-03-10';
export const REQUIRED_ADVISORY_TARGETS = Object.freeze([
  Object.freeze({ targetId: 'root', lockfile: 'package-lock.json' }),
  Object.freeze({ targetId: 'ui-web', lockfile: 'modules/ui-web/package-lock.json' }),
  Object.freeze({ targetId: 'shell', lockfile: 'modules/shell/package-lock.json' }),
  Object.freeze({ targetId: 'runtime-client', lockfile: 'packages/runtime-client/package-lock.json' }),
  Object.freeze({ targetId: 'wire-contract', lockfile: 'scripts/wire-contract/package-lock.json' }),
]);

const SEVERITIES = new Set(['unknown', 'low', 'moderate', 'high', 'critical']);

export function packageSpecsFromLockfileText(text) {
  const lock = JSON.parse(text);
  if (![2, 3].includes(lock?.lockfileVersion) || !lock?.packages || typeof lock.packages !== 'object') {
    throw new Error('lockfile must use package-lock schema v2/v3 and contain a packages object');
  }
  const specs = new Set();
  for (const [packagePath, row] of Object.entries(lock.packages)) {
    if (!packagePath || !row || typeof row !== 'object') continue;
    const version = String(row.version ?? '').trim();
    if (!version) continue;
    const marker = 'node_modules/';
    const markerIndex = packagePath.lastIndexOf(marker);
    if (markerIndex < 0) continue;
    const name = packagePath.slice(markerIndex + marker.length).trim();
    if (!name || name.includes('/node_modules/') || name.includes(',') || name.includes('@', 1)) {
      throw new Error(`cannot derive an npm package name from lockfile path '${packagePath}'`);
    }
    specs.add(`${name}@${version}`);
  }
  if (specs.size === 0) throw new Error('lockfile contains no versioned npm packages');
  return [...specs].sort();
}

export function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

export function buildAffectsBatches(specs, { maxEncodedQueryLength = 6500, maxSpecs = 200 } = {}) {
  if (!Array.isArray(specs) || specs.length === 0) throw new Error('at least one package spec is required');
  const batches = [];
  let current = [];
  for (const spec of specs) {
    const candidate = [...current, spec];
    const queryLength = new URLSearchParams({
      ecosystem: 'npm', affects: candidate.join(','), per_page: '100', page: '1',
    }).toString().length;
    // A response identifies packages, not which queried version matched. Keep
    // each package in at most one spec per request so GitHub decides the version.
    const name = spec.slice(0, spec.lastIndexOf('@'));
    const repeatsPackage = current.some(entry => entry.slice(0, entry.lastIndexOf('@')) === name);
    if (current.length > 0 && (repeatsPackage || candidate.length > maxSpecs || queryLength > maxEncodedQueryLength)) {
      batches.push(current);
      current = [spec];
    } else {
      current = candidate;
    }
    const singleLength = new URLSearchParams({ ecosystem: 'npm', affects: current.join(',') }).toString().length;
    if (singleLength > maxEncodedQueryLength) throw new Error(`package spec exceeds advisory query bound: ${spec}`);
  }
  if (current.length > 0) batches.push(current);
  return batches;
}

function retryDelayMs(response, attempt) {
  const retryAfter = Number(response?.headers?.get?.('retry-after'));
  if (Number.isFinite(retryAfter) && retryAfter >= 0) return Math.min(retryAfter * 1000, 5000);
  return Math.min(250 * (2 ** attempt), 2000);
}

async function fetchAdvisoryPage(url, {
  fetchImpl, headers, timeoutMs, retries, sleep, timeoutSignalFactory,
}) {
  let lastError;
  for (let attempt = 0; attempt <= retries; attempt += 1) {
    try {
      const response = await fetchImpl(url, {
        method: 'GET', headers, signal: timeoutSignalFactory(timeoutMs),
      });
      if (!response?.ok) {
        const error = new Error(`GitHub advisory request returned HTTP ${response?.status ?? 'unknown'}`);
        if (response?.status !== 429 && Number(response?.status) < 500) {
          error.retryable = false;
          throw error;
        }
        lastError = error;
        if (attempt < retries) await sleep(retryDelayMs(response, attempt));
        continue;
      }
      const body = await response.json();
      if (!Array.isArray(body)) throw new Error('GitHub advisory response must be a JSON array');
      return body;
    } catch (error) {
      if (error?.retryable === false) throw error;
      lastError = error;
      if (attempt < retries) await sleep(retryDelayMs(null, attempt));
    }
  }
  throw lastError ?? new Error('GitHub advisory request failed');
}

function normalizeAdvisory(row) {
  const ghsaId = String(row?.ghsa_id ?? '').trim();
  const providerSeverity = String(row?.severity ?? '').trim().toLowerCase();
  const severity = providerSeverity === 'medium' ? 'moderate' : providerSeverity;
  const htmlUrl = String(row?.html_url ?? '').trim();
  if (!/^GHSA-[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4}$/i.test(ghsaId)) {
    throw new Error(`GitHub advisory response contains invalid ghsa_id '${ghsaId}'`);
  }
  if (!SEVERITIES.has(severity)) throw new Error(`GitHub advisory ${ghsaId} has invalid severity '${severity}'`);
  if (!htmlUrl.startsWith('https://github.com/advisories/')) {
    throw new Error(`GitHub advisory ${ghsaId} has an invalid html_url`);
  }
  return { ghsa_id: ghsaId.toUpperCase(), severity, html_url: htmlUrl };
}

export async function queryGitHubAdvisories(specs, {
  fetchImpl = globalThis.fetch,
  token = '',
  apiBase = 'https://api.github.com',
  timeoutMs = 20_000,
  retries = 2,
  maxPages = 10,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  timeoutSignalFactory = (ms) => AbortSignal.timeout(ms),
} = {}) {
  if (typeof fetchImpl !== 'function') throw new Error('fetch implementation is required');
  const headers = {
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': GITHUB_API_VERSION,
    'User-Agent': 'justsearch-public-claims',
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  const byId = new Map();
  const batches = buildAffectsBatches(specs);
  for (const batch of batches) {
    for (let page = 1; page <= maxPages; page += 1) {
      const url = new URL('/advisories', apiBase);
      url.search = new URLSearchParams({
        ecosystem: 'npm', affects: batch.join(','), per_page: '100', page: String(page),
      }).toString();
      const rows = await fetchAdvisoryPage(url, {
        fetchImpl, headers, timeoutMs, retries, sleep, timeoutSignalFactory,
      });
      for (const row of rows) {
        const advisory = normalizeAdvisory(row);
        const prior = byId.get(advisory.ghsa_id);
        if (prior && (prior.severity !== advisory.severity || prior.html_url !== advisory.html_url)) {
          throw new Error(`GitHub advisory ${advisory.ghsa_id} changed within one report`);
        }
        if (!Array.isArray(row.vulnerabilities) || !row.vulnerabilities.length) {
          throw new Error(`GitHub advisory ${advisory.ghsa_id} has no package attribution`);
        }
        const affected = batch.flatMap((spec) => {
          const at = spec.lastIndexOf('@');
          const name = spec.slice(0, at);
          const version = spec.slice(at + 1);
          const entries = row.vulnerabilities.filter(v => v?.package?.ecosystem === 'npm' && v.package.name === name);
          const matches = entries.some(entry => {
            // GitHub separates conjunctive comparators with commas; node-semver
            // uses whitespace for the same intersection.
            const range = typeof entry.vulnerable_version_range === 'string'
              ? entry.vulnerable_version_range.replaceAll(',', ' ') : null;
            if (!semver.valid(version)) {
              throw new Error(`GitHub advisory ${advisory.ghsa_id} has unreadable package version for ${spec}`);
            }
            if (typeof range !== 'string' || !range.trim() || !semver.validRange(range)) {
              throw new Error(`GitHub advisory ${advisory.ghsa_id} has unreadable vulnerable range for ${spec}`);
            }
            // node-semver excludes prereleases by default, even below an upper
            // bound. Advisory ranges must also cover queried prerelease builds.
            return semver.satisfies(version, range, { includePrerelease: true });
          });
          return matches ? [{ package: name, version }] : [];
        });
        if (!affected.length) throw new Error(`GitHub advisory ${advisory.ghsa_id} matches no queried package`);
        const unique = new Map([...(prior?.affected ?? []), ...affected].map((entry) => [`${entry.package}@${entry.version}`, entry]));
        byId.set(advisory.ghsa_id, { ...advisory, affected: [...unique.values()].sort((a, b) => `${a.package}@${a.version}`.localeCompare(`${b.package}@${b.version}`)) });
      }
      if (rows.length < 100) break;
      if (page === maxPages) throw new Error(`GitHub advisory pagination exceeded ${maxPages} pages`);
    }
  }
  return [...byId.values()].sort((a, b) => a.ghsa_id.localeCompare(b.ghsa_id));
}

export function unavailableAdvisoryTargetReason(row) {
  if (!row || typeof row !== 'object') return 'target row is missing';
  if (row.available !== true) return String(row.error ?? 'provider evidence is unavailable');
  if (!/^[a-f0-9]{64}$/.test(String(row.lockfile_sha256 ?? ''))) return 'lockfile digest is missing or invalid';
  if (!Number.isInteger(row.package_versions) || row.package_versions <= 0) return 'package version count is missing or invalid';
  if (!Array.isArray(row.advisories)) return 'advisory list is missing';
  try {
    const ids = new Set();
    for (const advisory of row.advisories) {
      const normalized = normalizeAdvisory(advisory);
      if (ids.has(normalized.ghsa_id)) return `duplicate advisory ${normalized.ghsa_id}`;
      ids.add(normalized.ghsa_id);
      if (!Array.isArray(advisory.affected) || !advisory.affected.length) return `${normalized.ghsa_id} has no package/version attribution`;
      const specs = new Set();
      for (const entry of advisory.affected) {
        if (typeof entry?.package !== 'string' || !entry.package || typeof entry.version !== 'string' || !entry.version) return `${normalized.ghsa_id} has invalid package/version attribution`;
        const spec = `${entry.package}@${entry.version}`;
        if (specs.has(spec)) return `${normalized.ghsa_id} has duplicate package/version attribution`;
        specs.add(spec);
      }
    }
  } catch (error) {
    return error instanceof Error ? error.message : String(error);
  }
  return null;
}
