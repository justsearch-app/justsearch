/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.core.context.EngineContext;

import io.justsearch.aibackend.backend.EngineCircuitBreaker;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.gpu.VramRequirements;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.indexing.SchemaFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Batch processor for VDU files during offline time.
 *
 * <p>Called by OfflineCoordinator when there are pending VDU files.
 * Runs with LLM loaded (Online Mode).
 *
 * <p><b>Architecture:</b> Main process runs VDU (vision completion via LLM),
 * then updates index via gRPC to Worker (which owns IndexWriter).
 */
public class VduBatchProcessor {
  private static final EngineContext ENGINE_CONTEXT = io.justsearch.app.services.intent.EngineProvenance.internal(
      "vdu-batch-processor", EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);

    private static final Logger LOG = LoggerFactory.getLogger(VduBatchProcessor.class);

    private final VduProcessor vduProcessor;
    private final GpuCapabilitiesService gpuCapabilitiesService;
    // Tempdoc 672: live supplier, not a captured value — see OfflineCoordinator's field javadoc.
    private final Supplier<KnowledgeClient> knowledgeClientSupplier;
    private final VduMetricCatalog catalog;
    private final VduCapabilityState vduCapabilityState;
    // Tempdoc 672 follow-up: cooperative-checkpoint interrupt signal, checked between documents
    // in the batch loop — mirrors EmbeddingBackfillOps.checkInterrupt()'s shape on the Worker
    // side. Composed by the caller (ServicePhase) from activity/energy/LLM-exclusivity signals;
    // this class only needs "should I stop now", not the individual signal sources.
    private final java.util.function.BooleanSupplier shouldInterruptBatch;

    // Circuit breaker to prevent hammering dead LLM (5 failures, 1 minute recovery)
    private final EngineCircuitBreaker circuitBreaker = new EngineCircuitBreaker(5, Duration.ofMinutes(1));

    /**
     * Creates a VduBatchProcessor without telemetry (for backward compatibility).
     */
    public VduBatchProcessor(VduProcessor vduProcessor,
                             GpuCapabilitiesService gpuCapabilitiesService,
                             Supplier<KnowledgeClient> knowledgeClientSupplier) {
        this(vduProcessor, gpuCapabilitiesService, knowledgeClientSupplier, VduMetricCatalog.noop(),
            new VduCapabilityState());
    }

    /**
     * Creates a VduBatchProcessor with observability catalog.
     *
     * <p>Tempdoc 374 alpha.27: VRAM probe routes through {@link GpuCapabilitiesService}
     * (NVML-first) instead of the legacy {@code VramDetector} (nvidia-smi only). Pre-fix,
     * cuda12 sandbox hosts where NVML works fine but nvidia-smi isn't on PATH silently
     * failed {@link #processPendingFiles}'s VRAM gate, disabling VDU even though the
     * GPU was healthy.
     *
     * @param vduProcessor processor for individual VDU files
     * @param gpuCapabilitiesService NVML-first capability snapshot service
     * @param knowledgeClientSupplier live supplier of the gRPC client for Worker communication
     * @param catalog VDU metric catalog
     */
    public VduBatchProcessor(VduProcessor vduProcessor,
                             GpuCapabilitiesService gpuCapabilitiesService,
                             Supplier<KnowledgeClient> knowledgeClientSupplier,
                             VduMetricCatalog catalog) {
        this(vduProcessor, gpuCapabilitiesService, knowledgeClientSupplier, catalog, new VduCapabilityState());
    }

    public VduBatchProcessor(VduProcessor vduProcessor,
                             GpuCapabilitiesService gpuCapabilitiesService,
                             Supplier<KnowledgeClient> knowledgeClientSupplier,
                             VduMetricCatalog catalog,
                             VduCapabilityState vduCapabilityState) {
        this(vduProcessor, gpuCapabilitiesService, knowledgeClientSupplier, catalog,
            vduCapabilityState, () -> false);
    }

