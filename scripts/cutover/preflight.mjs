/**
 * Cutover preflight — one go/no-go for the irreversible flip (tempdoc 634 §T5).
 *
 * Composes the oracles that already exist into a single status board, rather than re-implementing them. The
 * flip is irreversible, so the win is a legible green/red over a moving, multi-gate target — not a new gate
 * (it is a one-shot; YAGNI rules out CI-wiring it). Mirrors the dev-stack `preflight` shape.
 *
 * Two modes:
 *   (default) PREP  — runs the checks that are meaningful before a snapshot exists; everything that needs the
 *                     actual snapshot / a freeze / gitleaks is listed PENDING with the exact command. Never
 *                     hard-fails on a PENDING item — it is a dashboard.
 *   --flip          — the real gate, run against the produced snapshot tree immediately before push. Enforces
 *                     every runnable check; a check that cannot run (e.g. gitleaks absent) is a FAIL.
 *
 * Flags: --flip ; --gates (also run the full discipline-gate kernel — heavy) ; --full (also run the gradle
 *        build/test + public-host test — heavy) ; <treeRoot> (the snapshot tree; default repo root).
 *
 * Exit 0 = nothing actively failed; non-zero = a check ran and FAILED. PENDING/SKIP never fail.
 * Node stdlib only.
 */

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

