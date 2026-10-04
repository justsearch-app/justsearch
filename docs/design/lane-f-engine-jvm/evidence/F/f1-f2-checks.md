# Stage F F-1/F-2 verification - 2026-09-30

Scope: residue checker and canonical docs, plus required generated projections. D2 remains post-merge work. No Gradle, backend, commit or push was run.

Tested base: `7648f7122d9b8a93d4509869f1330c53925b8272` plus the listed uncommitted working-tree changes, branch `codex/lane-f-f-docs`, Windows / Node v24.12.0.

## Checks

| Check | Result |
| --- | --- |
| Checker self-test | PASS; unlabelled fixture exits 1, labelled fixture omitted |
| Deliberately broken historical label | RED, exit 1; historical fixture incorrectly reported |
| Restored label rule | GREEN; self-test passes |
| Checker --paths docs | PASS, exit 0, zero stdout hits; residue-grep.txt is intentionally empty |
| docs-validate.mjs | PASS |
| verify-canonical-doc-links.mjs | PASS (157 files) |
| llmstxt-generate.mjs then --check | PASS (116 docs) |
| skills-sync.mjs then --check | PASS (5 generated skills, 9 sources) |
| npx.cmd --offline markdownlint on changed docs | PASS |
| Runtime-config matrix tests | PASS, 6 tests |
| verify-runtime-config-matrix.mjs | PASS |
| git diff --check | PASS |
| LC_ALL=C.UTF-8 git diff with added-line non-ASCII grep | PASS, no output (grep exits 1 for no match) |

## Source evidence

- Composition, index teardown, component registration: modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java:18-34,443-467,488-585,940-999.
- Shutdown order: modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java:1643-1819; memoized shutdown and native quiescence: modules/app-engine/src/main/java/io/justsearch/app/engine/EngineShutdownSequence.java:135-297.
- Host supervisor: scripts/dev/lib/engine-supervisor.cjs:129-243 and modules/shell/src-tauri/src/supervisor.rs:43-53,407-420.
- G1 spawn flags: modules/shell/src-tauri/src/lib.rs:788 and scripts/dev/dev-runner.cjs:741.
- Boot selectors: modules/ui/src/main/java/io/justsearch/ui/api/routes/BootRoutes.java:82-106 (head default; worker 501; brain assembly query).
- Retained readiness identifiers: modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/ReadinessDimension.java:15-16; migrated reasons: LifecycleReasonCode.java:18-41.
- Extraction routing: modules/worker-services/src/main/java/io/justsearch/indexerworker/extract/RoutingExtractionSandbox.java:27 and ExtractionSandboxFactory.java:149-178; source-specific citations are recorded in the canonical extraction sections.
- Inference composition: modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/InferenceCompositionRoot.java:267-311,477-605,745-907; InferenceSurface.java:22-140,218-270; EncoderSet.java:18-25,63-97,133-205.
- Configuration tier removal: modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java:32-72,109-112.

Live rewritten claims were source-checked. Historical measurements remain labelled history. The workflow output_heartbeat identifier has no producer in this checkout and is labelled historical rather than claimed as current.

## Remaining outside-docs hits

The whole-tree checker exits 1 with 1413 unlabelled matching lines outside docs. These include retained identifiers and live non-process uses of broad terms, as well as stale architecture prose; they require the later batches, not automatic deletion.

Full path:line:text output: `tmp/lane-f-outside-docs-residue.txt` in this worktree. Retain until the later F batches consume it; regenerate with `node scripts/ci/check-lane-f-residue.mjs`.

| Area | Hits |
| --- | --- |
| .agents | 17 |
| .claude | 2 |
| build-logic | 1 |
| config | 1 |
| contracts | 2 |
| gates | 14 |
| governance | 22 |
| gradle | 36 |
| models | 17 |
| modules | 1076 |
| root files | 3 |
| scripts | 221 |
| SSOT | 1 |

The hand-maintained .agents skill copies were reviewed for drift and remain assigned to F-3. Old inference-composition filename mentions left in dated design/inventory/capture history are historical references; all active links and the consult-register pointer use the new filename.

## Files changed (added / removed lines)