    /**
     * Full constructor. Tempdoc 672 follow-up: {@code shouldInterruptBatch} is checked between
     * documents in {@link #processPendingFiles()} so an in-progress batch stops early if the user
     * becomes active mid-run, leaving remaining documents PENDING for the next idle window.
     *
     * @param shouldInterruptBatch cooperative-checkpoint interrupt signal; {@code () -> false}
     *     (never interrupt) for callers that don't wire idle/energy arbitration
     */
    public VduBatchProcessor(VduProcessor vduProcessor,
                             GpuCapabilitiesService gpuCapabilitiesService,
                             Supplier<KnowledgeClient> knowledgeClientSupplier,
                             VduMetricCatalog catalog,
                             VduCapabilityState vduCapabilityState,
                             java.util.function.BooleanSupplier shouldInterruptBatch) {
        this.vduProcessor = vduProcessor;
        this.gpuCapabilitiesService = gpuCapabilitiesService;
        this.knowledgeClientSupplier = knowledgeClientSupplier;
        this.catalog = catalog != null ? catalog : VduMetricCatalog.noop();
        this.vduCapabilityState =
            vduCapabilityState != null ? vduCapabilityState : new VduCapabilityState();
        this.shouldInterruptBatch = shouldInterruptBatch != null ? shouldInterruptBatch : () -> false;
    }

    // Counter recording helpers
    private void recordCompleted() {
        catalog.outcomeTotal.increment(VduOutcomeTags.of(VduOutcome.COMPLETED));
    }

    private void recordEmpty() {
        catalog.outcomeTotal.increment(VduOutcomeTags.of(VduOutcome.EMPTY));
    }

    // Tempdoc 417 Phase 2e: drops the unbounded "reason" exception-message tag (cardinality bug).
    // Exception details continue to be logged via slf4j at the same callsite (see callers).
    // F10: signature simplified — `reason` parameter was dead.
    private void recordFailed() {
        catalog.outcomeTotal.increment(VduOutcomeTags.of(VduOutcome.FAILED));
    }

    private void recordSkipped() {
        catalog.outcomeTotal.increment(VduOutcomeTags.of(VduOutcome.SKIPPED));
    }

    // Tempdoc 677: the abstention gate rejection is counted as a distinct FAILED-bucket outcome
    // (see VduOutcome — no dedicated REJECTED tag exists yet, and adding one is outside this
    // slice's scope). recordFailed()'s existing tag is reused rather than adding a new enum
    // constant purely for this call site.
    private void recordRejected() {
        recordFailed();
    }

