# 958 — ORT optimized-model cache: one owned, content-keyed, bounded store

Status: design + plan (2026-10-03). Branch `codex/ort-optimized-cache` (from lane F integration
`8b83b1d94`); publish as its own PR after PR #727 merges. Registers: `/inference-runtime` (read;
update at closure).

## 1. Problem (owner report 2026-10-03, recurring for months)

Disk tools showed ~700 GB of ONNX files. Two producers, different ages:

| Producer | Since | Mechanism |
|---|---|---|
| A. `OnnxSessionCache` (`modules/ort-common`) | initial public release (2026-06-25) | Writes the optimized graph **beside whatever model path is loaded**: `<model>.optimized` + `.opt-meta` (CPU) and `<model>.cuda.optimized` + `.cuda.opt-meta` (CUDA), via `SessionOptions.setOptimizedModelFilePath` (`OnnxSessionCache.java:29-32,87,207-212`). Validity is a sidecar beside the model (mtime + size + ORT version). Every new copy, hard-link tree, data dir or temp dir has no sidecar, so it re-optimizes and writes another full-size real file (gte-multilingual-base: 1.25 GB per variant; FP16-on-CPU first optimization 5-10 min, `05-ai-architecture.md`). Nothing ever deletes these. |
| B. Lane-F recovery fixtures | 2026-09-23 | Per-run `installer-models` hard-link trees under `tmp/` never removed (fixed in `8b83b1d94`: `pruneInstallerModelTrees`). The 09-27 `prune-model-caches.mjs` patched producer A's symptom for one fixture path only. |

Producer A is the long-standing root: the cache's location is a property of the *loaded path*, not
of the *model content*, so cache count scales with the number of places a model is loaded from.
`governance/store-recoverability.v1.json:1034` already records these as unregistered app-generated
caches under `models/**` ("splitting is the open option").

## 2. Decision

The optimized graph is a per-machine derived artifact of **(model bytes, ORT version, execution
provider, optimization level)**. It is owned by one store, keyed by that tuple, outside every
model directory, and bounded.

- **Location:** `PlatformPaths.getPlatformDefault()/cache/ort-optimized/` (Windows
  `%LOCALAPPDATA%\JustSearch\cache\ort-optimized`). Deliberately *not* the data dir: data dirs are
  per run/test/worktree, which is exactly the multiplier. Override: one config key
  (`justsearch.ort.optimized_cache_dir`, registered through the existing configuration catalog and
  its gates); tests and CI point it at a temp root.
- **Key:** `sha256(model bytes)` + `ort` version + EP tag (`cpu` | `cuda`) + optimization level.
  Content hash via the existing `Sha256SidecarCache` (move it from `worker-core` to the lowest
  module both `ort-common` and its current callers can depend on, rather than writing a second
  hasher). Entry layout: `<root>/<ortVersion>/<ep>-<optLevel>/<sha256>/model.onnx` + `entry.json`
  (source size, created, last-used).
- **Write protocol:** ORT writes to a unique temp path inside the entry directory; on success the
  file is atomically moved into place and `entry.json` written last. A reader loads an entry only
  when `entry.json` exists. Two JVMs racing on the same key may both optimize; the second move is a
  no-op. No lock, no partial-file reads.
- **Bound:** total-size cap (default 16 GiB, config key `justsearch.ort.optimized_cache_max_mb`);
  least-recently-used entries evicted after a successful write. Entries for other ORT versions are
  deleted at first use of the store in a JVM.
- **Models with external data** (`*.onnx_data` or external initializers): not cached (optimize in
  memory, no file). None ship today; correctness over speed if one appears.
- **Legacy cleanup:** on resolving a model, delete exactly its four legacy sibling names
  (`.optimized`, `.opt-meta`, `.cuda.optimized`, `.cuda.opt-meta`) if present — nothing else, no
  globbing, refusing symlinks/junctions.
- **Retired with it:** `DevModeVariantProbe`'s beside-model `.optimized` existence checks (ask the
  store instead); `pruneRegenerableModelCaches` in `prune-model-caches.mjs` and its callers
  (fixtures no longer get caches; `pruneInstallerModelTrees` stays); the store-recoverability
  `derivedCacheNote` (replace with a registered regenerable store row for the new root).

Rejected: keep caches beside models and prune them (symptom-level, every new producer re-leaks);
per-data-dir cache (still multiplies per run); sampled hash (same-architecture fine-tunes share
size and header bytes); mtime/size key (copies and links differ in path, not identity, and a model
swap with preserved mtime is undetectable).

## 3. Acceptance

1. Loading the same model from two directories (a byte copy and a hard link) produces exactly one
   cache entry and writes **nothing** beside either model (unit, `ort-common`).
2. Changing model bytes, ORT version, EP or optimization level selects a different entry (unit).
3. Concurrent creators of one key leave one committed entry and no temp files (unit).
4. Over-cap writes evict least-recently-used entries; other-version entries are removed (unit).
5. Legacy sibling cleanup removes exactly the four names and refuses links (unit).
6. Gates: `check-store-recoverability`, config-surface/governance gates touched by the new keys,
   `module-deps --check-canonical`, `docs-validate`, `regen-all --check`, `spotlessCheck`, `pmdAll`,
   `UnreferencedCodeTest`, `ClosurePropertyTest`, `:modules:ort-common:test`, the stress-tagged
   session tests (register rule for `NativeSessionHandle` changes).
7. Live (dev stack, standard profile): two starts with **different data dirs** — the second logs
   loading the pre-optimized graph from the store; `find` shows no `*.optimized` under `models/`,
   data dirs or `tmp/`; cold-start encoder readiness not slower than before.
8. Canonical docs updated: `05-ai-architecture.md`, `24-worker-inference-composition.md` if it
   names the location, `01-system-overview.md`, `test-gpu-locally.md`, ADR-0019 references, the
   `/inference-runtime` register (new decision entry), both skill trees regenerated.

## 4. Plan and delegation

Constraint: the lane F final E queue runs until ~19:30 on 2026-10-03 and needs a quiet machine.
Until it finishes, workers edit and read only (no Gradle, no javac, no tests, no stack); all
verification runs afterwards in the orchestrator.

| Chunk | Owner | Scope | Worktree |
|---|---|---|---|
| W1 product store | Codex builder-strong | `OnnxSessionCache` rewrite onto the store, hasher move, config keys, `DevModeVariantProbe`, legacy cleanup, unit tests 3.1-3.5, store-recoverability row | `ort-optimized-cache` |
| W2 fixture retirement | Codex builder (after W1 lands, small) | retire `pruneRegenerableModelCaches` + callers, keep tree prune, adjust its tests | same branch, after W1 |
| W3 docs | Codex builder (parallel with W2) | canonical docs + register entry + skills regen | same branch |
| Review | Codex challenger per chunk | refute-first against §2/§3 | read-only |
| Verify | orchestrator, after E | gates §3.6, live §3.7, register update | `ort-optimized-cache` |

## 5. Related, not in scope

- `%TEMP%` holds 14,569 `junit-*` directories (2026-10-03): JUnit `@TempDir` cleanup is failing for
  some suites on Windows (likely open file handles). Small today; separate follow-up.
