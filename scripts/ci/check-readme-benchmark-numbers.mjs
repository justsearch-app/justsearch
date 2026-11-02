/**
 * CI guard (tempdoc 633 #2 / Gap-2): the README's editorial benchmark table must not drift from the
 * canonical release. The README table is intentionally *editorial* — a curated corpus subset with prose
 * notes — so it is NOT a full projection (that would flatten the messaging; cf. 632 P3 "consistency-check,
 * not generation"). Instead this asserts every nDCG number the README *shows* matches
 * scripts/jseval/release.v1.json (within the README's rounding). The prose stays hand-authored; the numbers
 * cannot silently go stale.
 *
 * Usage: node scripts/ci/check-readme-benchmark-numbers.mjs
 * Exit 0 = consistent (or skipped); non-zero = a shown row disagrees with, or has no evidence in, the release;
 * a row no label recognises shows a number; a row matches two labels; or a label is reused by a row that
 * is not a declared, checked ablation. Only nDCG@10 values are compared: note cells and prose are not.
 * Unit fixtures: scripts/ci/check-readme-benchmark-numbers.test.mjs (sibling convention).
 *
 * Skip-if-absent (the 632 sidecar local-vs-CI duality): no release, or no README with a "## Benchmarks"
 * section → skipped (exit 0). README path auto-resolves between the root README.md and the staged
 * positioning draft, so this guards the draft now and the promoted root README at the 634 cutover with no
 * re-point. Mirrors the repo-root/exit shape of check-release-baseline-sync.mjs; Node stdlib only.
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

function nd(entry) {
  const v = entry && entry.metrics && entry.metrics["nDCG@10"];
  return typeof v === "number" ? v : null;
}

// README's rounding varies (3dp for most, 2dp for CourtListener) — accept either form.
function roundedForms(v) {
  return [v.toFixed(3), v.toFixed(2)];
}

// The row shows `v` as a whole number in one of its rounded forms ("0.69" must not match inside "0.690").
function showsValue(row, v) {
  return roundedForms(v).some((f) => new RegExp(`(?<![\\d.])${f.replace(".", "\\.")}(?![\\d])`).test(row));
}

// Find the first candidate README that has a "## Benchmarks" section; return {rel, lines} or null.
function findReadmeWithBenchmarks(root) {
  const candidates = [
    "README.md",
    path.join("docs", "business", "positioning", "readme-draft.md"),
  ];
  for (const rel of candidates) {
    const abs = path.join(root, rel);
    if (!fs.existsSync(abs)) continue;
    const text = fs.readFileSync(abs, "utf8");
    if (/^##\s+Benchmarks\b/m.test(text)) {
      return { rel, lines: text.split(/\r?\n/) };
    }
  }
  return null;
}

// Return the table-row lines inside the "## Benchmarks" section (between that heading and the next "## ").
export function benchmarkTableRows(lines) {
  const rows = [];
  let inSection = false;
  for (const line of lines) {
    if (/^##\s+Benchmarks\b/.test(line)) {
      inSection = true;
      continue;
    }
    if (inSection && /^##\s+/.test(line)) break; // next section
    if (inSection && line.trim().startsWith("|")) rows.push(line);
  }
  return rows;
}

// Editorial label token -> the release dataset that row must resolve to. Values are DERIVED from the
// release (not hardcoded), so the check stays correct across releases. A corpus the README omits is
// simply skipped; a corpus it SHOWS must resolve to measured or explicitly withheld evidence.
const EXPECTATIONS = [
  { token: "SciFact", dataset: "beir/scifact" },
  { token: "Enron", dataset: "mixed/enron-qa" },
  { token: "MIRACL-de", dataset: "mixed/miracl-de-2k" },
  { token: "MIRACL-fr", dataset: "mixed/miracl-fr-2k" },
  { token: "CourtListener", dataset: "mixed/courtlistener-200", alsoFullAblation: true },
  { token: "CLERC", dataset: "mixed/legal-clerc-200" },
];

// A cell that shows a score in any common form: ".578", "0.578", "57.8", "0,578", "81%", "81 %".
const SCORE_FORM = /\.\d|\d,\d|\d\s*%/;

function rowMatches(row) {
  const lower = row.toLowerCase();
  return EXPECTATIONS.filter((exp) => lower.includes(exp.token.toLowerCase()));
}

// An ablation row declares itself with the word "ablation" and names exactly one mode the release
// records as an ablation for that corpus; its number is then checked against that ablation.
function declaredAblation(row, entries) {
  if (!/\bablation\b/i.test(row)) return null;
  const modes = (entries || []).filter((a) => new RegExp(`\\b${a.config_mode}\\b`, "i").test(row));
  return { modes };
}

/** Pure (unit-tested): compare the README benchmark rows with the release. Returns {failures, checked}. */
export function checkBenchmarkRows(rows, release) {
  if (release.status === "superseded") throw new Error("superseded release cannot supply current README claims");
  const measured = release.measured || {};
  const ablations = release.ablations || {};
  const withheld = release.withheld || {};
  const failures = [];
  let checked = 0;
  const defaultRowSeen = new Set();
  // Every row is checked. Rows are matched by label, so a row no label recognises (a renamed or added
  // corpus) would escape the check: any such row that shows a number fails. A row matching two labels is
  // ambiguous and fails. Per label, one row is the measured-mode row; any further row must be a declared
  // ablation whose number is itself checked.
  for (const row of rows) {
    const matches = rowMatches(row);
    if (matches.length === 0) {
      if (SCORE_FORM.test(row)) {
        failures.push(`unrecognised Benchmarks row shows a number no release label checks — row: ${row.trim()}`);
      }
      continue;
    }
    if (matches.length > 1) {
      failures.push(`row matches more than one corpus label (${matches.map((m) => m.token).join(", ")}) — row: ${row.trim()}`);
      continue;
    }
    const exp = matches[0];
    const ablation = declaredAblation(row, ablations[exp.dataset]);
    if (ablation) {
      if (ablation.modes.length !== 1) {
        failures.push(
          `${exp.token}: ablation row must name exactly one ablation mode the release records for ${exp.dataset} — row: ${row.trim()}`
        );
        continue;
      }
      const v = nd(ablation.modes[0]);
      checked += 1;
      if (v == null || !showsValue(row, v)) {
        failures.push(
          `${exp.token}: ${ablation.modes[0].config_mode} ablation row does not show the release value ${v == null ? "(none)" : roundedForms(v).join(" / ")} — row: ${row.trim()}`
        );
      }
      continue;
    }
    if (defaultRowSeen.has(exp.token)) {
      failures.push(`${exp.token}: a second row reuses this corpus label without declaring a checked ablation — row: ${row.trim()}`);
      continue;
    }
    defaultRowSeen.add(exp.token);
    if (withheld[exp.dataset]) {
      checked += 1;
      if (!/withheld/i.test(row) || SCORE_FORM.test(row)) {
        failures.push(`${exp.token}: default-mode row must state WITHHELD without a quality score — row: ${row.trim()}`);
      }
      continue;
    }
    const value = nd(measured[exp.dataset]);
    if (value == null) {
      failures.push(
        `${exp.token}: README shows a row but the release has neither a measured nDCG@10 nor a withheld entry for ${exp.dataset} — row: ${row.trim()}`
      );
      continue;
    }
    const full = exp.alsoFullAblation ? nd(ablations[exp.dataset] && ablations[exp.dataset][0]) : null;
    for (const v of [full, value]) {
      if (v == null) continue;
      checked += 1;
      const forms = roundedForms(v);
      if (!showsValue(row, v)) {
        failures.push(
          `${exp.token}: README row does not show the release value ${forms.join(" / ")} — row: ${row.trim()}`
        );
      }
    }
  }
  return { failures, checked };
}

