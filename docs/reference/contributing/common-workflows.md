---
title: Common Workflows
type: reference
status: stable
description: "Step-by-step recipes for recurring JustSearch contribution tasks — add a gRPC method / REST endpoint / configuration key / frontend component / agent tool; modify SSOT catalogs; add a field to an API record or registry declaration; run test suites; regenerate after doc edits. Relocated out of the always-loaded layer (tempdoc 620 Phase 2); the path-triggerable recipes are also delivered just-in-time via governance/consult-register.v1.json."
---

# Common Workflows

On-demand recipes for recurring contribution tasks. This file is **not** loaded
every session (tempdoc 620 residence relocation) — load it when you start one of
these tasks. The four path-triggerable recipes (gRPC, REST, config key, agent
tool) are *also* registered for just-in-time consultation in
`governance/consult-register.v1.json` when you edit the relevant region; this
file is the full reference behind those recipes.

## Add a gRPC method
1. Define in `modules/ipc-common/src/main/proto/indexing.proto`
2. Implement service method in `modules/worker-services/`
3. ~~Add forward in `DelegatingIngestService.java`~~ — **no longer a step.** Lane F stage A item A9 deleted the gRPC server and its three `Delegating*Service` wrappers; callers reach the impl directly through `KnowledgeServer.appServices()`, so adding a method to `WorkerIngestService` is all there is.
4. Add client call in `modules/app-services/` (`KnowledgeClient`, or relevant client)
5. Add contract test
6. Verify: `./gradlew.bat :modules:ipc-common:build :modules:worker-services:test :modules:indexer-worker:test`

## Add a REST endpoint
1. Add handler/controller in `modules/ui/src/main/java/.../api/`
2. Register the route through the relevant `modules/ui/src/main/java/io/justsearch/ui/api/routes/*Routes.java` class, then wire that route class from `LocalApiServer.java` if needed
3. Update `docs/reference/api-contract-map.md` if public
4. Verify: `./gradlew.bat :modules:ui:test`

## Add a configuration key
1. Add entry in `EnvRegistry.java` (operator-facing) or `ConfigKey.java` (YAML-only)
2. Wire in `ResolvedConfigBuilder.java` — add `putYaml*()` contribution + `resolve*()` call in `build*()`
3. Expose via `ResolvedConfig` record field
4. Document in `docs/reference/configuration/environment-variables.md`
5. Verify: `./gradlew.bat :modules:configuration:test`

## Add a frontend component
Load `/ui-check` for visual verification via `jseval ui-shot`.
1. Create in `modules/ui-web/src/components/` or appropriate subdirectory
2. Follow patterns in neighboring components
3. Verify: `cd modules/ui-web && npm run typecheck && npm run test:unit:run`

**Changed a `modules/ui-web` dependency?** The gradle web build runs `npm ci`
(reproducible, never rewrites `package-lock.json` — tempdoc 618 §2), so it will
**fail** if `package.json` and `package-lock.json` drift. Regenerate the lock
explicitly with `cd modules/ui-web && npm install`, then commit the updated
`package-lock.json`. To live-validate a worktree's FE in a browser, run
`node scripts/dev/serve-worktree-fe.cjs` (§7).

## Add an agent tool
1. Add or update the operation entry in `AgentToolsOperationCatalog` (including its `OperationPolicy` — risk tier, confirm strategy, audit policy) and implement it as an `OperationHandler`. For a new knowledge/file-shaped tool, these are the same file pair in the same package, `modules/app-agent/src/main/java/io/justsearch/agent/tools/`. When the work isn't tool-shaped — as `core.remember` and `core.navigate-to-surface` show — the handler instead goes under `modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/handlers/`
2. Confirm `AgentToolEmitter` projects the intended model-visible wire name and schema; a deliberate schema/policy change also requires updating `modules/app-services/src/test/resources/agent-tools-wire-baseline.json` (pinned by `AgentToolCatalogBaselineTest`)
3. Update `docs/explanation/22-agent-system-architecture.md`
4. Verify: `./gradlew.bat :modules:app-agent:test :modules:app-services:test`

