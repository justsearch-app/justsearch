/**
 * CI guard (tempdoc 683): outward-facing benchmark NUMBERS must be citable to the canonical
 * release. Sibling of check-readme-benchmark-numbers.mjs (which pins the README "## Benchmarks"
 * table); this one sweeps ALL outward files (root README.md + docs/business/**\/*.md) for loose
 * benchmark-shaped numbers.
 *
 * Rule (deliberately TIGHT — this runs in the required public-claims lane, so false positives are
 * worse than misses): a scanned line trips only when it contains
 *   (a) an nDCG-attached decimal (e.g. "nDCG@10: 0.755", "nDCG = 0.62"), or
 *   (b) a 2-3-digit percentage on the same line as a benchmark corpus token
 *       (SciFact | Enron | MIRACL | CLERC | CourtListener | BEIR).
 * A tripped number passes when EITHER
 *   - the same file contains the current `release_id` string from scripts/jseval/release.v1.json
 *     (the number is citable — the release is named), OR
 *   - the number equals (within its own rounding) a value present in the release's
 *     measured/ablations metrics or external_baselines.
 * Everything else passes. Code fences are skipped.
 *
 * Usage: node scripts/ci/check-outward-number-citations.mjs
 * Exit 0 = all outward numbers citable (or skipped: no release); 1 = an uncited number.
 * Unit fixtures: scripts/ci/check-outward-number-citations.test.mjs (sibling convention).
 */

import fs from "node:fs";
import path from "node:path";
import process from "node:process";

function repoRoot() {
  const markers = ["settings.gradle.kts", ".git"];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    for (const m of markers) if (fs.existsSync(path.join(dir, m))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

const CORPUS_TOKEN = /(SciFact|Enron|MIRACL|CLERC|CourtListener|BEIR)/i;
const NDCG_DECIMAL = /nDCG@?\d*\s*[:=]?\s*(0\.\d{2,})/gi;
const PERCENTAGE = /(?<![\d.])(\d{2,3}(?:\.\d+)?)\s*%/g;

/**
 * Every numeric value the release vouches for: measured + ablations metrics/run_metrics + external baselines.
 * A superseded release vouches for nothing: it is rejected before any number is collected.
 */
export function collectReleaseNumbers(release) {
  if (release?.status === "superseded") {
    throw new Error(
      `release ${release.release_id ?? "<unknown>"} is superseded and cannot make outward numbers citable`
    );
  }
  const out = [];
  const pushMetrics = (entry) => {
    for (const key of ["metrics", "run_metrics"]) {
      for (const v of Object.values((entry && entry[key]) || {})) {
        if (typeof v === "number") out.push(v);
      }
    }
  };
  for (const entry of Object.values(release.measured || {})) pushMetrics(entry);
  for (const list of Object.values(release.ablations || {})) {
    for (const entry of list || []) pushMetrics(entry);
  }
  for (const list of Object.values(release.external_baselines || {})) {
    for (const b of list || []) {
      if (b && typeof b.value === "number") out.push(b.value);
    }
  }
  return out;
}

/** Does `raw` (the literal digits shown) equal some release value within its own rounding? */
export function matchesReleaseValue(raw, releaseNumbers, { percent = false } = {}) {
  const shown = Number(raw);
  if (!Number.isFinite(shown)) return false;
  const dp = raw.includes(".") ? raw.length - raw.indexOf(".") - 1 : 0;
  return releaseNumbers.some((r) => {
    const candidate = percent ? r * 100 : r;
    return Number(candidate.toFixed(dp)) === shown;
  });
}

/**
 * Scan one file's text; returns [{line, column, token, kind}] for benchmark-shaped numbers that
 * are neither covered by a release_id citation in the file nor equal to a release value.
 * Pure (unit-tested); code fences are skipped.
 */
export function findUncitedNumbers(text, { releaseId, releaseNumbers }) {
  if (releaseId && text.includes(releaseId)) return []; // the file cites the release — all numbers covered
  const failures = [];
  let inFence = false;
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence;
      continue;
    }
    if (inFence) continue;

    for (const m of line.matchAll(NDCG_DECIMAL)) {
      if (!matchesReleaseValue(m[1], releaseNumbers)) {
        failures.push({ line: i + 1, column: m.index + 1, token: m[0].trim(), kind: "nDCG decimal" });
      }
    }
    if (CORPUS_TOKEN.test(line)) {
      for (const m of line.matchAll(PERCENTAGE)) {
        if (!matchesReleaseValue(m[1], releaseNumbers, { percent: true })) {
          failures.push({
            line: i + 1,
            column: m.index + 1,
            token: `${m[1]}% (line names a benchmark corpus)`,
            kind: "corpus-adjacent percentage",
          });
        }
      }
    }
  }
  return failures;
}

export function listOutwardFiles(root) {
  const files = [];
  for (const name of ["README.md", "RESEARCH.md"]) {
    const outward = path.join(root, name);
    if (fs.existsSync(outward)) files.push(outward);
  }
  const bizRoot = path.join(root, "docs", "business");
  const walk = (dir) => {
    if (!fs.existsSync(dir)) return;
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) walk(full);
      else if (ent.isFile() && ent.name.endsWith(".md")) files.push(full);
    }
  };
  walk(bizRoot);
  return files;
}

function main() {
  const root = repoRoot();
  const releasePath = path.join(root, "scripts", "jseval", "release.v1.json");
  if (!fs.existsSync(releasePath)) {
    console.log("check-outward-number-citations: skipped (no release.v1.json).");
    return;
  }
  const release = JSON.parse(fs.readFileSync(releasePath, "utf8"));
  let releaseNumbers;
  try {
    releaseNumbers = collectReleaseNumbers(release);
  } catch (error) {
    console.error(`check-outward-number-citations: FAIL - ${error.message}`);
    process.exitCode = 1;
    return;
  }
  const releaseId = typeof release.release_id === "string" ? release.release_id : null;

  const failures = [];
  let scanned = 0;
  for (const abs of listOutwardFiles(root)) {
    scanned += 1;
    const rel = path.relative(root, abs).replaceAll("\\", "/");
    for (const f of findUncitedNumbers(fs.readFileSync(abs, "utf8"), { releaseId, releaseNumbers })) {
      failures.push(`${rel}:${f.line}: ${f.kind} "${f.token}" is not citable`);
    }
  }

  if (failures.length) {
    console.error("check-outward-number-citations: FAIL");
    for (const f of failures) console.error("  - " + f);
    console.error(
      `  Fix: cite the release id ("${releaseId ?? "<release_id>"}") in the same file, ` +
        "make the number match a value in scripts/jseval/release.v1.json, or remove the number."
    );
    process.exitCode = 1;
  } else {
    console.log(
      `check-outward-number-citations: OK (${scanned} outward files; every benchmark-shaped number is citable to ${releaseId ?? "the release"})`
    );
  }
}

// Import-safe for the sibling .test.mjs (same pattern as check-store-recoverability.mjs).
if (process.argv[1] && process.argv[1].replace(/\\/g, "/").endsWith("check-outward-number-citations.mjs")) {
  main();
}