| File | Added | Removed |
| --- | --- | --- |
| .claude/skills/inference-runtime/SKILL.md | 35 | 35 |
| .claude/skills/installer/SKILL.md | 21 | 13 |
| .claude/skills/jseval/SKILL.md | 53 | 84 |
| .claude/skills/module-arch/SKILL.md | 88 | 249 |
| .claude/skills/search-quality/SKILL.md | 15 | 15 |
| docs/decisions/0002-grpc-mmf-hybrid-ipc.md | 2 | 2 |
| docs/decisions/0003-direct-lucene-no-elasticsearch.md | 3 | 3 |
| docs/decisions/0004-single-tenant-gpu-policy.md | 8 | 8 |
| docs/decisions/0009-custom-dag-engine-ci-orchestration.md | 1 | 1 |
| docs/decisions/0017-ai-bridge-module-decomposition.md | 1 | 1 |
| docs/decisions/0021-build-stamp-content-hash.md | 4 | 4 |
| docs/decisions/0025-core-dto-dual-type-layering.md | 12 | 14 |
| docs/decisions/0028-scoped-reverse-path-lookup.md | 1 | 1 |
| docs/decisions/0037-universal-sse-envelope.md | 2 | 2 |
| docs/decisions/0041-catalog-category-format.md | 7 | 4 |
| docs/decisions/0048-extraction-isolation-and-indexing-pacing.md | 22 | 23 |
| docs/decisions/0049-one-engine-jvm-and-the-boundaries-that-survive.md | 21 | 11 |
| docs/decisions/README.md | 1 | 1 |
| docs/design/lane-f-engine-jvm/evidence/F/residue-grep.txt | 0 | 0 |
| docs/design/lane-f-engine-jvm/stages/F.md | 11 | 1 |
| docs/explanation/01-system-overview.md | 7 | 5 |
| docs/explanation/02-process-coordination.md | 90 | 386 |
| docs/explanation/03-knowledge-server.md | 29 | 27 |
| docs/explanation/05-ai-architecture.md | 28 | 28 |
| docs/explanation/07-ui-host-architecture.md | 19 | 19 |
| docs/explanation/08-observability.md | 56 | 52 |
| docs/explanation/11-index-schema-migration.md | 4 | 3 |
| docs/explanation/12-desktop-installer-and-sandbox-setup.md | 21 | 13 |
| docs/explanation/17-ai-bridge-deep-dive.md | 1 | 1 |
| docs/explanation/19-module-architecture.md | 87 | 248 |
| docs/explanation/23-search-pipeline-overview.md | 13 | 13 |
| docs/explanation/24-engine-inference-composition.md | 100 | 0 |
| docs/explanation/24-worker-inference-composition.md | 0 | 349 |
| docs/explanation/25-service-lifecycle-pattern.md | 2 | 2 |
| docs/how-to/spawn-isolated-test-backend.md | 9 | 27 |
| docs/how-to/use-codex-for-development.md | 1 | 1 |
| docs/llms.txt | 7 | 7 |
| docs/reference/api-contract-map.md | 50 | 30 |
| docs/reference/architectural-risks.md | 3 | 3 |
| docs/reference/configuration/environment-variables.md | 21 | 21 |
| docs/reference/configuration/runtime-config-ownership-matrix.md | 6 | 2 |
| docs/reference/contracts/workflow-telemetry-contract.v1.md | 1 | 1 |
| docs/reference/contributing/agent-guide.md | 2 | 2 |
| docs/reference/contributing/agent-postmortems.md | 2 | 2 |
| docs/reference/contributing/common-workflows.md | 3 | 3 |
| docs/reference/contributing/mcp-dev-tools.md | 4 | 3 |
| docs/reference/index-schema-mismatch-reindex-noop.md | 7 | 7 |
| docs/reference/inference-runtime-register.md | 6 | 6 |
| docs/reference/jseval-pipeline-reference.md | 53 | 84 |
| docs/reference/model-inventory.md | 3 | 3 |
| docs/reference/search-quality-register.md | 2 | 2 |
| docs/reference/security/threat-model.md | 2 | 2 |
| docs/reference/ui/frontend-kernel/kernel/01-runtime-contracts.md | 1 | 2 |
| docs/reference/ui/frontend-kernel/kernel/05-streaming-envelope.md | 4 | 4 |
| docs/runbook/index-start-error.md | 10 | 10 |
| docs/runbook/index-unavailable.md | 27 | 21 |
| docs/ui-explorations/2026-08-06-brand-identity/brief.md | 1 | 1 |
| governance/consult-register.v1.json | 2 | 2 |
| scripts/ci/check-lane-f-residue.mjs | 128 | 0 |
| scripts/ci/test-check-lane-f-residue.mjs | 51 | 0 |
| scripts/docs/runtime-config-matrix-lib.mjs | 5 | 1 |
| docs/design/lane-f-engine-jvm/evidence/F/f1-f2-checks.md | 128 | 0 |
