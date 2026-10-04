#!/usr/bin/env node
/**
 * Derive the `ReleaseSequence` for a release cut from what is ALREADY PUBLISHED.
 *
 * Why this exists (tempdoc 801 D14.b). `build-installer.yml` used to set
 * `ReleaseSequence = GITHUB_RUN_NUMBER`. GitHub scopes that counter to the workflow
 * *file*: renaming, moving, or delete-and-recreating `build-installer.yml` resets it to
 * 1. `updater.rs` persists `highest_accepted_sequence` and permanently refuses any
 * descriptor below it (`check_release`, no in-client recovery), so one workflow-file
 * rename would brick updates for every client that ever accepted a release. That trap
 * locked in with the first stable tag (v0.2.0, sequence 40, published 2026-08-13).
 *
 * The replacement source is the published release descriptors themselves: the next
 * sequence is `max(sequence over every published release.v1.json) + 1`. That is
 * independent of workflow-file identity, and monotonic by construction against exactly
 * the values clients can have accepted — the same artifacts the updater reads.
 *
 * Fail-closed rules (a guessed sequence is what bricks clients):
 *   - A release WITHOUT a `release.v1.json` asset is skipped with a warning (v0.1.0
 *     predates the descriptor).
 *   - A release WITH the asset whose bytes cannot be fetched or parsed, or whose
 *     `sequence` is not a positive integer, is a HARD ERROR. Never guess past a
 *     descriptor that exists.
 *   - Any GitHub API failure is a HARD ERROR.
 *   - If no published descriptor exists at all, the floor below applies.
 *
 * Usage:
 *   node scripts/ci/derive-release-sequence.mjs [--repo owner/repo] [--exclude-tag vX.Y.Z]
 *
 * Prints the derived sequence (bare integer) on stdout; provenance and warnings on
 * stderr. Non-zero exit means the caller must NOT ship a sequence.
 */

import path from 'node:path';
import process from 'node:process';
import { mkdir, rename, rm, writeFile, lstat } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { checkRetiredDurableStores } from './check-store-recoverability.mjs';

/**
 * Floor for the zero-descriptor edge, and a hard lower bound on every derived value.
 *
 * 41 = v0.2.0's published sequence (40) + 1. v0.2.0 is public with real downloads, so a
 * client may already hold `highest_accepted_sequence = 40`; anything <= 40 would be
 * rejected forever. Raise this only to stay above the highest sequence any PUBLISHED
 * release has ever carried — never lower it.
 */
export const RELEASE_SEQUENCE_FLOOR = 41;

export const DESCRIPTOR_ASSET_NAME = 'release.v1.json';

// Overridable so the test suite can drive the network-failure path offline (against a closed
// loopback port). Production callers never set it.
const API_ROOT = process.env.DERIVE_RELEASE_SEQUENCE_API_ROOT || 'https://api.github.com';

/**
 * Core derivation. Pure apart from the injected `fetchAsset`, so tests run offline.
 *
 * @param {object} args
 * @param {Array<object>} args.releases GitHub release objects (as returned by the list API).
 * @param {(asset: object, release: object) => Promise<string>} args.fetchAsset Returns asset bytes as text.
 * @param {string|null} [args.excludeTag] Tag currently being built; its own release is ignored so a
 *   re-run of an already-published tag re-derives the SAME value instead of drifting upward.
 * @param {number} [args.floor]
 * @returns {Promise<{sequence: number, provenance: string, warnings: string[], observed: Array<{tag: string, sequence: number}>}>}
 */
