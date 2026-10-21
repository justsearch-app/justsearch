---
title: "ADR-0018: VLM PDF Extraction via Chat Model"
type: decision
status: stable
description: "Reuse the chat model's vision path, without a Docling sidecar, for visual extraction of PDFs and images; on by default as the second fallback tier."
date: 2026-03-23
probes:
  - adr-0018-layout-flag-absent
last_reviewed: 2026-10-20
---

# ADR-0018: VLM PDF Extraction via Chat Model

## Status
Accepted. GPU sharing and extraction pacing are subsequently governed by
[ADR-0048](0048-extraction-isolation-and-indexing-pacing.md) and
[ADR-0050](0050-gpu-co-residence-by-prevention.md).

## Context
Tika extracts text from PDFs but loses layout and table structure. Complex PDFs (scientific papers, financial reports) need structural understanding for quality retrieval. The system already runs a chat model (Qwen 3.5 VLM with mmproj) via llama-server for AI features.

Options considered:
- Dedicated OCR model (olmOCR-2, RolmOCR)
- Dedicated layout detector
- Docling Python sidecar (8.4 GB dependency)
- Reusing the existing chat model's vision capability

Pages are flagged for VDU processing by `TextQualityAnalyzer` when the quality score falls below 0.3, indicating poor text extraction (e.g., scanned pages, complex table layouts, image-heavy content).

## Decision
Use Vision Language Model (VLM) extraction via the existing chat model path (Qwen 3.5 + mmproj) for PDF layout extraction. Configuration: plain-text prompt, 100 DPI, temp=0, JPEG format. Gated behind `JUSTSEARCH_LAYOUT_ENABLED=true` (disabled by default). The VDU pipeline processes pages flagged by `TextQualityAnalyzer` with quality score < 0.3.

This approach reuses the existing llama-server infrastructure and mmproj vision projection, requiring zero new dependencies.

## Consequences

### Positive
- **Zero new dependencies**: Reuses existing llama-server + mmproj. No Python runtime, no new model downloads, no sidecar lifecycle management.
- **Higher quality**: VLM achieves 81.9% word overlap vs Docling's 71.5% on a 50-page stratified sample. VLM wins 30/50 pages in head-to-head comparison.
- **Deterministic output**: Verified by independent rerun (Run 1 confirmed by rerun 1v) -- same input produces same output at temp=0.
- **Consistent architecture**: Follows the existing pattern of routing work through llama-server rather than adding external processes.

### Negative
- **Requires Brain process**: VLM extraction is only available when the Brain (llama-server) is running. Not available in headless/CPU-only deployments without LLM.
- **Processing speed**: ~0.25s/page on CPU. Acceptable for the selective pipeline (only flagged pages), but not suitable for bulk full-document OCR.
- **GPU contention**: VDU processing competes with chat/generation for llama-server capacity. Mitigated by the Head-side VDU pacing policy (`VduPacingPolicy`) and selective page flagging.

## Alternatives Considered

### Docling Python sidecar
Full-featured document understanding library with layout detection, table extraction, and OCR.

- **Pros**: Mature library, dedicated to document understanding, handles diverse document types.
- **Cons**: 8.4 GB dependency, Python subprocess lifecycle management, lower quality on this benchmark (71.5% vs 81.9% word overlap).
- **Rejected because**: The dependency size and sidecar complexity are disproportionate to the quality gain (which is actually negative -- VLM outperforms Docling).

### Dedicated OCR model (olmOCR-2, RolmOCR)
Specialized vision models trained specifically for document OCR.

- **Pros**: Purpose-built for OCR, potentially higher throughput.
- **Cons**: Cannot share llama-server -- would need a second inference server process, separate model management, and VRAM partitioning on the single GPU.
- **Rejected because**: Running two inference servers on a single consumer GPU (8-12 GB VRAM target) is impractical.

### Text-only LLM cleanup pass
Send extracted text through the LLM to fix formatting and structure.

- **Pros**: No vision capability needed, works with any LLM.
- **Cons**: LLMs hallucinate content not present in the original document. The model cannot "see" what was lost during text extraction, so it invents plausible but incorrect content.
- **Rejected because**: Hallucination risk is unacceptable for a retrieval system where factual accuracy matters.

### Document anchoring (visual-text alignment)
Align visual layout features with extracted text spans to reconstruct structure.

- **Pros**: Preserves exact text from extraction, uses vision only for layout.
- **Cons**: Over-engineered for the observed quality gap. The VLM approach is simpler and produces better results.
- **Rejected because**: The complexity is not justified given that straightforward VLM extraction already outperforms alternatives.

## Amendment 2026-09-02: routing is default-on, tiered, and not PDF-only

Re-examined under decision-review lane B (tempdoc 884). Three claims in the Decision section
above are false against `main`. The decision itself — reuse the chat model's vision path rather
than adding a Docling sidecar — still holds; its *gating, scope and trigger* do not.

