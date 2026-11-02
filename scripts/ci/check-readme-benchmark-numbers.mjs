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
 * a row no label recognises shows a number; a row matches two labels; a label is reused by a row that
 * is not a declared, checked ablation; or a withheld corpus's row shows any score other than its declared
 * ablation's value. Rows are parsed into cells: the label cell routes the row (corpus, "ablation", mode),
 * and only the nDCG@10 cell is compared with the release; note cells and prose are not compared.
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
// Every score token in a cell, in the same forms as SCORE_FORM.
const SCORE_TOKENS = /\d*[.,]\d+(?:\s*%)?|\d+\s*%/g;

// Split a Markdown table row into trimmed cells ("| a | b |" -> ["a", "b"]).
export function rowCells(row) {
  return row.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((c) => c.trim());
}

const isSeparator = (cells) => cells.every((c) => /^:?-+:?$/.test(c));

// The cell's score tokens that are not a rounded form of any allowed value.
function otherScores(cell, allowed) {
  const forms = new Set(allowed.filter((v) => v != null).flatMap(roundedForms));
  return (cell.match(SCORE_TOKENS) || []).filter((t) => !forms.has(t));
}

// The corpus labels named in the label cell (the first cell); other cells are prose and never route a row.
function labelMatches(label) {
  const lower = label.toLowerCase();
  return EXPECTATIONS.filter((exp) => lower.includes(exp.token.toLowerCase()));
}

// An ablation row declares itself in its label cell with the word "ablation" and names exactly one mode
// the release records as an ablation for that corpus; its nDCG@10 cell is then checked against that
// ablation. Words in the note cell never make a row an ablation row.
function declaredAblation(label, entries) {
  if (!/\bablation\b/i.test(label)) return null;
  const modes = (entries || []).filter((a) => new RegExp(`\\b${a.config_mode}\\b`, "i").test(label));
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
  let ndcgIndex = null;
  // Every row is checked, cell by cell. The header row locates the nDCG@10 column; a row is routed by its
  // label cell only, and only its nDCG@10 cell is compared with the release. A row no label recognises
  // (a renamed or added corpus) that shows a number fails; a label naming two corpora fails. Per label,
  // one row is the measured-mode row; any further row must be a declared ablation whose number is itself
  // checked. A withheld corpus shows no score in any cell of any of its rows other than the declared
  // ablation's own value.
  for (const row of rows) {
    const cells = rowCells(row);
    if (isSeparator(cells)) continue;
    const header = cells.findIndex((c) => /^ndcg@10$/i.test(c.replace(/\*/g, "")));
    if (header > 0) {
      ndcgIndex = header;
      continue;
    }
    const label = cells[0];
    const matches = labelMatches(label);
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
    if (ndcgIndex == null || ndcgIndex >= cells.length) {
      failures.push(`${exp.token}: row has no nDCG@10 cell (no table header names an nDCG@10 column) — row: ${row.trim()}`);
      continue;
    }
    const scoreCell = cells[ndcgIndex];
    const isWithheld = Boolean(withheld[exp.dataset]);
    const ablation = declaredAblation(label, ablations[exp.dataset]);
    if (ablation) {
      if (ablation.modes.length !== 1) {
        failures.push(
          `${exp.token}: ablation row must name exactly one ablation mode the release records for ${exp.dataset} — row: ${row.trim()}`
        );
        continue;
      }
      const v = nd(ablation.modes[0]);
      checked += 1;
      if (v == null || !showsValue(scoreCell, v) || otherScores(scoreCell, [v]).length) {
        failures.push(
          `${exp.token}: ${ablation.modes[0].config_mode} ablation row's nDCG@10 cell does not show exactly the release value ${v == null ? "(none)" : roundedForms(v).join(" / ")} — row: ${row.trim()}`
        );
        continue;
      }
      if (isWithheld) {
        const others = cells.flatMap((c) => otherScores(c, [v]));
        if (others.length) {
          failures.push(
            `${exp.token}: ${exp.dataset} is withheld; its ablation row shows a score other than the ${ablation.modes[0].config_mode} ablation's own value (${others.join(", ")}) — row: ${row.trim()}`
          );
        }
      }
      continue;
    }
    if (defaultRowSeen.has(exp.token)) {
      failures.push(`${exp.token}: a second row reuses this corpus label without declaring a checked ablation — row: ${row.trim()}`);
      continue;
    }
    defaultRowSeen.add(exp.token);
    if (isWithheld) {
      checked += 1;
      if (!/withheld/i.test(scoreCell) || SCORE_FORM.test(row)) {
        failures.push(`${exp.token}: measured-mode row must state WITHHELD in its nDCG@10 cell and show no quality score — row: ${row.trim()}`);
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
    const allowed = [full, value].filter((v) => v != null);
    for (const v of allowed) {
      checked += 1;
      if (!showsValue(scoreCell, v)) {
        failures.push(
          `${exp.token}: README row's nDCG@10 cell does not show the release value ${roundedForms(v).join(" / ")} — row: ${row.trim()}`
        );
      }
    }
    const others = otherScores(scoreCell, allowed);
    if (others.length) {
      failures.push(`${exp.token}: README row's nDCG@10 cell shows a score the release does not record (${others.join(", ")}) — row: ${row.trim()}`);
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
