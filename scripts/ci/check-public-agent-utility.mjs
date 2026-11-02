/**
 * Guard the immutable agent-utility publication pointer and outward projections.
 *
 * Pointer selection semantics (which manifest is CURRENTLY selected, and the null-state
 * transitions around that) are pointer-only concepts Python's `replay_publication` never sees —
 * it replays a single already-selected bundle. Everything replay DOES provably perform (full
 * manifest shape, per-file hash chain, and the record's accepted claim verdict) is delegated to
 * the real offline replay (`python -m jseval utility-replay`) below rather than re-implemented
 * here, so the two can never silently drift apart. See the deletion mapping against
 * `scripts/jseval/jseval/utility_publication.py::replay_publication` in the authoring changeset.
 * The recomputed question-level projection beside the publication is likewise checked by its own
 * offline recomputation (`python -m jseval.utility_public_projection --check`).
 */

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
// Test-only override (scripts/ci/check-public-agent-utility.test.mjs) so failure paths can be
// exercised against a fixture root without touching the real repo; CI never sets this, so bare
// invocation keeps resolving against REPO_ROOT unchanged.
const ROOT = process.env.CHECK_PUBLIC_AGENT_UTILITY_ROOT
  ? path.resolve(process.env.CHECK_PUBLIC_AGENT_UTILITY_ROOT)
  : REPO_ROOT;
const PUBLIC_ROOT = path.join(ROOT, "scripts", "jseval", "public-agent-utility");
const POINTER_PATH = path.join(PUBLIC_ROOT, "current.v1.json");

function fail(message) {
  console.error(`check-public-agent-utility: FAIL - ${message}`);
  process.exit(1);
}

function readJson(file, label) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (error) {
    fail(`${label} is missing or invalid JSON: ${error.message}`);
  }
}

function exactKeys(value, keys, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail(`${label} must be an object`);
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${label} keys differ: expected ${expected.join(", ")}; got ${actual.join(", ")}`);
  }
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function confined(base, relative, label, allowedBase = ROOT) {
  const resolved = path.resolve(base, relative);
  const prefix = path.resolve(allowedBase) + path.sep;
  if (!resolved.startsWith(prefix)) fail(`${label} escapes its allowed root`);
  return resolved;
}

/**
 * Pure formatter for the "offline replay could not be verified" failure — unit-testable in
 * isolation (scripts/ci/check-public-agent-utility.test.mjs) without spawning a real process.
 * `replay` is a `spawnSync` result: either `{ error }` (the interpreter itself could not start,
 * e.g. ENOENT) or `{ status, stdout, stderr }` (the interpreter ran and reported failure).
 */
export function missingPythonMessage(python, replay) {
  if (replay.error) {
    const code = replay.error.code || "unknown error";
    return (
      `offline replay could not start: '${python}' not found (${code}). ` +
      "Install Python 3.13+ and run: python -m pip install -e scripts/jseval " +
      "(or set PYTHON to the interpreter path)."
    );
  }
  const detail = replay.stderr || replay.stdout || "(no output captured)";
  return `offline replay failed (status ${replay.status}):\n${detail}`;
}

/** Validates the pointer's own shape/selection semantics; returns the parsed pointer on success. */
export function validatePointer({ pointerPath = POINTER_PATH, publicRoot = PUBLIC_ROOT, root = ROOT } = {}) {
  const pointer = readJson(pointerPath, "publication pointer");
  exactKeys(pointer, ["schema", "schema_version", "current", "previous", "reason", "selected_at"], "pointer");
  if (pointer.schema !== "agent-utility-publication-pointer.v1" || pointer.schema_version !== 1) {
    fail("unsupported publication pointer schema");
  }
  if (typeof pointer.reason !== "string" || pointer.reason.length === 0) fail("pointer reason must be non-empty");

  if (pointer.current !== null) {
    exactKeys(pointer.current, ["publication_id", "path", "manifest_sha256"], "pointer.current");
    const manifestPath = confined(publicRoot, pointer.current.path, "manifest path", root);
    if (!fs.existsSync(manifestPath)) fail("selected manifest does not exist");
    if (sha256(manifestPath) !== pointer.current.manifest_sha256) fail("selected manifest hash mismatch");

    // Only the pointer-selection identity is read here (which publication, and is it accepted) —
    // everything about the manifest's own internal shape and hash chain is Python's job below.
    const manifest = readJson(manifestPath, "publication manifest");
    if (
      manifest.publication_id !== pointer.current.publication_id ||
      manifest.lifecycle_state !== "accepted"
    ) {
      fail("pointer does not select the matching accepted publication");
    }

    const python = process.env.PYTHON || "python";
    const replay = spawnSync(
      python,
      ["-m", "jseval", "utility-replay", "--publication", manifestPath],
      { cwd: root, encoding: "utf8" },
    );
    if (replay.error || replay.status !== 0) fail(missingPythonMessage(python, replay));
    console.log(`check-public-agent-utility: replayed ${pointer.current.publication_id}`);

    // The recomputed question-level intervals shown beside the publication are statistical claims
    // of their own: recompute them offline from the replayed observations and require the committed
    // projection to match byte for byte, so a tampered interval cannot pass by regenerating the docs.
    if (fs.existsSync(path.join(publicRoot, "question-level.v1.json"))) {
      const recompute = spawnSync(
        python,
        ["-m", "jseval.utility_public_projection", "--check"],
        { cwd: root, encoding: "utf8", env: { ...process.env, JUSTSEARCH_REPO_ROOT: root } },
      );
      if (recompute.error) fail(missingPythonMessage(python, recompute));
      if (recompute.status !== 0) {
        fail(
          `question-level projection does not match its offline recomputation (status ${recompute.status}):\n` +
            (recompute.stderr || recompute.stdout || "(no output captured)"),
        );
      }
      console.log("check-public-agent-utility: question-level projection recomputed");
    }
  } else if (pointer.previous === null && pointer.selected_at !== null) {
    fail("the initial no-result pointer must have selected_at=null");
  } else if (pointer.previous !== null && pointer.selected_at === null) {
    fail("a withdrawn publication pointer must retain its transition timestamp");
  }
  return pointer;
}

function main() {
  const pointer = validatePointer();

  const projection = spawnSync(
    "node",
    [path.join(ROOT, "scripts", "docs", "gen-public-agent-utility.mjs"), "--check"],
    { cwd: ROOT, stdio: "inherit" },
  );
  if (projection.status !== 0) fail("public projections are stale or invalid");

  console.log(`check-public-agent-utility: OK (${pointer.current ? "accepted result replayed" : "no accepted result"})`);
}

// Run as CLI only (not when imported by the test). Basename check is robust cross-platform.
if (process.argv[1] && process.argv[1].replace(/\\/g, "/").endsWith("check-public-agent-utility.mjs")) {
  main();
}
