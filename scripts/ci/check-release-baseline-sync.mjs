/**
 * CI guard: the benchmark-release projections cannot silently rot or re-fork
 * (tempdoc 623 §C-6 / increment 5).
 *
 * Checks, in order (all best-effort-but-fail-loud):
 *   1. If scripts/jseval/release.v1.json exists, it validates against
 *      scripts/jseval/release.v1.schema.json (Ajv).
 *   2. The relevance-ratchet pointer (scripts/jseval/relevance-ratchet-baselines.v1.json)
 *      is well-formed: either it points to an existing `current_release`, or it
 *      carries `fallback_baselines` / inline `baselines` (transition).
 *   2b. The perf-ratchet pointer (scripts/jseval/perf-ratchet-baselines.v1.json, tempdoc 640)
 *      — when present — parses, carries the expected schema, and has a `baselines` object.
 *   3. The register "Release Scorecard" block is in sync with the release —
 *      delegates to `register-headline-sync.mjs --check`.
 *
 * Usage: node scripts/ci/check-release-baseline-sync.mjs
 * Exit 0 = consistent; non-zero = stale / invalid (with a fix hint).
 *
 * Mirrors the projection-staleness pattern of the regen set (scripts/ci/regen-all.mjs).
 * Uses only repo-root deps (Ajv) + Node stdlib.
 */

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { execFileSync } from "node:child_process";
import Ajv from "ajv";

function repoRoot() {
  const markers = ["settings.gradle.kts", ".git"];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    for (const m of markers) if (fs.existsSync(path.join(dir, m))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function main() {
  const root = repoRoot();
  const releasePath = path.join(root, "scripts", "jseval", "release.v1.json");
  const schemaPath = path.join(root, "scripts", "jseval", "release.v1.schema.json");
  const pointerPath = path.join(root, "scripts", "jseval", "relevance-ratchet-baselines.v1.json");
  const failures = [];

  // 1. Release schema validation (if a release exists).
  if (fs.existsSync(releasePath)) {
    if (!fs.existsSync(schemaPath)) {
      failures.push(`release.v1.json exists but schema missing at ${schemaPath}`);
    } else {
      const schema = JSON.parse(fs.readFileSync(schemaPath, "utf8"));
      const release = JSON.parse(fs.readFileSync(releasePath, "utf8"));
      if (release.status === "superseded") failures.push("current release is superseded and cannot supply claims or floors");
      const ajv = new Ajv({ allErrors: true, strict: false });
      const validate = ajv.compile(schema);
      if (!validate(release)) {
        failures.push(
          "release.v1.json fails schema:\n" +
            (validate.errors || []).map((e) => `    ${e.instancePath || "/"} ${e.message}`).join("\n")
        );
      }
    }
  } else {
    console.log("check-release-baseline-sync: no release.v1.json yet (pre-first-release) — schema check skipped.");
  }

  // 2. Ratchet pointer well-formed.
  if (!fs.existsSync(pointerPath)) {
    failures.push(`relevance-ratchet pointer missing at ${pointerPath}`);
  } else {
    const ptr = JSON.parse(fs.readFileSync(pointerPath, "utf8"));
    const hasInline = ptr.baselines && Object.keys(ptr.baselines).length > 0;
    const hasFallback = ptr.fallback_baselines && Object.keys(ptr.fallback_baselines).length > 0;
    if (ptr.current_release) {
      const rp = path.join(path.dirname(pointerPath), ptr.current_release);
      if (fs.existsSync(rp) && JSON.parse(fs.readFileSync(rp, "utf8")).status === "superseded") {
        failures.push("relevance-ratchet pointer selects a superseded release");
      }
      if (!fs.existsSync(rp) && !hasFallback && !hasInline) {
        failures.push(
          `ratchet pointer current_release=${ptr.current_release} does not exist and there is no ` +
            `fallback_baselines — the gate would have no floors. Compose a release or add a fallback.`
        );
      }
    } else if (!hasInline && !hasFallback) {
      failures.push("ratchet pointer has neither current_release nor inline/fallback baselines.");
    }
  }

  // 2b. Perf-ratchet pointer well-formed (tempdoc 640 — the perf-metric-family sibling).
  // Absent is fine (additive / pre-merge worktrees); when present it must parse, carry the
  // expected schema, and have a `baselines` object (empty {} is OK — un-pinned corpora do not gate).
  const perfPointerPath = path.join(root, "scripts", "jseval", "perf-ratchet-baselines.v1.json");
  if (fs.existsSync(perfPointerPath)) {
    let perf = null;
    try {
      perf = JSON.parse(fs.readFileSync(perfPointerPath, "utf8"));
    } catch (e) {
      failures.push(`perf-ratchet pointer is not valid JSON: ${e.message}`);
    }
    if (perf) {
      if (perf.schema !== "perf-ratchet-baseline.v1") {
        failures.push(`perf-ratchet pointer schema is '${perf.schema}' (want perf-ratchet-baseline.v1).`);
      }
      // tempdoc 640 #1: perf is now a POINTER (current_release projection) like relevance — validate
      // it the same way (2a), not as a required inline `baselines` object.
      const perfHasInline = perf.baselines && Object.keys(perf.baselines).length > 0;
      const perfHasFallback = perf.fallback_baselines && Object.keys(perf.fallback_baselines).length > 0;
      if (perf.current_release) {
        const prp = path.join(path.dirname(perfPointerPath), perf.current_release);
        if (!fs.existsSync(prp) && !perfHasFallback && !perfHasInline) {
          failures.push(
            `perf-ratchet pointer current_release=${perf.current_release} does not exist and there is ` +
              `no fallback_baselines — the gate would have no floors.`
          );
        }
      } else if (!perfHasInline && !perfHasFallback) {
        failures.push("perf-ratchet pointer has neither current_release nor inline/fallback baselines.");
      }
    }
  } else {
    console.log("check-release-baseline-sync: no perf-ratchet-baselines.v1.json yet — perf pointer check skipped.");
  }

  // 3. Register scorecard in sync (delegate).
  try {
    execFileSync("node", [path.join(root, "scripts", "docs", "register-headline-sync.mjs"), "--check"], {
      stdio: "pipe",
    });
  } catch (e) {
    failures.push(
      "register Release Scorecard is out of date — run: node scripts/docs/register-headline-sync.mjs\n" +
        (e.stderr ? "    " + e.stderr.toString().trim() : "")
    );
  }

  // 4. Public-facing benchmark table(s) in sync (delegate — tempdoc 633 #2).
  try {
    execFileSync("node", [path.join(root, "scripts", "docs", "gen-public-benchmark.mjs"), "--check"], {
      stdio: "pipe",
    });
  } catch (e) {
    failures.push(
      "public benchmark table is out of date — run: node scripts/docs/gen-public-benchmark.mjs\n" +
        (e.stderr ? "    " + e.stderr.toString().trim() : "")
    );
  }

  if (failures.length) {
    console.error("check-release-baseline-sync: FAIL");
    for (const f of failures) console.error("  - " + f);
    process.exitCode = 1;
  } else {
    console.log("check-release-baseline-sync: OK");
  }
}

main();