    public int processPendingFiles() {
        KnowledgeClient knowledgeClient = knowledgeClientSupplier.get();
        if (knowledgeClient == null) {
            LOG.info("VDU batch processing skipped: Worker not connected yet");
            return 0;
        }
        int pendingCount = knowledgeClient.countPendingVdu(ENGINE_CONTEXT);
        if (pendingCount == 0) {
            LOG.info("No pending VDU files");
            vduCapabilityState.clearAll();
            return 0;
        }

        // Tempdoc 374 alpha.27: NVML-first probe via GpuCapabilitiesService. Pre-fix
        // VramDetector.meetsVduRequirements() shelled out to nvidia-smi and returned
        // false on cuda12 sandbox hosts where NVML works fine — VDU was silently
        // disabled even though VRAM was 12 GB.
        Long vramBytes = gpuCapabilitiesService.snapshot().effective().totalVramBytes();
        if (!VramRequirements.meetsGgufRequirements(vramBytes)) {
            LOG.warn("VDU batch processing skipped: insufficient VRAM ({})",
                VramRequirements.describe(vramBytes));
            vduCapabilityState.block(VduCapabilityState.REASON_INSUFFICIENT_VRAM);
            return 0;
        }
        vduCapabilityState.clear(VduCapabilityState.REASON_INSUFFICIENT_VRAM);

        if (!vduProcessor.hasVisionCapability()) {
            LOG.warn("VDU batch processing skipped: missing vision projector (mmproj)");
            vduCapabilityState.block(VduCapabilityState.REASON_MISSING_MMPROJ);
            return 0;
        }
        vduCapabilityState.clear(VduCapabilityState.REASON_MISSING_MMPROJ);

        List<String> pendingDocIds = knowledgeClient.queryPendingVduDocIds(ENGINE_CONTEXT);
        if (pendingDocIds.isEmpty()) {
            LOG.info("No pending VDU doc IDs returned");
            vduCapabilityState.clearAll();
            return 0;
        }

        LOG.info("Processing {} pending VDU files", pendingDocIds.size());

        // Tempdoc 672 follow-up: VDU mode is entered/exited once for the whole batch, not once
        // per document — each transition is a full llama-server restart (~10-12s). A failure to
        // enter also now fails the whole batch immediately instead of repeating the same failed
        // restart for every remaining document.
        try {
            vduProcessor.enterVduMode();
        } catch (VduProcessor.VduException e) {
            LOG.error("Failed to enter VDU mode for batch; skipping {} pending files",
                pendingDocIds.size(), e);
            vduCapabilityState.block(VduCapabilityState.REASON_AI_OFFLINE);
            return 0;
        }
        vduCapabilityState.clear(VduCapabilityState.REASON_AI_OFFLINE);

        int processed = 0;
        int failed = 0;

        try {
        for (String docId : pendingDocIds) {
            // Tempdoc 672 follow-up: cooperative-checkpoint interrupt — mirrors
            // EmbeddingBackfillOps.checkInterrupt() on the Worker side. Checked between
            // documents so the user regaining activity (or the OS requesting reduced energy use)
            // stops the batch early instead of grinding through the rest of the queue.
            if (shouldInterruptBatch.getAsBoolean()) {
                int remaining = pendingDocIds.size() - processed - failed;
                LOG.info("VDU batch interrupted (user active or energy-reduced), leaving {} docs PENDING",
                    remaining);
                break;
            }

            // Circuit breaker check - fast-fail if LLM is repeatedly failing
            if (!circuitBreaker.isClosed()) {
                int remaining = pendingDocIds.size() - processed - failed;
                LOG.warn("VDU circuit breaker OPEN, skipping remaining {} docs (reason: {})",
                    remaining, circuitBreaker.tripReason());
                vduCapabilityState.block(VduCapabilityState.REASON_CIRCUIT_OPEN);
                break;
            }
            vduCapabilityState.clear(VduCapabilityState.REASON_CIRCUIT_OPEN);

            try {
                // Mark as PROCESSING with retry count increment (poison pill protection)
                int retryCount = knowledgeClient.markVduProcessing(docId, SchemaFields.VDU_MAX_RETRIES, ENGINE_CONTEXT);
                if (retryCount < 0) {
                    LOG.warn("VDU skipped (max retries exceeded or error): {}", docId);
                    recordSkipped();
                    failed++;
                    continue;
                }

                LOG.debug("VDU processing attempt {}/{} for: {}",
                    retryCount, SchemaFields.VDU_MAX_RETRIES, docId);

                Path filePath = Path.of(docId);

                if (!Files.exists(filePath)) {
                    LOG.warn("VDU file no longer exists: {}", docId);
                    markVduFailed(knowledgeClient, docId, "File no longer exists");
                    recordFailed();
                    failed++;
                    continue;
                }

                VduProcessor.VduResult result = vduProcessor.process(filePath);
                circuitBreaker.recordSuccess();  // LLM call succeeded

                // P0.4: Use explicit VduUpdateOutcome to distinguish SUCCESS_TEXT vs SUCCESS_EMPTY vs FAILED.
                // This avoids misleading "COMPLETED but empty" states where content was never updated.
                String extractedText = result.extractedText();
                boolean hasText = extractedText != null && !extractedText.isBlank();
                GateVerdict gateVerdict = result.gateVerdict();

                io.justsearch.ipc.VduUpdateOutcome outcome;
                String enrichment;
                String contentForWire;
                if (gateVerdict.rejected()) {
                    // Tempdoc 677: the abstention gate judged this document's output untrustworthy
                    // (Stage 0: no page carried any input-legibility signal, or Stage 1: the
                    // model's own logprob/finish-reason signals were suspect). Checked BEFORE
                    // hasText so a Stage 0 rejection (no model call, extractedText is blank) is
                    // still reported as REJECTED_SUSPECT_TEXT rather than falling through to the
                    // SUCCESS_EMPTY branch below.
                    outcome = io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT;
                    // Omit the model's suspect text from the wire entirely — the worker ignores
                    // extracted_content for this outcome anyway (baseline is retained), and
                    // omitting it keeps a possibly-fabricated string out of the payload.
                    contentForWire = null;
                    enrichment = buildGateRejectionEnrichment(gateVerdict, result.pageCount());
                    LOG.info("VDU output rejected by abstention gate (stage={}) for: {}",
                        gateVerdict.stage(), docId);
                } else if (hasText) {
                    outcome = io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT;
                    contentForWire = extractedText;
                    enrichment = result.enrichment();
                } else {
                    // VDU succeeded but produced no text (e.g., blank image, handwriting)
                    outcome = io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY;
                    contentForWire = null;
                    enrichment = buildNoTextEnrichment(result.pageCount(), result.enrichment());
                    LOG.info("VDU produced no text for: {} (pageCount={})", docId, result.pageCount());
                }

                boolean updated = knowledgeClient.updateVduResult(
                    docId,
                    contentForWire,
                    outcome,
                    enrichment,
                    result.pageCount()
                , ENGINE_CONTEXT);

                if (updated) {
                    if (gateVerdict.rejected()) {
                        recordRejected();
                        failed++;
                        LOG.info("VDU rejected ({}/{}): {}",
                            failed, pendingDocIds.size(), filePath.getFileName());
                    } else if (hasText) {
                        recordCompleted();
                        processed++;
                        LOG.info("VDU completed ({}/{}): {}",
                            processed, pendingDocIds.size(), filePath.getFileName());
                    } else {
                        // SUCCESS_EMPTY: VDU ran successfully but no usable text; count separately
                        recordEmpty();
                        failed++;
                        LOG.info("VDU completed (no text) ({}/{}): {}",
                            failed, pendingDocIds.size(), filePath.getFileName());
                    }
                } else {
                    LOG.warn("VDU update failed for: {}", docId);
                    recordFailed();
                    failed++;
                }

            } catch (VduProcessor.VduException e) {
                LOG.error("VDU processing failed for: {}", docId, e);
                circuitBreaker.recordFailure(e);  // Track LLM failures
                markVduFailed(knowledgeClient, docId, e.getMessage());
                recordFailed();
                failed++;
            } catch (Exception e) {
                LOG.error("Unexpected error processing: {}", docId, e);
                circuitBreaker.recordFailure(e);  // Track LLM failures
                markVduFailed(knowledgeClient, docId, "Unexpected error: " + e.getMessage());
                recordFailed();
                failed++;
            }
        }
        } finally {
            vduProcessor.exitVduMode();
        }

        LOG.info("VDU batch complete: {} processed, {} failed", processed, failed);
        return processed;
    }

