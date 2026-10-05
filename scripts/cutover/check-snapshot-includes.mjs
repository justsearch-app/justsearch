/**
 * Cutover snapshot include/exclude linter (tempdoc 634, Phase-1 spec → build).
 *
 * Validates that a candidate PUBLIC snapshot tree applies the Option-C include/exclude correctly. Three
 * categories (mirroring cutover-runbook.md §1 — keep the lists in sync):
 *   - EXCLUDE  : the strategy sidecar, the local-runtime bits, and the model LFS blobs must NOT be present.
 *   - CLOSURE  : the FULL agent/governance machinery dependency closure MUST be present (631 C1 — the
 *                narrower "hooks/-only" list ships broken machinery: hooks import ../lib/*, the 36 gate
 *                enforcers live under scripts/governance/gates/).
 *   - SETTINGS : .claude/settings.json must be the guards-only public template (`permissions` may carry
 *                `deny` rules only — 930 row 4 moved force-push protection there; no `env`; the
 *                4 founder-analytics hooks excluded). Enforced in strict mode only (the swap happens at the
 *                flip; the live dev settings legitimately differ before then).
 *
 * Usage:
 *   node scripts/cutover/check-snapshot-includes.mjs [<treeRoot>] [--source]
 *     <treeRoot>  the snapshot tree to validate (default: repo root).
 *     --source    treat <treeRoot> as the PRIVATE source repo, not the snapshot: EXCLUDE hits are EXPECTED
 *                 (they are precisely what the snapshot strips) and reported informationally, not as
 *                 failures; CLOSURE is still enforced; SETTINGS is skipped. Use this to self-check the
 *                 source tree (and see the strip-list) before producing the snapshot.
 *
 * Exit 0 = valid (strict) / closure OK (source); non-zero = a violation that would break the flip.
 * Node stdlib only. No tree walk except a bounded scan under models/ — only specific paths are probed.
 */

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