export async function deriveReleaseSequence({
  releases,
  fetchAsset,
  excludeTag = null,
  floor = RELEASE_SEQUENCE_FLOOR,
  fetchCompatibilityBaseline = null,
}) {
  const warnings = [];
  const observed = [];
  let predecessor = null;
  let ambiguousPredecessor = false;

  for (const release of releases) {
    const tag = release.tag_name ?? '(untagged)';
    if (release.draft) {
      warnings.push(`skipped draft release ${tag} (not published; no client can have accepted it)`);
      continue;
    }
    if (excludeTag && tag === excludeTag) {
      warnings.push(`skipped ${tag}: it is the tag being built (keeps a re-run of a published tag idempotent)`);
      continue;
    }
    const asset = (release.assets ?? []).find((candidate) => candidate?.name === DESCRIPTOR_ASSET_NAME);
    if (!asset) {
      warnings.push(`skipped ${tag}: no ${DESCRIPTOR_ASSET_NAME} asset (predates the release descriptor)`);
      continue;
    }

    let text;
    try {
      text = await fetchAsset(asset, release);
    } catch (error) {
      throw new Error(
        `Failed to download ${DESCRIPTOR_ASSET_NAME} from published release ${tag}: ${error.message}. ` +
          'Refusing to derive a sequence past a descriptor that exists but could not be read.',
      );
    }

    let descriptor;
    try {
      descriptor = JSON.parse(text);
    } catch (error) {
      throw new Error(
        `Published release ${tag} has an unparseable ${DESCRIPTOR_ASSET_NAME}: ${error.message}. ` +
          'Refusing to derive a sequence past a descriptor that exists but could not be read.',
      );
    }

    const sequence = descriptor?.sequence;
    if (!Number.isSafeInteger(sequence) || sequence <= 0 || sequence === Number.MAX_SAFE_INTEGER) {
      throw new Error(
        `Published release ${tag} has ${DESCRIPTOR_ASSET_NAME} with an invalid sequence ` +
          `(${JSON.stringify(sequence)}); expected a positive safe integer with a safe successor.`,
      );
    }
    if (fetchCompatibilityBaseline && (descriptor.schemaVersion !== 1
        || descriptor.channel !== 'stable' || descriptor.target !== 'windows-x86_64'
        || typeof descriptor.version !== 'string' || !descriptor.version.trim()
        || tag !== `v${descriptor.version}`)) {
      throw new Error(`Published predecessor ${tag} descriptor/tag identity mismatch.`);
    }
    observed.push({ tag, sequence });
    if (!predecessor || sequence > predecessor.sequence) {
      predecessor = { release, descriptor, tag, sequence };
      ambiguousPredecessor = false;
    } else if (sequence === predecessor.sequence && tag !== predecessor.tag) {
      ambiguousPredecessor = true;
    }
  }

  if (observed.length === 0) {
    if (fetchCompatibilityBaseline) {
      throw new Error('No published predecessor descriptor; refusing to guess a compatibility baseline.');
    }
    return {
      sequence: floor,
      provenance: `no published ${DESCRIPTOR_ASSET_NAME} found; using checked-in floor ${floor}`,
      warnings,
      observed,
    };
  }

  const highest = observed.reduce((best, entry) => (entry.sequence > best.sequence ? entry : best));
  let compatibilityBaseline;
  if (fetchCompatibilityBaseline) {
    if (ambiguousPredecessor) throw new Error('Highest published sequence has ambiguous predecessor tags.');
    const text = await fetchCompatibilityBaseline(predecessor.release);
    validateCompatibilityBaseline(text, predecessor.descriptor, predecessor.tag);
    compatibilityBaseline = { tag: predecessor.tag, text };
  }
  const next = highest.sequence + 1;
  if (next < floor) {
    return {
      sequence: floor,
      provenance:
        `highest published sequence ${highest.sequence} (${highest.tag}/${DESCRIPTOR_ASSET_NAME}) + 1 = ${next}, ` +
        `raised to checked-in floor ${floor}`,
      warnings,
      observed,
      ...(compatibilityBaseline ? { compatibilityBaseline } : {}),
    };
  }
  return {
    sequence: next,
    provenance: `highest published sequence ${highest.sequence} (${highest.tag}/${DESCRIPTOR_ASSET_NAME}) + 1`,
    warnings,
    observed,
    ...(compatibilityBaseline ? { compatibilityBaseline } : {}),
  };
}

function validateCompatibilityBaseline(text, descriptor, tag) {
  const baseline = JSON.parse(text);
  const retirementFailures = checkRetiredDurableStores(baseline);
  if (retirementFailures.length) throw new Error(`Predecessor ${tag}: ${retirementFailures.join(' ')}`);
  if (!Array.isArray(baseline.durableStores) || baseline.durableStores.length === 0) {
    throw new Error(`Predecessor ${tag} compatibility baseline has no installed durable stores.`);
  }
  const byId = new Map();
  for (const store of baseline.durableStores) {
    if (!store || ['id', 'owner', 'recoverability', 'reconciliation'].some(
      key => typeof store[key] !== 'string' || !store[key].trim())
      || !Number.isSafeInteger(store.currentVersion) || store.currentVersion < 0
      || byId.has(store.id)) {
      throw new Error(`Predecessor ${tag} has invalid or duplicate installed durable stores.`);
    }
    byId.set(store.id, store);
  }
  for (const store of baseline.retiredDurableStores ?? []) byId.set(store.id, store);
  if (!Array.isArray(descriptor.compatibility) || descriptor.compatibility.length === 0) {
    throw new Error(`Predecessor ${tag} descriptor has no compatibility evidence.`);
  }
  const seen = new Set();
  for (const row of descriptor.compatibility) {
    const store = byId.get(row?.ownerId);
    if (!store || seen.has(row.ownerId) || row.owner !== store.owner
        || row.role !== store.recoverability || row.reconciliationStrategy !== store.reconciliation
        || row.formatVersion !== store.currentVersion) {
      throw new Error(`Predecessor ${tag} descriptor/tag compatibility identity or format mismatch.`);
    }
    seen.add(row.ownerId);
  }
}

