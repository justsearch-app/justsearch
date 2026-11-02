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
const REGISTER_START =
  "<!-- generated:start — do not edit between markers; run: node scripts/docs/register-headline-sync.mjs -->";
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

  const register = path.join(root, "docs", "reference", "search-quality-register.md");
  fs.writeFileSync(register, `before\n${REGISTER_START}\nstale\n${END}\nafter\n`);

  for (const script of ["gen-public-benchmark.mjs", "gen-scorecard.mjs", "register-headline-sync.mjs"]) {
    const result = spawnSync(process.execPath, [path.join(HERE, script)], { cwd: root, encoding: "utf8" });
    assert.equal(result.status, 0, `${script} failed: ${result.stdout}${result.stderr}`);
  }
  for (const doc of ["methodology.md", "scorecard.md"]) {
    const text = fs.readFileSync(path.join(docs, doc), "utf8");
    assert.match(text, /measured mode `hybrid`/, `${doc} names the measured mode`);
    assert.doesNotMatch(text, /default mode/i, `${doc} does not call the measured mode the default`);
  }
  const registerText = fs.readFileSync(register, "utf8");
  assert.match(registerText, /measured mode `hybrid`/, "register scorecard names the measured mode");
  assert.doesNotMatch(registerText, /default mode/i, "register scorecard does not call the measured mode the default");

  // The hand-written paragraph that reads the committed register's scorecard must not call it the
  // product default either, nor cite a production-default score.
  const committed = fs.readFileSync(path.join(REPO, "docs", "reference", "search-quality-register.md"), "utf8");
  const tail = committed.slice(committed.indexOf(END, committed.indexOf(REGISTER_START)) + END.length);
  const reading = tail.slice(0, tail.search(/^#{2,3} /m));
  assert.match(reading, /Reading the two numbers/, "the scorecard reading note follows the generated block");
  assert.doesNotMatch(reading, /production[- ]default|default mode/i, "the reading note does not call the scorecard the product default");

  // Every score the reading note shows is a value the committed release records (a measured headline or
  // an ablation). A withheld headline (legal's earlier hybrid 0.598) or a retired corpus's number
  // (CourtListener's full-mode 0.925) has no entry there, so it cannot come back into the note.
  const release = JSON.parse(fs.readFileSync(path.join(REPO, "scripts", "jseval", "release.v1.json"), "utf8"));
  const recorded = [
    ...Object.values(release.measured || {}),
    ...Object.values(release.ablations || {}).flat(),
  ]
    .map((entry) => entry.metrics && entry.metrics["nDCG@10"])
    .filter((v) => typeof v === "number");
  const forms = new Set(recorded.flatMap((v) => [v.toFixed(3), v.toFixed(2)]));
  const shown = reading.match(/\d*[.,]\d+(?:\s*%)?|\d+\s*%/g) || [];
  for (const score of shown) {
    assert.ok(forms.has(score), `the reading note shows ${score}, which the current release does not record`);
  }
  for (const [dataset, entry] of Object.entries(release.withheld || {})) {
    if (reading.includes(dataset)) {
      assert.match(reading, /withholds/, `the reading note says the release withholds ${dataset}'s result`);
    }
    assert.equal(entry.metrics, undefined, `withheld ${dataset} carries no headline metric to cite`);
  }
  for (const stale of ["0.598", "0.925"]) {
    assert.ok(!shown.includes(stale), `the reading note does not show ${stale}`);
  }

  const schema = JSON.parse(fs.readFileSync(path.join(REPO, "scripts", "jseval", "release.v1.schema.json"), "utf8"));
  const description = schema.properties.default_mode.description;
  assert.match(description, /measured/i, "schema describes default_mode as the measured mode");
  assert.doesNotMatch(description, /production-default/i, "schema does not call it the production default");
} finally {
  fs.rmSync(root, { recursive: true, force: true });
}
console.log("gen-benchmark-mode-label.test: OK");