function main() {
  const root = repoRoot();
  const releasePath = path.join(root, "scripts", "jseval", "release.v1.json");
  if (!fs.existsSync(releasePath)) {
    console.log("check-readme-benchmark-numbers: skipped (no release.v1.json).");
    return;
  }
  const release = JSON.parse(fs.readFileSync(releasePath, "utf8"));
  if (release.status === "superseded") throw new Error("superseded release cannot supply current README claims");

  const found = findReadmeWithBenchmarks(root);
  if (!found) {
    console.log("check-readme-benchmark-numbers: skipped (no README with a '## Benchmarks' section).");
    return;
  }
  const { failures, checked } = checkBenchmarkRows(benchmarkTableRows(found.lines), release);

  if (failures.length) {
    console.error(`check-readme-benchmark-numbers: FAIL (${found.rel})`);
    for (const f of failures) console.error("  - " + f);
    console.error("  Fix the README number to match scripts/jseval/release.v1.json (the canonical release).");
    process.exitCode = 1;
  } else {
    console.log(`check-readme-benchmark-numbers: OK (${found.rel}; ${checked} shown entries match release.v1.json)`);
  }
}

// Import-safe for the sibling .test.mjs (same pattern as check-outward-number-citations.mjs).
if (process.argv[1] && process.argv[1].replace(/\\/g, "/").endsWith("check-readme-benchmark-numbers.mjs")) {
  main();
}
