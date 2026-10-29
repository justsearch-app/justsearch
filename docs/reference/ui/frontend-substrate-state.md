---
title: "Frontend substrate-state map"
type: reference
status: stable
description: "What frontend framework substrate exists, where it shipped, its current consumer state, and the named user-visible consumer gaps. Graduated 2026-06-09 from the retired 421 kernel draft's R5.1 register (snapshot current through tempdoc 564)."
date: 2026-06-09
---

# Frontend substrate-state map

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft's living inventory (its R5.1 register; the rewrite shipped per tempdoc 563). This is the
> one durable artifact from that inventory — the map of *what substrate exists, where it shipped,
> what consumes it, and the named gap*. It is a **dated snapshot** (current through tempdoc 564, with
> the 565/567 additions noted below); verify any specific row against `main` + the cited tempdoc before
> relying on it. The draft's strategy prose, the F/G candidate-feature wishlist, and the refresh/history
> logs were dated history and were retired with the draft (preserved in git).

## Substrate slot inventory

The frontend framework's shipped substrate, with consumer state. "F#/G#" gaps refer to the
candidate-feature ids the retired draft tracked (now historical; see git history for the catalog).

| Substrate slot | Where shipped | Consumer state | Named user-visible consumer gap |
|---|---|---|---|
| `Audience` axis on Operation / Resource / Prompt | slice 481 | Wire shape only. FE `currentAudience` substrate absent. SurfaceAreaValidator startup-warning is a token consumer. | F4 (filter UX). |
| `consumers: List<ConsumerHook>` | slice 481 | Wire shape only. SurfaceConsumerIndex auto-derives. No enforcement consumer. | None — internal correctness. |
| `OperationLineage.affects` | slice 481 | One non-empty value (`core.rebuild-index`). Wire-projected. | F3 (pre-invoke confirm), F1 (palette). |
| `OperationAvailability.expression` (bounded AST) | slice 447 + 481 | Empty defaults everywhere. No declared values. | F1 (smart-suggestions). |
| `core.condition-recovery-index` (STATE×SSE_STREAM) | slice 447 | Producer + HealthSurface SSE consumer. Palette consumer absent. | F1 (palette consumer of recovery suggestions). |
| Prompt `LANGUAGE_WORKFLOW` substrate | proposed slice 437 | Empty (`PromptCatalog.of("core", List.of())`); no Category enum until F2 lands. | F2 (and the F2 cascade). |
| Plugin V1.5.1 contribution paths | shipped | `applyContribution` wired; production callsites absent for some merge functions. | F5, F11, F12 (real-plugin consumers). |
| User-authored persistent state (`UserStateDocument`) | shipped (migration ladder) | Partial — surfaceOrder / visibility / activeTheme + custom themes (567). localStorage-only. | F18, G1, G2, G14, G15, G17, G38. |
| Agent-session continuity | shipped (linear history) | `AgentRunStore` durable, schema migrations, append-only checkpoints. **Absent**: Letta-style three-tier memory; causal-DAG trace. | G20, G50 (memory); G61, G62 (causal-DAG). |
| Search-side substrate depth | shipped (narrow) | `FacetingEngine` internal; `BooleanQuery` Builder; no field-qualified syntax, multi-collection, time-travel, regex. | G3–G10, F24, G63 (full bundle). |
| Operation chaining | shipped (single Operation) | `OperationDispatcher` invokes one Operation; per-Operation `RetryPolicy` — enforced by the agent dispatcher since tempdoc 879 (it was declaration-only before, while a hard-coded `risk == LOW` decided retries). No Workflow/Sequence record. | F13, F25, G24, G52, G133. |
| Intent substrate (Source / Transport / Handler) | slice 487 + 492 | `IntentSource` + `NavigationHandler` + `InvocationHandler` + trust lattice + `URLSource` + `URLOperationEmitter` + `MCPIntentSource`. | F8, F13, G47, G54, G116–G127. |
| URL routing substrate (`justsearch://` grammar) | slice 489 | route-mapper present; `SurfaceCatalog` shipped. | G118, G120, G123, G124. |
| Conversation / chat substrate (`ConversationShape` + 4 SPIs) | slice 491 + 496 + 497 | 8 shapes shipped; `ConversationStore` (JSONL); unified `/api/chat/dispatch`; Search (`views/search-v3/SearchV3View.ts`, `<jf-sv3-window>` / `<jf-sv3-composer>`), with Search, Ask, Structured and Delegate; compatibility addresses prepare the same window. | F6, F9, G132, G133, G141, G142, G143, G162, G163. |
| Proactive emission substrate (`KIND_ADVISORY` + `EmissionPolicy` + `InvocationProvenance`) | slice 490 | 1 advisory class shipped; FE chrome discovers advisory Resources generically; dedupe enforced; `signedIntentToken` reserved. | F23, G37, G44, G47, G55, G75, G83. |
| Composition substrate (`HeadAssembly` + `BootTrace` + sealed-sum `PhaseOutcome` + `Memoized<T>` + `RebuildHistory` + `BrainAssembly`) | tempdoc 541 (merged `f4b403d2c`) | `GET /api/boot/phases`; `<jf-boot-phases-panel>` in LogSurface; ArchUnit composition-root rules. | None cataloged — lifecycle observability + discipline. |
| Canonical search-execution record (`SearchTrace` projection; execution-surface register + drift gate) | tempdoc 549 + 551 + 553 | **G33 + G111 shipped** — explain panel + always-on "Why this result?" + LLM narration. | None user-visible remaining. |
| Presentation projection (`present()`→`DisplayLabel` / `token()`→`TokenName` / landmark / a11y / operability / adaptivity facets; the `SurfaceFactory` engine) | tempdoc 557 + 558 + 559 | Facets project from declarations; FE gates enforce. **Element-catalog end-state**: reference-implemented on one region (569), designed to the collection row (566), producer side shipped (567). | Cluster-H substrate + chrome. |
| Theme-authoring producer authority (seeds∪roles unrepresentability; persisted `DesignTokenTree` custom themes; role/contrast-floor derivation) | tempdoc 567 (on main) | User-creatable/persisted custom themes; per-mode authoring; WCAG-floor foreground derivation. | The theming consumer (shipped). |
| Agent-window content + composition authorities (grounded-answer / local-passage citations; status→tone; one ordered run; `composeGridStyles` generated composition) | tempdoc 565 (on main) | Agent answers carry verifiable clickable local-passage citations; semantic status; generated answer-first three-zone composition; `run-renderers` + `composition-surfaces` registers. | Agent-mode grounded-answer consumer (shipped). |
| Extension substrate, completed (single-authority contribution projection + delivery) | tempdoc 560 | Deepens the plugin/contribution substrate; gated on the first real plugin (tempdoc 533). | F5, F11, F12. |
| Agent-action lifecycle + LLM-interaction record (`Intent`+`Provenance`; Preview / Authorize / Outcome faces) | tempdoc 550 + 561 | **G119 receipt shipped**; 550's Preview evaluated `Operation.availability`; 561 eliminated the agent-plane double-write. | F23 advisor surface; G138 full Preview UI. |
| Contract source-of-truth projection (**record-as-IDL**: Java record → JSON-Schema → {TS, Zod}; proto demoted to a derived view) | tempdoc 564 | Wire types project from one generated authority across ~16 surfaces; non-fail-open `parseWireContract`; `wire-type-single-authority` lint + `contract-projection` gate. | None user-visible — internal correctness. |

## See also

- The FE architecture decisions this substrate realizes: ADRs `0031`–`0041` (`docs/decisions/`).
- The FE kernel contracts: `docs/reference/ui/frontend-kernel/kernel/`.
- The presentation-projection lineage: tempdocs 557 / 558 / 559 / 565 / 566 / 567 / 569.