## Modify SSOT catalogs (fields, analyzers, synonyms)
Load `/ssot-catalog` for the dual-copy checklist and field role reference.
1. Edit JSON in `SSOT/catalogs/`
2. **If adding fields**: also update the classpath copy at `modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json` — production loads this when the repo root is unavailable
3. **If adding fields with extraction logic**: update `IndexingDocumentOps.java` to populate the new field during ingestion. Existing indices will NOT have the new field — test corpora must be re-indexed (`jseval run --reset` or `--start-backend --clean`). `--clean` is fail-closed (tempdoc 711 item 4): it verifies the wipe actually completed (sweeping an orphaned Worker JVM if a delete is blocked) and raises rather than silently re-running against a stale index if a survivor remains — see `docs/reference/jseval-pipeline-reference.md`
4. Regenerate fingerprints if needed
5. Verify pinned-hash tests: `./gradlew.bat :modules:ssot-tools:test`
6. Check commit metadata compatibility: `./gradlew.bat :modules:adapters-lucene:test`

## Run specific test suites
- Single module: `./gradlew.bat :modules:<module>:test`
- Full unit suite: `./gradlew.bat test`
- Frontend unit: `cd modules/ui-web && npm run test:unit:run`
- Frontend typecheck: `cd modules/ui-web && npm run typecheck`
- System/integration: `./gradlew.bat :modules:system-tests:test`
- Format check: `./gradlew.bat spotlessCheck`
- Format fix: `./gradlew.bat spotlessApply`

## Add a field to an API record

**The caveat that bites first:** `KnowledgeSearchController.handleSearch()` builds its
response `Map` by hand, key by key (`modules/ui/src/main/java/io/justsearch/ui/api/KnowledgeSearchController.java`).
Adding a component to the record does **not** change API output until that map gains an
explicit `put()`. `KnowledgeSearchResponseContractTest` (in `app-api`) compares
`KnowledgeSearchResponse`'s record components against the controller's mapped-field set and
fails the build on omission — so for that one record the trap is gate-caught; for any other
record it is not.

Records in `app-api` (e.g., `KnowledgeSearchResponse`, `WorkerDebugView`)
use `@RecordBuilder` to generate fluent builders. When adding a field:

1. Add the field to the record's parameter list
2. If the field is a collection type, add null-coalescing in the compact
   constructor (e.g., `myList = myList == null ? List.of() : List.copyOf(myList)`)
3. Compile: `./gradlew.bat :modules:app-api:compileJava` — the builder
   regenerates automatically
4. Update the production construction site (e.g., `KnowledgeHttpApiAdapter`)
   to pass the new field
5. **Builder-based test callsites need no update** — the builder defaults
   new fields to `null`/`0`/`false`, so existing builder calls compile
   unchanged. Note: some records and nested types (e.g., `Hit` production
   callsite in `KnowledgeHttpApiAdapter`) still use positional constructors
   and will need manual updates. Check `@RecordBuilder` annotation presence.
6. If the record is part of the API contract, update the controller
   (`KnowledgeSearchController`) to include the field in the response map
7. Regenerate schemas and fixtures: `./gradlew.bat :modules:app-api:updateSchemas`
8. If the record is an FE wire surface, it is a generated record→JSON-Schema→{TS,Zod}
   projection (tempdoc 564). Emit/refresh its JSON Schema via the owning module's
   `updateSchemas` task (`:modules:app-api:updateSchemas` for app-api records;
   `:modules:app-observability:updateSchemas` for `HealthEvent`), then regenerate the FE
   types: `node scripts/codegen/gen-wire-schema-types.mjs` (add a `TARGETS` entry + a
   `governance/contract-surfaces.v1.json` row for a NEW surface). The parallel
   `wire-types.ts` (typescript-generator) path was retired in 564 Phase 4.
9. If the record is part of `/api/status` or `/api/knowledge/search`, the FE validates the
   raw wire at the parse boundary via `parseWireContract(<generated schema>, …)` (non
   fail-open). New fields flow through the generated schema-types; do not hand-author a
   second `.loose()` Zod (the `wire-type-single-authority` gate refuses a hand copy).
10. Run frontend contract tests: `cd modules/ui-web && npm run test:unit:run`
11. Verify: `./gradlew.bat :modules:app-api:test :modules:app-agent:test :modules:app-observability:test`