function authHeaders(token) {
  const headers = {
    'User-Agent': 'justsearch-derive-release-sequence',
    'X-GitHub-Api-Version': '2022-11-28',
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

async function listReleases(repo, token) {
  const releases = [];
  // 100 per page x 10 pages is far past any plausible release count; the cap bounds the loop,
  // and overrunning it throws rather than deriving from a partial list.
  for (let page = 1; page <= 10; page += 1) {
    const url = `${API_ROOT}/repos/${repo}/releases?per_page=100&page=${page}`;
    const response = await fetch(url, {
      headers: { ...authHeaders(token), Accept: 'application/vnd.github+json' },
    });
    if (!response.ok) {
      // Drain before throwing: an unconsumed body leaves an open handle, and exiting on top
      // of one aborts the process with a libuv assertion instead of a clean exit 1.
      await response.body?.cancel();
      throw new Error(`GitHub API ${response.status} ${response.statusText} for ${url}`);
    }
    const batch = await response.json();
    if (!Array.isArray(batch)) throw new Error(`GitHub API returned a non-array release list for ${url}`);
    releases.push(...batch);
    if (batch.length < 100) return releases;
  }
  throw new Error(`Release list for ${repo} exceeded the 10-page cap; refusing to derive from a partial list.`);
}

function makeAssetFetcher(token) {
  return async (asset) => {
    // The asset API URL with octet-stream works for public and token-authenticated reads
    // alike, unlike browser_download_url which is public-only.
    const response = await fetch(asset.url, {
      headers: { ...authHeaders(token), Accept: 'application/octet-stream' },
      redirect: 'follow',
    });
    if (!response.ok) {
      await response.body?.cancel();
      throw new Error(`HTTP ${response.status} ${response.statusText}`);
    }
    return response.text();
  };
}

function makeCompatibilityBaselineFetcher(repo, token) {
  return async (release) => {
    // Explicit ref binds the register to the selected published predecessor, never default main.
    const url = `${API_ROOT}/repos/${repo}/contents/governance/store-recoverability.v1.json?ref=${encodeURIComponent(release.tag_name)}`;
    const response = await fetch(url, {
      headers: { ...authHeaders(token), Accept: 'application/vnd.github.raw+json' },
    });
    if (!response.ok) {
      await response.body?.cancel();
      throw new Error(`Cannot read predecessor compatibility register: HTTP ${response.status}`);
    }
    return response.text();
  };
}

async function writeCompatibilityBaseline(destination, text) {
  const target = path.resolve(destination);
  await mkdir(path.dirname(target), { recursive: true });
  const temporary = `${target}.${randomUUID()}.tmp`;
  try {
    await writeFile(temporary, text, { flag: 'wx' });
    await rename(temporary, target);
  } finally {
    await rm(temporary, { force: true });
  }
}

function parseArgs(argv) {
  const opts = { repo: process.env.GITHUB_REPOSITORY ?? null, excludeTag: null };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--repo' && argv[i + 1]) opts.repo = argv[++i];
    else if (arg === '--exclude-tag' && argv[i + 1]) opts.excludeTag = argv[++i];
    else if (arg === '--compat-baseline-out' && argv[i + 1]) opts.compatibilityBaselineOut = argv[++i];
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  return opts;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log(
      'Usage: node scripts/ci/derive-release-sequence.mjs [--repo owner/repo] [--exclude-tag vX.Y.Z] [--compat-baseline-out FILE]\n\n' +
        'Prints max(sequence over published release.v1.json assets) + 1 on stdout,\n' +
        `never below the checked-in floor ${RELEASE_SEQUENCE_FLOOR}.`,
    );
    return;
  }
  if (opts.compatibilityBaselineOut) {
    // Invalidate only the explicitly named output before acquisition. A failed read must not
    // leave a prior predecessor's bytes available under this run's requested output name.
    const target = path.resolve(opts.compatibilityBaselineOut);
    try {
      const previous = await lstat(target);
      if (!previous.isFile()) throw new Error('Compatibility baseline output must be a regular file path.');
      await rm(target);
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
  }

  if (!opts.repo) throw new Error('No repository: pass --repo owner/repo or set GITHUB_REPOSITORY.');

  const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN || null;
  if (!token) console.error('derive-release-sequence: no GITHUB_TOKEN/GH_TOKEN; reading the API unauthenticated.');

  const releases = await listReleases(opts.repo, token);
  const result = await deriveReleaseSequence({
    releases,
    fetchAsset: makeAssetFetcher(token),
    excludeTag: opts.excludeTag,
    fetchCompatibilityBaseline: opts.compatibilityBaselineOut
      ? makeCompatibilityBaselineFetcher(opts.repo, token) : null,
  });

  if (opts.compatibilityBaselineOut) {
    await writeCompatibilityBaseline(opts.compatibilityBaselineOut, result.compatibilityBaseline.text);
  }

  for (const warning of result.warnings) console.error(`derive-release-sequence: ${warning}`);
  console.error(`derive-release-sequence: ${result.sequence} <- ${result.provenance}`);
  process.stdout.write(`${result.sequence}\n`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main().catch((error) => {
    console.error(`derive-release-sequence: FAILED - ${error.message}`);
    // exitCode, not exit(): calling process.exit() while fetch's keep-alive socket is still
    // open aborts Node with a libuv assertion and a garbage exit code, which is exactly the
    // signal the caller uses to decide whether it may ship a sequence.
    process.exitCode = 1;
  });
}