function repoRoot() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    for (const m of ["settings.gradle.kts", ".git"]) if (fs.existsSync(path.join(dir, m))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

const PASS = "PASS";
const FAIL = "FAIL";
const PENDING = "PENDING";
const SKIP = "SKIP";

function sh(cmd, args, cwd) {
  try {
    const out = execFileSync(cmd, args, { cwd, encoding: "utf8", stdio: ["pipe", "pipe", "pipe"] });
    return { code: 0, out };
  } catch (e) {
    return { code: e.status ?? 1, out: `${e.stdout || ""}${e.stderr || ""}` };
  }
}

function hasGitleaks() {
  return sh(process.platform === "win32" ? "where" : "which", ["gitleaks"]).code === 0;
}

function main() {
  const args = process.argv.slice(2);
  const flip = args.includes("--flip");
  const runGates = args.includes("--gates") || flip;
  const runFull = args.includes("--full");
  const positional = args.filter((a) => !a.startsWith("--"));
  const root = positional[0] ? path.resolve(positional[0]) : repoRoot();
  const node = process.execPath;

  const steps = [];

  // 1. include/exclude linter — runnable now (source mode); strict at flip.
  steps.push({
    id: "snapshot-includes",
    note: "no sidecar/runtime/model-blob staged; full machinery closure present",
    run: () => {
      const a = [path.join(root, "scripts/cutover/check-snapshot-includes.mjs"), root];
      if (!flip) a.push("--source");
      const r = sh(node, a, root);
      return { status: r.code === 0 ? PASS : FAIL, detail: r.out.trim().split("\n").slice(-1)[0] };
    },
  });

  // 2. gitleaks — presence checkable now; the scan itself is flip-time (scope #1).
  steps.push({
    id: "gitleaks",
    note: "secret scan on the snapshot (scope #1; the SOLE defense — unannounced ≠ unindexed)",
    run: () => {
      if (!hasGitleaks()) {
        return {
          status: flip ? FAIL : SKIP,
          detail: "gitleaks not installed — required at the flip (https://github.com/gitleaks/gitleaks)",
        };
      }
      if (!flip) return { status: PENDING, detail: "installed; run `gitleaks detect` on the snapshot at flip" };
      const r = sh("gitleaks", ["detect", "--no-banner", "--redact"], root);
      return { status: r.code === 0 ? PASS : FAIL, detail: r.code === 0 ? "clean" : "potential secret — see output" };
    },
  });

  // 3. full discipline-gate kernel — clears the deferred `main`-green (heavy; opt-in unless --flip).
  steps.push({
    id: "discipline-gates",
    note: "full kernel green — this is where the deferred `main`-green is cleared, just before the flip",
    run: () => {
      if (!runGates) return { status: PENDING, detail: "run `node scripts/governance/run.mjs --mode gate` (--gates to run here)" };
      const r = sh(node, [path.join(root, "scripts/governance/run.mjs"), "--mode", "gate"], root);
      const summary = (r.out.match(/governance: .*/) || [""])[0];
      return { status: r.code === 0 ? PASS : FAIL, detail: summary || (r.code === 0 ? "green" : "red") };
    },
  });

  // 4. public-host test (gradle; heavy) — flip-time unless --full.
  steps.push({
    id: "public-host",
    note: "everyDownloadUrlResolvesFromPublicHost — no private/localhost download host",
    run: () => {
      if (!(runFull || flip)) return { status: PENDING, detail: "gradle test (ModelRegistryLoaderTest); --full to run" };
      const r = sh(process.execPath, [path.join(root, "scripts/lib/tool-invoker.cjs"), "gradle",
        ":modules:configuration:test",
        "--tests",
        "*ModelRegistryLoaderTest.everyDownloadUrlResolvesFromPublicHost",
      ], root);
      return { status: r.code === 0 ? PASS : FAIL, detail: r.code === 0 ? "all download hosts public" : "a host is not public" };
    },
  });

  // 5. dangling-ref sweep — needs the snapshot; flip-time (scope #7 cross-cutting).
  steps.push({
    id: "dangling-refs",
    note: "no published artifact strands a now-private doc (e.g. release.v1.json tempdoc-provenance)",
    run: () => ({ status: PENDING, detail: "run on the snapshot tree at flip (scope #7)" }),
  });

  // 6. content/candor delta-scan — founder/manual; since 631's 2026-06-23 baseline.
  steps.push({
    id: "content-candor-delta",
    note: "candor delta since 631 baseline (the prose analogue of gitleaks; founder-signed)",
    run: () => ({ status: PENDING, detail: "founder/manual delta-scan of tempdocs changed since 2026-06-23" }),
  });

  // 7. build + test (gradle; heavy) — flip-time unless --full.
  steps.push({
    id: "build-test",
    note: "./gradlew build + test green on the snapshot",
    run: () => {
      if (!(runFull || flip)) return { status: PENDING, detail: "`./gradlew build` + `test`; --full to run" };
      const b = sh(process.execPath, [path.join(root, "scripts/lib/tool-invoker.cjs"), "gradle","build", "-x", "test"], root);
      if (b.code !== 0) return { status: FAIL, detail: "build failed" };
      const t = sh(process.execPath, [path.join(root, "scripts/lib/tool-invoker.cjs"), "gradle","test"], root);
      return { status: t.code === 0 ? PASS : FAIL, detail: t.code === 0 ? "build+test green" : "tests failed" };
    },
  });

  // 8. freeze invariant — main checkout clean + no mid-flight worktrees (T2 / finding D).
  steps.push({
    id: "freeze-clean",
    note: "snapshot is cut from a clean, committed `main` with no worktree mid-flight (freeze invariant)",
    run: () => {
      const st = sh("git", ["status", "--porcelain"], root);
      const wt = sh("git", ["worktree", "list", "--porcelain"], root);
      const dirty = st.out.split("\n").filter((l) => l.trim()).length;
      const worktrees = (wt.out.match(/^worktree /gm) || []).length;
      const clean = dirty === 0 && worktrees <= 1;
      if (clean) return { status: PASS, detail: "clean tree, no extra worktrees" };
      const detail = `${dirty} uncommitted change(s), ${Math.max(0, worktrees - 1)} extra worktree(s) — must be 0 at freeze`;
      return { status: flip ? FAIL : PENDING, detail };
    },
  });

  // --- run + report ---
  console.log(`cutover-preflight (${flip ? "FLIP — enforcing" : "PREP — dashboard"}; tree: ${root})\n`);
  const mark = { [PASS]: "✓", [FAIL]: "✗", [PENDING]: "⏳", [SKIP]: "⊘" };
  let anyFail = false;
  for (const s of steps) {
    let res;
    try {
      res = s.run();
    } catch (e) {
      res = { status: FAIL, detail: `check threw: ${e.message}` };
    }
    if (res.status === FAIL) anyFail = true;
    console.log(`  ${mark[res.status] || "?"} ${res.status.padEnd(7)} ${s.id.padEnd(22)} ${res.detail || ""}`);
    console.log(`              ${s.note}`);
  }
  console.log(
    `\n${anyFail ? "NOT READY — a check failed." : flip ? "Resolve every PENDING before push." : "Dashboard only — run --flip --full on the snapshot to enforce."}`
  );
  if (anyFail) process.exitCode = 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
