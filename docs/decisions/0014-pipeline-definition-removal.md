---
title: "ADR-0014: Pipeline Definition and Schema Removal"
type: decision
status: stable
description: "Remove pipeline DAG definitions, budget profiles, and pipeline-schema module."
date: 2026-03-16
probes: none - a removal decision; its premise is carried by the consumer-drift gate, whose slot-retraction classification cites ADR-0014 (scripts/governance/gates/consumer-drift/classifications.mjs).
last_reviewed: 2026-10-20
---

# ADR-0014: Pipeline Definition and Schema Removal

## Status

Accepted. See the 2026-10-20 amendment for surviving schema residue and retired prompt machinery.

## Context

ADR SSOT/0007 (Feb 2026) removed the pipeline execution engine (`modules/pipeline-engine`, `modules/pipeline-executor`, ~6,600 LOC) but retained the pipeline *definitions* (`SSOT/pipelines/*.json`), budget profiles, and the `modules/pipeline-schema` module for "structural validation, telemetry tagging, and the capabilities endpoint."

A subsequent evaluation (tempdoc 313) concluded that this retained infrastructure was dead weight:

- **Pipeline definitions describe 17 stages; the live code implements different logic.** `SearchOrchestrator` is a decision tree with fallback paths. `IndexingLoop` is a state machine with GPU lifecycle management. Neither follows the DAG.
- **`dag_hash` in commit metadata** was computed from a JSON artifact that doesn't govern runtime behavior — it provided false confidence that the index was built using a specific pipeline when no pipeline was ever executed.
- **Budget profiles** defined per-stage deadlines for stages that don't exist in the running code.
- **`modules/pipeline-schema`** (~450 LOC) existed solely to load and validate definitions that weren't executed.
- **Maintaining both the definitions and the live code** required asking "do I also need to update the SSOT pipeline definition?" for every search/indexing change — with the answer always being "technically yes, practically no."

## Decision

Remove all pipeline definition infrastructure:

- **Delete** `SSOT/pipelines/indexing.v1.json`, `SSOT/pipelines/search.v1.json`, `SSOT/pipelines/budget-profiles/`, `SSOT/artifacts/pipelines/`
- **Delete** `modules/pipeline-schema` entirely (PipelineDefinition, PipelineLoader, PipelineValidator, PipelineKind, EdgeDefinition, StageDefinition)
- **Delete** `SSOT/tools/resolve-pipeline.mjs` and the `ssotResolvePipeline` Gradle task
- **Delete** `BudgetProfiles`, `BudgetProfile`, `StageBudget` from the configuration module
- **Delete** `DagHashingService` and `JsonDagHashingService` from infra-core
- **Remove** `dag_hash` and `pipeline_budget_profile` from commit metadata (both production and schema)
- **Remove** `dag_hash` from parity diagnostics
- **Remove** `Launcher.simulate` command
- **Simplify** `CapabilitiesService` to expose prompt templates and schema versions only (no pipeline metadata)
- **Simplify** `WorkerConfig` to remove pipeline-related fields
- **Simplify** `IndexingLoop` to remove PipelineDefinition from constructors

**Not removed** (separate system): The prompt pipeline YAMLs (`intent_v1.yaml`, `embed_v1.yaml`, `classify_v1.yaml`, `summary_*.yaml`) loaded by `PromptPipelineLoader` in `ai-bridge`. These are an independent system with no relationship to the deleted pipeline-schema types.

## Rationale

- SSOT works well as a **data catalog** (fields, analyzers, version fingerprints) but failed as a **behavioral specification** (pipeline DAGs). The behavioral ambition was abandoned when the engine was deleted; the definitions should have been deleted at the same time.
- Every piece of removed infrastructure existed only to maintain definitions that didn't govern runtime behavior. The maintenance cost was real; the value was illusory.
- Commit metadata still contains `schema_fp`, `field_catalog_hash`, `analyzer_fp`, `synonyms_hash`, `grammar_hash`, `similarity_fp`, `boosts_fp`, and `index_schema_fp` — all of which reflect actual configuration that governs runtime behavior.

## Consequences

- `dag_hash` no longer appears in new index commits. Legacy indexes with `dag_hash` in their metadata are handled gracefully — it's simply ignored during parity checks.
- The `/api/capabilities` endpoint no longer exposes pipeline stage lists or budget summaries.
- The Node.js toolchain loses `resolve-pipeline.mjs` (one fewer script to maintain).
- ~450 LOC of Java code removed, ~200 lines of JSON definitions removed, ~100 lines of Gradle configuration removed.

## 2026-09-03 note (tempdoc 915)

The Rationale section above (2026-03-16) listed `schema_fp`, `field_catalog_hash`, `analyzer_fp`,
`synonyms_hash`, `grammar_hash`, `similarity_fp`, `boosts_fp`, and `index_schema_fp` as the commit
metadata surviving this removal. Tempdoc 915 later folded `schema_ver` (a separate key not listed
above, but present in commit metadata at the time), `index_schema_fp`, and `analyzer_fp` into a
single rebuild-requiring `index_fingerprint` key — see `docs/explanation/11-index-schema-migration.md`.
This is a downstream refinement of the commit-metadata surface described here, not a reversal of this
decision.

## Amendment 2026-10-20: removal is not complete in the metadata schema

The runtime pipeline-definition removal stands, but the original Decision's
claim to remove `dag_hash` and `pipeline_budget_profile` from the schema is not
fully implemented: both remain optional properties in
`SSOT/schemas/indexing/commit-metadata.schema.json`. Their presence in that
schema does not mean a DAG is executed. Removing the residual declarations is
a separate code/schema cleanup, not part of this documentation amendment.

The "Not removed" paragraph is also historical: `PromptPipelineLoader` is
absent from the current modules. The retained prompt-support module contains
`PromptTemplateLoader` and template types under
`modules/prompt-support/src/main/java/io/justsearch/prompts/`; it does not
restore the retired pipeline-definition system.
