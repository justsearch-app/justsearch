/**
 * CI guard (tempdoc 633 #2 / Gap-2): the README's editorial benchmark table must not drift from the
 * canonical release. The README table is intentionally *editorial* — a curated corpus subset with prose
 * notes — so it is NOT a full projection (that would flatten the messaging; cf. 632 P3 "consistency-check,
 * not generation"). Instead this asserts every nDCG number the README *shows* matches
 * scripts/jseval/release.v1.json (within the README's rounding). The prose stays hand-authored; the numbers
 * cannot silently go stale.
 *
 * Usage: node scripts/ci/check-readme-benchmark-numbers.mjs
 * Exit 0 = consistent (or skipped); non-zero = a shown number disagrees with the release.
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
function benchmarkTableRows(lines) {
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

function main() {
  const root = repoRoot();
  const releasePath = path.join(root, "scripts", "jseval", "release.v1.json");
  if (!fs.existsSync(releasePath)) {
    console.log("check-readme-benchmark-numbers: skipped (no release.v1.json).");
    return;
  }
  const release = JSON.parse(fs.readFileSync(releasePath, "utf8"));
  const measured = release.measured || {};
  const ablations = release.ablations || {};

  const found = findReadmeWithBenchmarks(root);
  if (!found) {
    console.log("check-readme-benchmark-numbers: skipped (no README with a '## Benchmarks' section).");
    return;
  }
  const rows = benchmarkTableRows(found.lines);

  // Editorial label token -> the release value(s) that row must show. Values are DERIVED from the release
  // (not hardcoded), so the check stays correct across releases. A corpus the README omits is simply skipped.
  const courtFull = ablations["mixed/courtlistener-200"] && ablations["mixed/courtlistener-200"][0];
  const expectations = [
    { token: "SciFact", values: [nd(measured["beir/scifact"])] },
    { token: "Enron", values: [nd(measured["mixed/enron-qa"])] },
    { token: "MIRACL-de", values: [nd(measured["mixed/miracl-de-2k"])] },
    { token: "MIRACL-fr", values: [nd(measured["mixed/miracl-fr-2k"])] },
    { token: "CourtListener", values: [nd(courtFull), nd(measured["mixed/courtlistener-200"])] },
    { token: "CLERC", values: [nd(measured["mixed/legal-clerc-200"])] },
  ];

  const failures = [];
  let checked = 0;
  for (const exp of expectations) {
    const row = rows.find((r) => r.toLowerCase().includes(exp.token.toLowerCase()));
    if (!row) continue; // README chose not to show this corpus — editorial freedom.
    for (const v of exp.values) {
      if (v == null) continue; // release doesn't carry this number — nothing to assert.
      checked += 1;
      const forms = roundedForms(v);
      if (!forms.some((f) => row.includes(f))) {
        failures.push(
          `${exp.token}: README row does not show the release value ${forms.join(" / ")} — row: ${row.trim()}`
        );
      }
    }
  }

  if (failures.length) {
    console.error(`check-readme-benchmark-numbers: FAIL (${found.rel})`);
    for (const f of failures) console.error("  - " + f);
    console.error("  Fix the README number to match scripts/jseval/release.v1.json (the canonical release).");
    process.exitCode = 1;
  } else {
    console.log(`check-readme-benchmark-numbers: OK (${found.rel}; ${checked} shown numbers match release.v1.json)`);
  }
}

main();
