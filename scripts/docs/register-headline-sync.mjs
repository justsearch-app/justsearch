/**
 * Project the current benchmark release into the search-quality register's
 * "Release Scorecard" block (tempdoc 623 T-5 / §C-4).
 *
 * Usage:
 *   node scripts/docs/register-headline-sync.mjs           # rewrite the generated block
 *   node scripts/docs/register-headline-sync.mjs --check    # verify committed == generated (CI)
 *
 * Only the region between the markers is touched. The per-corpus (config × mode)
 * ABLATION tables in the register stay hand-authored — they are a heterogeneous
 * research log (different git/config per row). Only the production-default
 * headline number is re-rooted as a PROJECTION of a cohort-identical release,
 * so the hand-typed-fork drift class is removed structurally.
 *
 * Source of truth: scripts/jseval/release.v1.json (composed by `jseval release`).
 * If no release exists yet, a placeholder block is emitted (honest: "pending").
 */

import fs from "node:fs";
import path from "node:path";

const MARKER_START =
  "<!-- generated:start — do not edit between markers; run: node scripts/docs/register-headline-sync.mjs -->";
const MARKER_END = "<!-- generated:end -->";

function repoRootFromCwd() {
  const cwd = process.cwd();
  const markers = ["settings.gradle.kts", "build.gradle.kts", ".git"];
  for (let dir = cwd; ; dir = path.dirname(dir)) {
    for (const m of markers) {
      if (fs.existsSync(path.join(dir, m))) return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return cwd;
}

function fmtExternal(entries) {
  if (!entries || entries.length === 0) return "—";
  return entries
    .map((e) => `${e.model} ${Number(e.value).toFixed(3)}${e.split && e.split !== "test" ? ` (${e.split})` : ""}`)
    .join(", ");
}

function buildGenerated(release) {
  const lines = [MARKER_START, "", "### Release Scorecard (projected — do not hand-edit)", ""];
  if (!release) {
    lines.push(
      "> No benchmark release composed yet. Run `jseval release` (tempdoc 623) and re-run",
      "> `node scripts/docs/register-headline-sync.mjs`. Until then, the per-corpus **Best known**",
      "> lines below remain the hand-authored research-log headlines.",
      "",
      MARKER_END
    );
    return lines.join("\n");
  }

  const cohort = release.cohort || {};
  const hw = cohort.hardware || {};
  const hwStr = [hw.gpu_name, hw.gpu_driver_version && `driver ${hw.gpu_driver_version}`, hw.ort_version && `ORT ${hw.ort_version}`]
    .filter(Boolean)
    .join(" · ") || "unstated";
  const relId = release.release_id || (cohort.git_sha || "").slice(0, 10) || "unknown";
  const ext = release.external_baselines || {};

  lines.push(
    "> Generated from `scripts/jseval/release.v1.json` (tempdoc 623). Each per-corpus number below is a",
    "> **projection** of one cohort-identical release (same config/commit/hardware), not a hand-typed value.",
    "> The (config × mode) ablation tables in each corpus block stay hand-authored. No calibrated reproduction envelope",
    "> is published; compare each run's measured spread directly (see the benchmark methodology).",
    "",
    `**Release:** \`${relId}\` · default mode \`${release.default_mode}\` · ${hwStr}`,
    "",
    `**Coverage:** ${(release.coverage || {}).measures || "retrieval ranking quality"} — **does NOT measure** ${(release.coverage || {}).does_not_measure || "extraction/OCR/route quality"}.`,
    "",
    "| Corpus | Ours (mode) | nDCG@10 | Published baselines (cited, side-by-side) |",
    "|---|---|---|---|"
  );

  const measured = release.measured || {};
  for (const ds of Object.keys(measured).sort()) {
    const m = measured[ds];
    const ndcg = (m.metrics || {})["nDCG@10"];
    const ndcgStr = typeof ndcg === "number" ? ndcg.toFixed(3) : "—";
    lines.push(`| ${ds} | ${m.config_mode} | ${ndcgStr} | ${fmtExternal(ext[ds])} |`);
  }

  // Engine-performance scorecard (tempdoc 640): rendered generically from the perf metric families
  // the release carries (CE-stage latency + per-run throughput/footprint). Present only when a
  // perf-carrying release exists, so a quality-only release is unchanged.
  const PERF_COLS = [
    { key: "ce_p50_ms", label: "CE p50 (ms)", src: "metrics", fmt: (v) => v.toFixed(0) },
    { key: "primary_docs_s", label: "Index docs/s", src: "run_metrics", fmt: (v) => v.toFixed(1) },
    { key: "enrich_docs_s", label: "Enrich docs/s", src: "run_metrics", fmt: (v) => v.toFixed(1) },
    { key: "resident_bytes", label: "Resident (GB)", src: "run_metrics", fmt: (v) => (v / 1e9).toFixed(2) },
  ];
  const perfRows = [];
  for (const ds of Object.keys(measured).sort()) {
    const m = measured[ds];
    const cells = PERF_COLS.map((c) => {
      const v = (c.src === "metrics" ? m.metrics || {} : m.run_metrics || {})[c.key];
      return typeof v === "number" ? c.fmt(v) : "—";
    });
    if (cells.some((c) => c !== "—")) perfRows.push(`| ${ds} | ${cells.join(" | ")} |`);
  }
  if (perfRows.length) {
    lines.push(
      "",
      "**Engine performance** (relative-ratchet guarded — tempdoc 640):",
      "",
      `| Corpus | ${PERF_COLS.map((c) => c.label).join(" | ")} |`,
      `|---|${PERF_COLS.map(() => "---").join("|")}|`,
      ...perfRows
    );
  }

  lines.push("", MARKER_END);
  return lines.join("\n");
}

function main() {
  const root = repoRootFromCwd();
  const registerPath = path.join(root, "docs", "reference", "search-quality-register.md");
  const releasePath = path.join(root, "scripts", "jseval", "release.v1.json");

  if (!fs.existsSync(registerPath)) {
    console.error(`ERROR: register not found at ${registerPath}`);
    process.exitCode = 1;
    return;
  }
  const existing = fs.readFileSync(registerPath, "utf8");
  const startIdx = existing.indexOf(MARKER_START);
  const endIdx = existing.indexOf(MARKER_END);
  if (startIdx === -1 || endIdx === -1) {
    console.error("ERROR: search-quality-register.md is missing the generation markers.");
    console.error(`Expected:\n  ${MARKER_START}\n  ${MARKER_END}`);
    process.exitCode = 1;
    return;
  }

  let release = null;
  if (fs.existsSync(releasePath)) {
    try {
      release = JSON.parse(fs.readFileSync(releasePath, "utf8"));
    } catch (err) {
      console.error(`ERROR: release.v1.json is not valid JSON: ${err.message}`);
      process.exitCode = 1;
      return;
    }
  }

  const before = existing.slice(0, startIdx);
  const after = existing.slice(endIdx + MARKER_END.length);
  const output = before + buildGenerated(release) + after;

  if (process.argv.includes("--check")) {
    if (existing === output) {
      console.log(`register-headline-sync --check: OK${release ? "" : " (no release yet — placeholder)"}`);
    } else {
      console.error("register-headline-sync --check: FAIL — register scorecard is out of date.");
      console.error("Run: node scripts/docs/register-headline-sync.mjs");
      process.exitCode = 1;
    }
  } else {
    fs.writeFileSync(registerPath, output, "utf8");
    console.log(`register-headline-sync: wrote register scorecard${release ? "" : " (placeholder — no release)"}`);
  }
}

main();