### 1. `JUSTSEARCH_LAYOUT_ENABLED` does not exist. Do not look for it.

The Decision section says VLM extraction is *"Gated behind `JUSTSEARCH_LAYOUT_ENABLED=true`
(disabled by default)"*. That flag has **zero occurrences in shipped code** —
`grep -rn JUSTSEARCH_LAYOUT_ENABLED modules/` returns 0 matches. It is not in `EnvRegistry`, not
in any Java, Kotlin, TypeScript, Rust or JSON source. Either it never shipped or it was removed
without amending this ADR; the surviving mentions were prose only. Probe
`adr-0018-layout-flag-absent` now keeps that absence honest: re-introducing the name fails the
`adr-coverage` gate and forces this ADR to be re-decided rather than quietly re-gated.

There is **no enable flag** for VDU/VLM routing. It is on by default.

### 2. Routing runs unconditionally on every extracted document

`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/IndexingDocumentOps.java:196`
calls `markVduIfNeeded(...)` on the ordinary field-building path for every extracted document, and
`markVduIfNeeded` (`:746-762`) calls `VisualRoutingDecision.decide(...)` (`:754-755`) with no
guard. The decision writes `vdu_status` / `vdu_demand_kind` fields on every document; what varies
is the *reason*, not whether the router ran.

### 3. Scope is VDU-eligible files, not PDFs only

`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecision.java:20-21`
declares `VDU_ELIGIBLE_EXTENSIONS = Set.of(".pdf", ".png", ".jpg", ".jpeg", ".tiff", ".bmp", ".gif")`,
matched by suffix in `isVduEligible` (`:100-106`). Anything else short-circuits to
`notNeeded("ineligible")` (`:44-46`). The ADR's title and body say "PDF"; the shipped scope is PDF
plus the common raster image formats.

### 4. The `< 0.3` quality score is one branch of a tiered fallback, not the trigger

`VisualRoutingDecision.decide` is a tier-2 fallback decision, in this order:

1. **Ineligible extension** → `notNeeded("ineligible")` (`:44-46`).
2. **Budget check.** VDU/VLM is extraction fallback **tier 2**; when the budget stops at tier 1
   the router refuses regardless of the text — `notNeeded("fallback_budget_spent")` at `:57`,
   `:73` and `:86`. The comment at `:51-52` states the rule: *"Tempdoc 790 item 2: VDU/VLM is
   fallback tier 2. A budget that stops at tier 1 must not queue it, whatever the text looks
   like."*
3. **Extraction dropout** → the strongest demand, `"extraction_dropout"` (`:60-66`), including
   after OCR.
4. **OCR result with a visual-enrichment signal** → `"visual_enrichment_signal"` (`:69-78`);
   an OCR result without one is `notNeeded("ocr_baseline_sufficient")` (`:80`).
5. **Quality-score gate** — only here does the threshold apply:
   `qualityScore < vduQualityThreshold || pagesMissingReadableText(evidence) > 0` (`:84`) →
   `"baseline_text_missing"`. Otherwise `notNeeded("structured_baseline_sufficient")` (`:93`).

### 5. The 0.3 threshold survives, and is configurable

The number in the ADR is still right, and it is now a named configuration key rather than a
constant: `modules/configuration/src/main/java/io/justsearch/configuration/EnvRegistry.java:587-588`
declares `VDU_QUALITY_THRESHOLD("justsearch.vdu.quality_threshold", "JUSTSEARCH_VDU_QUALITY_THRESHOLD")`,
and `IndexingDocumentOps.java:730-731` applies the default and clamps it to `0..1`
(`EnvRegistry.VDU_QUALITY_THRESHOLD.getDouble(0.3)`).

### Consequences that change

The Negative bullet *"only flagged pages"* still holds, but the flagging population is larger than
this ADR assumed (images as well as PDFs, and dropout/enrichment demands as well as low quality
scores) — so the GPU-contention consequence is correspondingly larger. The tier-2 budget, not a
feature flag, is what bounds it.

## Amendment 2026-10-20: GPU and VDU pacing after co-residence shipped

The GPU-contention consequence above predates
[ADR-0048](0048-extraction-isolation-and-indexing-pacing.md) and
[ADR-0050](0050-gpu-co-residence-by-prevention.md). In addition to
`VduPacingPolicy`, `VduProcessor.asVduBulkWork` marks vision and chat passes
with `DemandScope.bulk(BulkWorkKind.VDU)`. `OnlineAiServiceImpl` classifies
these as bulk VDU demand, so VDU's own generation is not counted as chat
answering. The original default-off, PDF-only decision remains historical;
the 2026-09-02 amendment describes the current default-on PDF/image routing.
Evidence: `modules/app-services/src/main/java/io/justsearch/app/services/vdu/VduProcessor.java`
(`asVduBulkWork`, vision-completion calls),
`modules/app-inference/src/main/java/io/justsearch/app/inference/OnlineAiServiceImpl.java`
(`declaredLeaseKind`), and
`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecision.java`.
