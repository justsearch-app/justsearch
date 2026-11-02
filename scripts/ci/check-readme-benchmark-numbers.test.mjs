/**
 * Tests for the README benchmark-table guard (scripts/ci/check-readme-benchmark-numbers.mjs): every row
 * the README shows must resolve to measured or explicitly withheld evidence in the release. The CLI is
 * spawned against disposable fixture roots (settings.gradle.kts marker + release + README), never the
 * real repo.
 *
 * Run: `node scripts/ci/check-readme-benchmark-numbers.test.mjs` (exits non-zero on failure)
 */
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), "check-readme-benchmark-numbers.mjs");

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (error) {
    failures.push(error.message);
  }
};

const nd = (v) => ({ metrics: { "nDCG@10": v } });
const RELEASE = {
  release_id: "rel-fixture-2026-01-01",
  measured: {
    "beir/scifact": nd(0.759),
    "mixed/enron-qa": nd(0.806),
    "mixed/miracl-de-2k": nd(0.986),
    "mixed/miracl-fr-2k": nd(0.99),
  },
  withheld: { "mixed/legal-clerc-200": { reason: "degraded-ce" } },
  ablations: { "mixed/legal-clerc-200": [{ config_mode: "lexical", ...nd(0.691) }, { config_mode: "vector", ...nd(0.617) }] },
};
const README = [
  "# Fixture",
  "",
  "## Benchmarks",
  "",
  "| Corpus | nDCG@10 | Note |",
  "|---|---|---|",
  "| BEIR / SciFact | **0.759** | note |",
  "| Enron-QA | 0.806 | note |",
  "| MIRACL-de (German) | 0.986 | note |",
  "| MIRACL-fr (French) | 0.990 | note |",
  "| Legal (CLERC case-retrieval) | WITHHELD | note |",
  "",
  "## Next",
  "",
].join("\n");

