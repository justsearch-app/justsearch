/**
 * Project the canonical benchmark release into the PUBLIC-facing benchmark table(s) — tempdoc 633 #2.
 *
 * The public methodology doc (and, once staged, the launch README) show a benchmark table. That table is
 * a *projection* of scripts/jseval/release.v1.json (the single canonical, cohort-identified release
 * object — tempdoc 623), never a hand-transcribed fork, so it cannot silently drift when a new release is
 * composed. This is the public/external twin of register-headline-sync.mjs (which projects the same source
 * into the internal search-quality register).
 *
 * Usage:
 *   node scripts/docs/gen-public-benchmark.mjs           # rewrite the generated region in each target
 *   node scripts/docs/gen-public-benchmark.mjs --check    # verify committed == generated (CI); exit 1 on drift
 *
 * Only the region between the markers is touched. Our measured numbers are the projection; the external
 * baselines are CITED evidence (self_reproduced:false) carried verbatim with their caveats (623 C-3).
 */

import fs from "node:fs";
import path from "node:path";

const MARKER_START =
  "<!-- generated:start — do not edit between markers; run: node scripts/docs/gen-public-benchmark.mjs -->";
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

// Mirrors register-headline-sync.mjs:39-44 — format cited external baselines into one compact string.
function fmtExternal(entries) {
  if (!entries || entries.length === 0) return "—";
  return entries
    .map((e) => `${e.model} ${Number(e.value).toFixed(3)}${e.split && e.split !== "test" ? ` (${e.split})` : ""}`)
    .join("; ");
}

function nd(metrics) {
  const v = metrics && metrics["nDCG@10"];
  return typeof v === "number" ? v.toFixed(3) : "—";
}

function buildGenerated(release) {
  if (release?.status === "superseded") throw new Error("superseded release cannot supply current public claims");
  const lines = [MARKER_START, ""];
  if (!release || !release.measured) {
    lines.push(
      "> No benchmark release composed yet. Run `jseval release` (tempdoc 623), then re-run",
      "> `node scripts/docs/gen-public-benchmark.mjs`.",
      "",
      MARKER_END
    );
    return lines.join("\n");
  }

  const hw = (release.cohort && release.cohort.hardware) || {};
  const vramGib = hw.gpu_vram_bytes ? Math.round(hw.gpu_vram_bytes / 2 ** 30) : null;
  const sha = release.cohort && release.cohort.git_sha ? release.cohort.git_sha.slice(0, 9) : "unknown";
  const env = [hw.os, hw.cpu, hw.gpu_name, vramGib ? `${vramGib} GiB VRAM` : null, hw.ort_version ? `ORT ${hw.ort_version}` : null]
    .filter(Boolean)
    .join(", ");

  lines.push(
    `*Release \`${release.release_id}\`, measured mode \`${release.default_mode || "hybrid"}\`, commit \`${sha}\`${env ? `, ${env}` : ""}. ` +
      `nDCG@10. External baselines are cited published numbers (not re-run by us) — see the comparison-class note above.*`,
    "",
    "Explicit hybrid mode uses keyword + vector + cross-encoder reranking; learned-sparse is off in this preset. MIRACL uses dev queries and complete positive judgments in the retained sampled document pool. This Linux measurement is a new release, not a rerun of the superseded Windows release.",
    "",
    "Legal caveat: legal-clerc-200 has 198 documents, all judged relevant to at least one query, and queries of roughly 260 words. It is a small citation-retrieval pool, not a representative large legal archive.",
    "",
    "| Corpus | Ours (mode) | nDCG@10 | Ablation | Published baselines (cited) |",
    "|---|---|---|---|---|"
  );

  const ablations = release.ablations || {};
  const ext = release.external_baselines || {};
  for (const corpus of [...new Set([...Object.keys(release.measured), ...Object.keys(release.withheld || {})])].sort()) {
    const m = release.measured[corpus];
    const withheld = release.withheld?.[corpus];
    const abl = ablations[corpus];
    const ablCell =
      abl && abl.length
        ? abl.map((a) => `${nd(a.metrics)} (${a.config_mode})`).join("; ")
        : "—";
    lines.push(
      `| ${corpus} | ${m?.config_mode || withheld.config_mode} | ${withheld ? "WITHHELD" : `**${nd(m.metrics)}**`} | ${ablCell} | ${fmtExternal(ext[corpus])} |`
    );
  }
  lines.push("", "Cross-encoder coverage (applied / all queries; deterministic skips and deadline drops are reported separately):", "");
  for (const corpus of Object.keys(release.measured).sort()) {
    const m = release.measured[corpus];
    const ce = m.ce_coverage;
    if (ce) lines.push(`- ${corpus}: ${ce.applied}/${m.corpus_source.query_count}; ${ce.legitimate_skips} deterministic skips; ${ce.silent_drops} deadline drops; harness verdict \`${ce.verdict}\`.`);
  }
  for (const [corpus, entry] of Object.entries(release.withheld || {})) {
    lines.push("", `**${corpus} ${entry.config_mode} withheld:** ${entry.reason}`);
  }

  // Engine-performance section (tempdoc 640): rendered generically from the perf metric families the
  // release carries — CE-stage latency (per-mode `metrics`) + throughput/footprint (per-run `run_metrics`).
  // Present only when a perf-carrying release exists, so a quality-only release is unchanged.
  const PERF_COLS = [
    { key: "ce_p50_ms", label: "CE p50 (ms)", src: "metrics", fmt: (v) => v.toFixed(0) },
    { key: "primary_docs_s", label: "Index docs/s", src: "run_metrics", fmt: (v) => v.toFixed(1) },
    { key: "enrich_docs_s", label: "Enrich docs/s", src: "run_metrics", fmt: (v) => v.toFixed(1) },
    { key: "resident_bytes", label: "Resident (GB)", src: "run_metrics", fmt: (v) => (v / 1e9).toFixed(2) },
  ];
  const perfRows = [];
  for (const corpus of Object.keys(release.measured).sort()) {
    const m = release.measured[corpus];
    const cells = PERF_COLS.map((c) => {
      const v = (c.src === "metrics" ? m.metrics || {} : m.run_metrics || {})[c.key];
      return typeof v === "number" ? c.fmt(v) : "—";
    });
    if (cells.some((c) => c !== "—")) perfRows.push(`| ${corpus} | ${cells.join(" | ")} |`);
  }
  if (perfRows.length) {
    lines.push(
      "",
      "**Engine performance** (relative-ratchet guarded — tempdoc 640; lower latency / higher throughput / lower footprint better):",
      "",
      `| Corpus | ${PERF_COLS.map((c) => c.label).join(" | ")} |`,
      `|---|${PERF_COLS.map(() => "---").join("|")}|`,
      ...perfRows
    );
  }

  lines.push("", MARKER_END);
  return lines.join("\n");
}