function repoRoot() {
  const markers = ["settings.gradle.kts", ".git"];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    for (const m of markers) if (fs.existsSync(path.join(dir, m))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

// EXCLUDE — must never reach the public repo. `path` = a file or dir prefix; `onnx` = any *.onnx under a dir.
export const EXCLUDE_RULES = [
  { id: "sidecar/docs-business", kind: "path", target: "docs/business" },
  { id: "sidecar/docs-market-analysis", kind: "path", target: "docs/market-analysis" },
  // Provisional unpublish (founder 2026-06-25): the future-features roadmap stays private for now — it carries
  // stale strategy (open-source-readiness, monetization framing); revisit to publish the technical-roadmap subset.
  { id: "unpublished/docs-future-features", kind: "path", target: "docs/future-features" },
  { id: "local-runtime/settings.local.json", kind: "path", target: ".claude/settings.local.json" },
  { id: "local-runtime/.mcp.json", kind: "path", target: ".mcp.json" },
  { id: "local-runtime/tmp", kind: "path", target: "tmp" },
  { id: "local-runtime/worktrees", kind: "path", target: ".claude/worktrees" },
  { id: "machine-artifacts/tempdoc-390-results", kind: "path", target: "docs/tempdocs/390-results" },
  { id: "model-blobs/onnx", kind: "onnx", target: "models" },
];

// CLOSURE — the machinery dependency closure that MUST ship for the published guardrails to actually run.
export const REQUIRED_PATHS = [
  "scripts/agent-analytics",
  "scripts/agent-analytics/lib",
  "scripts/governance",
  "scripts/governance/gates",
  "scripts/ci",
  "scripts/codegen",
  "governance/registry.v1.json",
];

// The 4 founder-analytics hooks excluded from the public wiring (present-but-opt-in; 631 PUBLIC_EXCLUDED_HOOKS).
export const EXCLUDED_HOOKS = ["dispatch", "export-session-env", "otlp-sink-ensure", "mcp-session-inject"];

/** Pure: does a repo-relative path match an EXCLUDE rule? Returns the rule id or null. */
export function isExcluded(rel) {
  const norm = String(rel).replace(/\\/g, "/").replace(/^\.\//, "");
  for (const r of EXCLUDE_RULES) {
    if (r.kind === "path" && (norm === r.target || norm.startsWith(r.target + "/"))) return r.id;
    if (
      r.kind === "onnx" &&
      norm.endsWith(".onnx") &&
      (norm === r.target || norm.startsWith(r.target + "/"))
    ) {
      return r.id;
    }
  }
  return null;
}

/** Pure: validate a parsed .claude/settings.json against the guards-only template shape. Returns violations. */
export function evaluateSettings(settings) {
  const v = [];
  const obj = settings || {};
  if (Object.prototype.hasOwnProperty.call(obj, "permissions")) {
    // 930 row 4: force-push protection moved off the PreToolUse guard onto `permissions.deny`, so a
    // deny-ONLY block is now part of the public template. An allow/ask posture is still local-only.
    const extra = Object.keys(obj.permissions ?? {}).filter((k) => k !== "deny");
    if (extra.length > 0) {
      v.push(`contains a \`permissions\` block with non-deny keys (${extra.join(", ")}); the public template carries deny rules only`);
    }
  }
  if (Object.prototype.hasOwnProperty.call(obj, "env")) {
    v.push("contains an `env` block (the public template must omit it)");
  }
  const blob = JSON.stringify(obj);
  for (const h of EXCLUDED_HOOKS) {
    if (blob.includes(`${h}.mjs`)) v.push(`wires the excluded founder-analytics hook \`${h}.mjs\``);
  }
  return v;
}

function findOnnx(dir, acc) {
  if (!fs.existsSync(dir)) return acc;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, ent.name);
    if (ent.isDirectory()) findOnnx(full, acc);
    else if (ent.name.endsWith(".onnx")) acc.push(full);
  }
  return acc;
}

/** Probe a tree root for all three categories. Filesystem-touching; the matching logic is the pure helpers above. */
export function evaluateSnapshot(treeRoot, { source = false } = {}) {
  const excludePresent = [];
  for (const r of EXCLUDE_RULES) {
    if (r.kind === "path") {
      if (fs.existsSync(path.join(treeRoot, r.target))) excludePresent.push({ id: r.id, path: r.target });
    } else if (r.kind === "onnx") {
      for (const b of findOnnx(path.join(treeRoot, r.target), [])) {
        excludePresent.push({ id: r.id, path: path.relative(treeRoot, b).replace(/\\/g, "/") });
      }
    }
  }

  const closureMissing = REQUIRED_PATHS.filter((p) => !fs.existsSync(path.join(treeRoot, p)));

  const settingsViolations = [];
  if (!source) {
    const settingsPath = path.join(treeRoot, ".claude", "settings.json");
    if (fs.existsSync(settingsPath)) {
      try {
        settingsViolations.push(...evaluateSettings(JSON.parse(fs.readFileSync(settingsPath, "utf8"))));
      } catch (e) {
        settingsViolations.push(`.claude/settings.json is not valid JSON: ${e.message}`);
      }
    } else {
      settingsViolations.push(".claude/settings.json is absent (the guards-only template must be swapped in)");
    }
  }

  return { excludePresent, closureMissing, settingsViolations, source };
}

function main() {
  const args = process.argv.slice(2);
  const source = args.includes("--source");
  const positional = args.filter((a) => !a.startsWith("--"));
  const treeRoot = positional[0] ? path.resolve(positional[0]) : repoRoot();

  const r = evaluateSnapshot(treeRoot, { source });

  // In --source mode the excluded paths are expected present (they are the strip-list), so they don't fail.
  const excludeFails = source ? [] : r.excludePresent;

  if (r.excludePresent.length) {
    if (source) {
      console.log(`check-snapshot-includes: ${r.excludePresent.length} path(s) to STRIP from the snapshot:`);
      for (const e of r.excludePresent) console.log(`  - ${e.path}  [${e.id}]`);
    } else {
      console.error(`check-snapshot-includes: EXCLUDE VIOLATION — these must NOT be in the public snapshot:`);
      for (const e of r.excludePresent) console.error(`  - ${e.path}  [${e.id}]`);
    }
  }
  if (r.closureMissing.length) {
    console.error(`check-snapshot-includes: CLOSURE VIOLATION — required machinery missing (631 C1):`);
    for (const p of r.closureMissing) console.error(`  - ${p}`);
  }
  if (r.settingsViolations.length) {
    console.error(`check-snapshot-includes: SETTINGS VIOLATION — .claude/settings.json is not the public template:`);
    for (const s of r.settingsViolations) console.error(`  - ${s}`);
  }

  const failed = excludeFails.length > 0 || r.closureMissing.length > 0 || r.settingsViolations.length > 0;
  if (failed) {
    process.exitCode = 1;
  } else {
    const mode = source ? "source" : "strict";
    console.log(
      `check-snapshot-includes: OK (${mode}; closure ${REQUIRED_PATHS.length}/${REQUIRED_PATHS.length} present` +
        (source ? `, ${r.excludePresent.length} to strip` : "") +
        ")"
    );
  }
}

// Run only as a CLI, not when imported by the test (robust on Windows' file:///X:/ URLs).
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