function run(release, readme) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "check-readme-numbers-"));
  try {
    fs.writeFileSync(path.join(root, "settings.gradle.kts"), "");
    fs.mkdirSync(path.join(root, "scripts", "jseval"), { recursive: true });
    fs.writeFileSync(path.join(root, "scripts", "jseval", "release.v1.json"), JSON.stringify(release));
    fs.writeFileSync(path.join(root, "README.md"), readme);
    return spawnSync(process.execPath, [SCRIPT], { cwd: root, encoding: "utf8" });
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

// --- control: a README consistent with the release passes ---
{
  const result = run(RELEASE, README);
  ok("a consistent README passes", result.status === 0);
}

// --- review counterexample 1: SciFact withheld, README still shows its old score ---
{
  const { "beir/scifact": scifact, ...rest } = RELEASE.measured;
  const release = { ...RELEASE, measured: rest, withheld: { ...RELEASE.withheld, "beir/scifact": { reason: "x" } } };
  const result = run(release, README);
  ok("a withheld SciFact row that still shows 0.759 fails", result.status !== 0);
  ok("the failure names SciFact as needing WITHHELD", /SciFact: default-mode row must state WITHHELD/.test(result.stderr));
}

// --- review counterexample 2: CLERC in neither measured nor withheld, README shows a score ---
{
  const release = { ...RELEASE, withheld: {} };
  const readme = README.replace("| WITHHELD |", "| 0.578 |");
  const result = run(release, readme);
  ok("a CLERC row with 0.578 and no release evidence fails", result.status !== 0);
  ok("the failure names the missing evidence", /CLERC: README shows a row but the release has neither/.test(result.stderr));
}

// --- a withheld row must not carry a score alongside WITHHELD ---
{
  const readme = README.replace("| WITHHELD |", "| WITHHELD (0.578) |");
  const result = run(RELEASE, readme);
  ok("a WITHHELD row that also prints a score fails", result.status !== 0);
}

// --- a measured row with the wrong number still fails ---
{
  const result = run(RELEASE, README.replace("0.806", "0.799"));
  ok("a measured row with a stale number fails", result.status !== 0);
  ok("the failure names the release value", /Enron: README row does not show the release value 0\.806/.test(result.stderr));
}

// --- re-review finding 2: a row whose label matches no expectation is never silently skipped ---
{
  const result = run(RELEASE, README.replace("MIRACL-de (German)", "MIRACL German").replace("0.986", "0.999"));
  ok("a renamed row showing a decimal fails", result.status !== 0);
  ok("the failure names the unrecognised row", /unrecognised Benchmarks row shows a number/.test(result.stderr));
}
{
  const readme = README.replace("| Legal (CLERC", "| NFCorpus | 0.412 | new corpus |\n| Legal (CLERC");
  const result = run(RELEASE, readme);
  ok("a new corpus row showing a decimal fails", result.status !== 0);
}
{
  const readme = README.replace("| Legal (CLERC", "| Planned corpus | — | not measured |\n| Legal (CLERC");
  const result = run(RELEASE, readme);
  ok("an unrecognised row without a number passes", result.status === 0);
}

// --- review round 3 should-fix 2: every row is checked, not only the first row with a label ---
const after = (row) => README.replace("| Legal (CLERC case-retrieval) | WITHHELD | note |", `| Legal (CLERC case-retrieval) | WITHHELD | note |\n${row}`);
{
  const result = run(RELEASE, after("| Legal lexical ablation (CLERC) | 0.690 | note |"));
  ok("a second CLERC row with a wrong ablation number fails", result.status !== 0);
  ok("the failure names the lexical ablation", /CLERC: lexical ablation row does not show the release value 0\.691/.test(result.stderr));
}
{
  const result = run(RELEASE, after("| Legal lexical ablation (CLERC) | 0.691 | note |"));
  ok("a declared ablation row showing the release value passes", result.status === 0);
}
{
  const result = run(RELEASE, after("| Legal ablation (CLERC) | 0.691 | note |"));
  ok("an ablation row naming no recorded mode fails", result.status !== 0);
}
{
  const result = run(RELEASE, README.replace("| Enron-QA", "| SciFact (full) | 0.812 | note |\n| Enron-QA"));
  ok("a second SciFact row with any number fails", result.status !== 0);
  ok("the failure names the reused label", /SciFact: a second row reuses this corpus label/.test(result.stderr));
}
{
  const result = run(RELEASE, README.replace("| Enron-QA", "| SciFact (full) | **0.759** | note |\n| Enron-QA"));
  ok("a second SciFact row repeating the measured number still fails", result.status !== 0);
}
{
  const result = run(RELEASE, README.replace("| Enron-QA | 0.806 |", "| Enron-QA vs SciFact | 0.806 0.759 |"));
  ok("a row matching two corpus labels fails", result.status !== 0);
  ok("the failure names the ambiguity", /row matches more than one corpus label/.test(result.stderr));
}
{
  const result = run(RELEASE, README.replace("0.806", "0.8060"));
  ok("a rounded form only matches as a whole number", result.status !== 0);
}
for (const form of ["81%", "0,81", "81 %"]) {
  const readme = README.replace("| Legal (CLERC", `| NFCorpus | ${form} | new corpus |\n| Legal (CLERC`);
  const result = run(RELEASE, readme);
  ok(`an unrecognised row showing ${form} fails`, result.status !== 0);
}
for (const form of [".578", "57.8", "57.8%", "0,578"]) {
  const result = run(RELEASE, README.replace("| WITHHELD |", `| WITHHELD (${form}) |`));
  ok(`a WITHHELD row that also prints ${form} fails`, result.status !== 0);
}

// --- a superseded release cannot supply README claims ---
{
  const result = run({ ...RELEASE, status: "superseded" }, README);
  ok("a superseded release fails", result.status !== 0);
}

if (failures.length) {
  console.error(`check-readme-benchmark-numbers.test: FAIL (${failures.length})`);
  for (const failure of failures) console.error("  - " + failure);
  process.exit(1);
}
console.log(`check-readme-benchmark-numbers.test: OK (${passed} assertions)`);