function applyToFile(absPath, generated, check, repoRoot) {
  const rel = path.relative(repoRoot, absPath).replaceAll("\\", "/");
  if (!fs.existsSync(absPath)) {
    return { rel, status: "missing" };
  }
  const text = fs.readFileSync(absPath, "utf8");
  const start = text.indexOf(MARKER_START);
  const end = text.indexOf(MARKER_END);
  if (start < 0 || end < 0 || end < start) {
    return { rel, status: "no-markers" };
  }
  const before = text.slice(0, start);
  const after = text.slice(end + MARKER_END.length);
  const next = before + generated + after;
  if (next === text) {
    return { rel, status: "in-sync" };
  }
  if (check) {
    return { rel, status: "drift" };
  }
  fs.writeFileSync(absPath, next);
  return { rel, status: "updated" };
}

function main() {
  const repoRoot = repoRootFromCwd();
  const check = process.argv.includes("--check");

  const releasePath = path.join(repoRoot, "scripts", "jseval", "release.v1.json");
  const release = fs.existsSync(releasePath) ? JSON.parse(fs.readFileSync(releasePath, "utf8")) : null;
  const generated = buildGenerated(release);

  // Projection targets. The methodology doc is the sole target today; the launch README gains a marker
  // region and joins this list when it is promoted to the repo root at the 634 cutover (tempdoc 633 hand-off).
  const targets = [
    { abs: path.join(repoRoot, "docs", "reference", "benchmarks", "methodology.md"), required: true },
  ];

  const results = targets.map((t) => ({ ...applyToFile(t.abs, generated, check, repoRoot), required: t.required }));

  let failed = false;
  for (const r of results) {
    if (r.required && (r.status === "missing" || r.status === "no-markers")) {
      console.log(`gen-public-benchmark: FAIL ${r.rel} — ${r.status} (a required projection target lacks the marker region)`);
      failed = true;
    } else if (r.status === "drift") {
      console.log(`gen-public-benchmark: DRIFT ${r.rel} — committed table is stale; run without --check to regenerate`);
      failed = true;
    } else if (r.status !== "missing" && r.status !== "no-markers") {
      console.log(`gen-public-benchmark: ${r.rel} — ${r.status}`);
    }
  }

  if (failed) {
    process.exitCode = 1;
  } else if (!check) {
    console.log("gen-public-benchmark: OK");
  } else {
    console.log("gen-public-benchmark: OK (in sync)");
  }
}

main();