    private void markVduFailed(KnowledgeClient knowledgeClient, String docId, String reason) {
        try {
            knowledgeClient.updateVduResult(
                docId,
                null,
                io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED,
                buildErrorEnrichment(reason),
                0
            , ENGINE_CONTEXT);
        } catch (Exception e) {
            LOG.warn("Failed to mark VDU failed for: {}", docId, e);
        }
    }

    /**
     * Builds a machine-readable JSON enrichment for "no text detected" VDU outcomes.
     *
     * @param pageCount the page count from VDU (may be 0 if unknown)
     * @param originalEnrichment the original enrichment from VduResult (may contain partial data)
     * @return JSON string with error code and metadata
     */
    private String buildNoTextEnrichment(int pageCount, String originalEnrichment) {
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            var node = mapper.createObjectNode();
            node.put("error", "no_text_detected");
            if (pageCount > 0) {
                node.put("pageCount", pageCount);
            }
            // Preserve any original enrichment data under "original" key
            if (originalEnrichment != null && !originalEnrichment.isBlank()) {
                try {
                    var originalNode = mapper.readTree(originalEnrichment);
                    node.set("original", originalNode);
                } catch (Exception ignored) {
                    // If original enrichment isn't valid JSON, store as string
                    node.put("originalRaw", originalEnrichment);
                }
            }
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            // Fallback to simple string if JSON building fails
            return "{\"error\":\"no_text_detected\",\"pageCount\":" + pageCount + "}";
        }
    }

    /**
     * Builds the {@code vdu_enrichment} JSON for a document the tempdoc 677 abstention gate
     * rejected — the gate's evidence trail under a {@code "gate"} key, so the rejection is
     * auditable (which stage, which signals tripped it) rather than a bare status flag. Fields
     * that are {@code null} on {@code verdict} (not applicable to the rejecting stage, or NO
     * SIGNAL from the server) are omitted from the JSON entirely via explicit null-guards below,
     * rather than written as a JSON {@code null}, to keep the evidence trail free of noise.
     *
     * @param verdict the rejecting gate verdict ({@link GateVerdict#rejected()} must be true)
     * @param pageCount the document's total page count
     * @return JSON string: {@code {"gate": {"stage", "meanLogprob", "lowConfidenceFraction",
     *     "tokenCount", "finishReason", "laplacianVariance", "rmsContrast", "agreement",
     *     "probedPage"}, "pageCount"}}
     */
    private String buildGateRejectionEnrichment(GateVerdict verdict, int pageCount) {
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            var gateNode = mapper.createObjectNode();
            gateNode.put("stage", verdict.stage());
            if (verdict.meanLogprob() != null) {
                gateNode.put("meanLogprob", verdict.meanLogprob());
            }
            if (verdict.lowConfidenceFraction() != null) {
                gateNode.put("lowConfidenceFraction", verdict.lowConfidenceFraction());
            }
            if (verdict.tokenCount() != null) {
                gateNode.put("tokenCount", verdict.tokenCount());
            }
            if (verdict.finishReason() != null) {
                gateNode.put("finishReason", verdict.finishReason());
            }
            if (verdict.laplacianVariance() != null) {
                gateNode.put("laplacianVariance", verdict.laplacianVariance());
            }
            if (verdict.rmsContrast() != null) {
                gateNode.put("rmsContrast", verdict.rmsContrast());
            }
            // Tempdoc 677 Stage 2 evidence — present only for a stage="agreement" rejection.
            if (verdict.agreement() != null) {
                gateNode.put("agreement", verdict.agreement());
            }
            if (verdict.probedPage() != null) {
                gateNode.put("probedPage", verdict.probedPage());
            }

            var node = mapper.createObjectNode();
            node.set("gate", gateNode);
            if (pageCount > 0) {
                node.put("pageCount", pageCount);
            }
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            // Fallback to a minimal but still-honest string if JSON building fails.
            return "{\"gate\":{\"stage\":\"" + verdict.stage() + "\"},\"pageCount\":" + pageCount + "}";
        }
    }

    /**
     * Builds a machine-readable JSON enrichment for error conditions.
     *
     * @param reason the error reason
     * @return JSON string with error message
     */
    private String buildErrorEnrichment(String reason) {
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            var node = mapper.createObjectNode();
            node.put("error", reason != null ? reason : "unknown_error");
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            // Fallback: escape quotes manually
            String safeReason = (reason != null ? reason : "unknown_error").replace("\"", "'");
            return "{\"error\":\"" + safeReason + "\"}";
        }
    }
}
