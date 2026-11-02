/**
 * The release's `default_mode` is the mode the benchmark measured, not the product's default: a request
 * that names no mode runs AUTO (SearchPipelinePresets.java). The generated benchmark headers and the
 * release schema must say "measured mode", never "default mode" or "production-default".
 *
 * Run: `node scripts/docs/gen-benchmark-mode-label.test.mjs` (exits non-zero on failure)
 */
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, "..", "..");
const START = "<!-- generated:start — do not edit between markers; run: node scripts/docs/gen-public-benchmark.mjs -->";
const END = "<!-- generated:end -->";
const RELEASE = {
  release_id: "fixture-2026-01-01",
  default_mode: "hybrid",
  cohort: { git_sha: "0123456789abcdef", hardware: { os: "Linux" } },
  measured: { "beir/scifact": { config_mode: "hybrid", metrics: { "nDCG@10": 0.759 }, corpus_source: { query_count: 300 } } },
};

const root = fs.mkdtempSync(path.join(os.tmpdir(), "benchmark-mode-label-"));
try {
  fs.writeFileSync(path.join(root, "settings.gradle.kts"), "");
  fs.mkdirSync(path.join(root, "scripts", "jseval"), { recursive: true });
  fs.writeFileSync(path.join(root, "scripts", "jseval", "release.v1.json"), JSON.stringify(RELEASE));
  const docs = path.join(root, "docs", "reference", "benchmarks");
  fs.mkdirSync(docs, { recursive: true });
  fs.writeFileSync(path.join(docs, "methodology.md"), `before\n${START}\nstale\n${END}\nafter\n`);

  for (const script of ["gen-public-benchmark.mjs", "gen-scorecard.mjs"]) {
    const result = spawnSync(process.execPath, [path.join(HERE, script)], { cwd: root, encoding: "utf8" });
    assert.equal(result.status, 0, `${script} failed: ${result.stdout}${result.stderr}`);
  }
  for (const doc of ["methodology.md", "scorecard.md"]) {
    const text = fs.readFileSync(path.join(docs, doc), "utf8");
    assert.match(text, /measured mode `hybrid`/, `${doc} names the measured mode`);
    assert.doesNotMatch(text, /default mode/i, `${doc} does not call the measured mode the default`);
  }
  const schema = JSON.parse(fs.readFileSync(path.join(REPO, "scripts", "jseval", "release.v1.schema.json"), "utf8"));
  const description = schema.properties.default_mode.description;
  assert.match(description, /measured/i, "schema describes default_mode as the measured mode");
  assert.doesNotMatch(description, /production-default/i, "schema does not call it the production default");
} finally {
  fs.rmSync(root, { recursive: true, force: true });
}
console.log("gen-benchmark-mode-label.test: OK");