`status-v1.json`, `knowledge-status-v1.json`, and `debug-state-v1.json` are deliberately
manual backward-compatibility baselines. An ordinary field **addition** must not touch them;
update them only for a breaking change (field removal or rename).

## Add a field to a registry declaration (Operation / Resource)
The `/api/registry/{operations,resources}` wire is a generated projection of a typed
**wire view** record, not the domain record directly (tempdoc 560 §4c). Adding a field:
1. Add it to the domain record (`Operation`/`Resource` in `modules/app-agent-api/.../registry/`)
2. Add the matching field on the typed wire view (`UIOperationView`/`UIResourceView`) and map it
   in the emitter (`UIOperationEmitter`/`UIResourceEmitter`). Mark nullability on the wire view:
   `Optional<>` or `@Nullable` for present-as-null; `@JsonInclude(NON_NULL)` for omitted-when-absent;
   plain for required + non-null (the `PreciseWire` rule drives the generated `required`/nullable).
3. Regenerate + sync the schema: `./gradlew.bat :modules:app-api:updateSchemas` then
   `./gradlew.bat :modules:ui:syncSsotSchemas`, then `node scripts/codegen/gen-wire-schema-types.mjs`
4. The FE re-exports/derives from the generated wire in `modules/ui-web/src/api/types/registry.ts`
   (derive nested types from the wire — do not hand-mirror); update fixtures the precise types flag.
5. Verify: the `UI{Operation,Resource}ViewConformanceTest` pins the wire byte-for-byte;
   `cd modules/ui-web && npm run typecheck && npm run test:unit:run`; the `contract-projection` +
   `wire-type-single-authority` gates.

## After modifying docs
Load `/docs-maintenance` for the full regeneration checklist and doc quality rules.
- Regenerate llms.txt: `node scripts/docs/llmstxt-generate.mjs`
- Refresh canonical documentation embedded in Claude skills: `node scripts/docs/skills-sync.mjs`; then manually review the corresponding Codex skills when the shared workflow or source material changed.
- After changing cross-harness invariants: `node scripts/docs/agent-instructions-sync.mjs`
- After changing hook bindings: `node scripts/ci/regen-all.mjs --only agent-hooks-wiring,codex-hooks`. The Claude generator refreshes tracked `.claude/settings.json` and `.claude/settings.local.json.example`; `--check` verifies both without creating ignored local state.
- After module changes: `node scripts/architecture/module-deps.mjs --update-canonical`
- After config changes: `node scripts/docs/generate-runtime-config-matrix.mjs --write-doc docs/reference/configuration/runtime-config-ownership-matrix.md`

## Worktree mechanics (relocated from `.claude/rules/branch-safety.md` — tempdoc 681)

The always-loaded rule file keeps the hard rules and a compact creation recipe; the full
mechanics live here.

**Config-file seeding.** `.claude/settings.local.json` and `.mcp.json` are gitignored
(maintainer-local — they carry a GitHub PAT / a permissive local security posture), **not**
git-tracked. Whether a new worktree starts with them depends on whether your base checkout had
them at creation time — don't rely on it. `node scripts/dev/prepare-worktree.cjs` seeds any
missing one from its committed `.example` file (never overwriting an existing copy), so it is
always safe to run. `--no-dist` skips the Java dists (FE-only prep). See `MAINTAINING.md`.

**Removing a registered worktree.** Run the removal tool from the owning repository root, with
the shell's current directory outside the target. Preview the exact registered target first:

```powershell
node scripts/dev/remove-worktree.cjs <registered-path> --dry-run
node scripts/dev/remove-worktree.cjs <registered-path> --allow-ignored --delete-branch --session-id SESSION_ID
```

Git registration is the authority, so linked worktrees may live under any parent directory and
use arbitrary branch names; paths with spaces or Unicode and detached worktrees are supported.
The tool refuses the main worktree, path aliases, nested registrations, locks, tracked/staged or
untracked changes, and target-related runtime or helper state that is live or cannot be proven
safe. Ignored paths are inventoried in the preview and require the explicit `--allow-ignored`
option for removal. Preview performs no ref, telemetry, helper, register, or filesystem writes.

