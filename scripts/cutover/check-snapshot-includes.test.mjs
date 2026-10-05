/**
 * Tests for the cutover snapshot include/exclude linter (tempdoc 634). Covers the pure matching logic
 * (isExcluded, evaluateSettings) plus a filesystem round-trip of evaluateSnapshot over synthetic temp trees.
 *
 * Run: `node scripts/cutover/check-snapshot-includes.test.mjs` (exits non-zero on failure)
 */
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  isExcluded,
  evaluateSettings,
  evaluateSnapshot,
  REQUIRED_PATHS,
  EXCLUDED_HOOKS,
} from "./check-snapshot-includes.mjs";

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
};

// --- isExcluded (pure) ---
ok("EXCLUDE: docs/business dir", isExcluded("docs/business") === "sidecar/docs-business");
ok("EXCLUDE: a file under docs/business", isExcluded("docs/business/strategy.md") === "sidecar/docs-business");
ok("EXCLUDE: docs/market-analysis", isExcluded("docs/market-analysis/x.md") === "sidecar/docs-market-analysis");
ok("EXCLUDE: docs/future-features (provisional unpublish)", isExcluded("docs/future-features/roadmap.md") === "unpublished/docs-future-features");
ok("EXCLUDE: settings.local.json", isExcluded(".claude/settings.local.json") === "local-runtime/settings.local.json");
ok("EXCLUDE: .mcp.json", isExcluded(".mcp.json") === "local-runtime/.mcp.json");
ok("EXCLUDE: tmp data", isExcluded("tmp/agent-telemetry/x.json") === "local-runtime/tmp");
ok(
  "EXCLUDE: tempdoc 390 machine artifacts",
  isExcluded("docs/tempdocs/390-results/fingerprint.txt") === "machine-artifacts/tempdoc-390-results"
);
ok("EXCLUDE: an onnx blob under models/", isExcluded("models/onnx/embed.onnx") === "model-blobs/onnx");
ok("EXCLUDE: backslash paths normalize", isExcluded("docs\\business\\x.md") === "sidecar/docs-business");
ok("KEEP: a normal module source file", isExcluded("modules/ui/src/main/java/X.java") === null);
ok("KEEP: a tokenizer json under models/ (not .onnx)", isExcluded("models/onnx/tokenizer.json") === null);
ok("KEEP: a tempdoc", isExcluded("docs/tempdocs/634-x.md") === null);
ok("KEEP: docs/business-ish but not the dir (prefix is path-segment safe)", isExcluded("docs/business-plan.md") === null);

// --- evaluateSettings (pure) ---
ok(
  "SETTINGS: flags an allow/ask permissions posture",
  evaluateSettings({ permissions: { allow: [] } }).some((v) => v.includes("permissions"))
);
ok(
  "SETTINGS: a deny-only permissions block is allowed (930 row 4 force-push rules)",
  evaluateSettings({ permissions: { deny: ["Bash(git push --force*)"] } }).length === 0
);
ok("SETTINGS: flags an env block", evaluateSettings({ env: {} }).some((v) => v.includes("env")));
ok(
  "SETTINGS: flags an excluded analytics hook",
  evaluateSettings({ hooks: { SessionStart: [{ hooks: [{ args: ["x/dispatch.mjs"] }] }] } }).some((v) =>
    v.includes("dispatch.mjs")
  )
);
ok(
  "SETTINGS: a guards-only template (a guard hook, no permissions/env) is clean",
  evaluateSettings({ hooks: { PreToolUse: [{ hooks: [{ args: ["x/repeat-guard.mjs"] }] }] } }).length === 0
);
ok("SETTINGS: every excluded hook is detected", EXCLUDED_HOOKS.every((h) => evaluateSettings({ x: `${h}.mjs` }).length === 1));

// --- evaluateSnapshot (filesystem round-trip over a synthetic tree) ---
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cutover-snap-"));
try {
  // Build a tree that satisfies CLOSURE...
  for (const p of REQUIRED_PATHS) {
    const abs = path.join(tmp, p);
    if (p.endsWith(".json")) {
      fs.mkdirSync(path.dirname(abs), { recursive: true });
      fs.writeFileSync(abs, "{}");
    } else {
      fs.mkdirSync(abs, { recursive: true });
    }
  }
  // ...and a guards-only settings.json. `.claude/` is no longer created by a required path
  // (the old agent layer was retired at agent-system adoption), so create it here.
  fs.mkdirSync(path.join(tmp, ".claude"), { recursive: true });
  fs.writeFileSync(
    path.join(tmp, ".claude", "settings.json"),
    JSON.stringify({ hooks: { PreToolUse: [{ hooks: [{ args: ["x/repeat-guard.mjs"] }] }] } })
  );

  const clean = evaluateSnapshot(tmp, { source: false });
  ok("SNAPSHOT clean: no exclude hits", clean.excludePresent.length === 0);
  ok("SNAPSHOT clean: no closure misses", clean.closureMissing.length === 0);
  ok("SNAPSHOT clean: no settings violations", clean.settingsViolations.length === 0);

  // Now inject violations: a sidecar dir, an onnx blob, a permissions block.
  fs.mkdirSync(path.join(tmp, "docs", "business"), { recursive: true });
  fs.writeFileSync(path.join(tmp, "docs", "business", "strategy.md"), "secret");
  fs.mkdirSync(path.join(tmp, "models", "onnx"), { recursive: true });
  fs.writeFileSync(path.join(tmp, "models", "onnx", "embed.onnx"), "blob");
  fs.writeFileSync(
    path.join(tmp, ".claude", "settings.json"),
    JSON.stringify({ permissions: { allow: [] }, hooks: {} })
  );

  const dirty = evaluateSnapshot(tmp, { source: false });
  ok("SNAPSHOT dirty: sidecar dir flagged", dirty.excludePresent.some((e) => e.id === "sidecar/docs-business"));
  ok("SNAPSHOT dirty: onnx blob flagged", dirty.excludePresent.some((e) => e.id === "model-blobs/onnx"));
  ok("SNAPSHOT dirty: permissions block flagged", dirty.settingsViolations.some((v) => v.includes("permissions")));

  // --source mode: the same dirty tree's excludes are reported but NOT failures; closure still enforced.
  const sourced = evaluateSnapshot(tmp, { source: true });
  ok("SOURCE mode: excludes still reported", sourced.excludePresent.length >= 2);
  ok("SOURCE mode: settings skipped", sourced.settingsViolations.length === 0);

  // Closure violation: remove a required path.
  fs.rmSync(path.join(tmp, "scripts", "governance", "gates"), { recursive: true, force: true });
  const broken = evaluateSnapshot(tmp, { source: true });
  ok("CLOSURE: missing gates dir flagged even in source mode", broken.closureMissing.includes("scripts/governance/gates"));
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

if (failures.length > 0) {
  console.error(`check-snapshot-includes.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`check-snapshot-includes.test: all ${passed} checks passed`);