Removal unlinks junctions link-only, deletes the validated tree, and then removes only that exact
Git registration. `--delete-branch` deletes the captured local branch only when it still names the
captured HEAD and no worktree uses it; detached HEAD skips branch deletion. The tool does not infer
merge state or provide a general force bypass. Editor/task discovery is necessarily incomplete,
and a process can start before its registration write becomes visible, so close target-scoped
tools and tasks before removal; an observed in-progress registration write blocks teardown.

Merge attribution is opt-in. A known explicit `--session-id` lets teardown use a supplied
`--merge-commit` or query the branch's merged PR, then invoke `record-merge.mjs` with that exact
session. Omit `--session-id`, or pass the supported `unknown` sentinel, for unattributed cleanup;
both forms skip the merged-PR lookup and telemetry writer. Environment variables, the
`current-session-id` pointer, and the worktree hash remain helper caller-identity fallbacks, but
they never establish merge attribution during teardown.

**Shared models / runtime resolution.** The dev-runner resolves `JUSTSEARCH_MODELS_DIR` from the
**main** checkout automatically (tempdoc 618 §2). Runtime resolution is **GPU-only by design as
of tempdoc 656** (supersedes 618 §3's CPU-baseline auto-stage): the dev-runner resolves a
**shared cuda12** llama-server — the worktree's own `native-bin/llama-server/variants/cuda12/`
if deliberately Install-AI'd there, else the **main checkout's** shared cuda12 — and provisions
the main checkout's copy once from the Gradle cuda stage if absent
(`./gradlew :modules:ui:stageLlamaCudaVariant`, a one-time ~600 MB download). Every worktree then
references that one shared copy with zero per-worktree download. Dev does not stage or fall back
to a CPU llama-server baseline — a CPU 9B fallback runs ~10x slower and saturates every core,
DOSing concurrent worktrees (tempdoc 381, 656) — so with no cuda12 resolvable, inference fails
CLOSED (truthful "unavailable" via the runtime manifest's reason codes) instead of silently
degrading onto CPU. See `resolveCuda12ServerExe` / `stageSharedCuda12` in
`scripts/dev/dev-runner.cjs` and the regression test
`scripts/dev/test-dev-runner-runtime-resolution.mjs`.

**Backends started outside the dev-runner** (e.g. a bare `gradlew runHeadlessEval`) get neither
`JUSTSEARCH_MODELS_DIR` nor `JUSTSEARCH_SERVER_EXE` set automatically and must export both:

```text
JUSTSEARCH_MODELS_DIR=F:\JustSearch\models
JUSTSEARCH_SERVER_EXE=F:\JustSearch\modules\ui\native-bin\llama-server\variants\cuda12\llama-server.exe
```

**Completing an incomplete ORT CUDA native pack (GPU ONNX inference).**
`OrtCudaHelper.applyOrtNativePackProperty` (tempdoc 772 §J) points ORT at
`tmp/ort-variant-test/<variant>` only when that dir is COMPLETE — all four DLLs in
`OrtCudaHelper.ORT_NATIVE_DLL_SET` (`onnxruntime.dll`, `onnxruntime4j_jni.dll`,
`onnxruntime_providers_shared.dll`, `onnxruntime_providers_cuda.dll`) plus the
`ort-native-version.txt` marker. A pre-772 layout carrying only the provider/cuDNN DLLs fails that
check and ORT silently falls back to CPU, killing GPU inference for every ONNX eval run. To
complete such a pack, run `node scripts/dev/restage-ort-pack.mjs [<packDir>]` (default packDir
`tmp/ort-variant-test/cuda-12.4-v1.24.3`): it validates the dir, extracts any missing DLL from the
gradle-cache `onnxruntime_gpu-<version>.jar` (found under
`<GRADLE_USER_HOME>/caches/modules-2/files-2.1/com.microsoft.onnxruntime/onnxruntime_gpu/<version>/`,
version read from `OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION`), writes the marker, and exits nonzero
if it cannot complete the pack. It never deletes anything. If the jar is absent, run any build that
resolves `onnxruntime_gpu` first (e.g. `./gradlew.bat :modules:ort-common:dependencies`) to populate
the cache.
