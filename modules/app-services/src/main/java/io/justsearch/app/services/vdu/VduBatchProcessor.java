/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineFutures;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.api.OfflineProcessingOutcome.EmbeddingHandoff;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

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
 * then updates the index through the Engine port (the index half owns IndexWriter).
 */
public class VduBatchProcessor {
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
     * documents in {@link #processPendingFiles} so an in-progress batch stops early if the user
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


    // Tempdoc 677: the abstention gate rejection is counted as a distinct FAILED-bucket outcome
    // (see VduOutcome — no dedicated REJECTED tag exists yet, and adding one is outside this
    // slice's scope). recordFailed()'s existing tag is reused rather than adding a new enum
    // constant purely for this call site.
    private void recordRejected() {
        recordFailed();
    }

    public OfflineProcessingOutcome processPendingFiles(
            EngineContext engineContext, Consumer<OfflineProcessingOutcome> progress) {
        Objects.requireNonNull(engineContext, "engineContext");
        Objects.requireNonNull(progress, "progress");
        checkInterrupted();
        KnowledgeClient knowledgeClient = knowledgeClientSupplier.get();
        if (knowledgeClient == null) {
            return report(progress, 0, 0, 0, BlockReason.WORKER_UNAVAILABLE);
        }
        // Capture the existing default bounded selection once. Later backlog is another pass.
        List<String> pendingDocIds = List.copyOf(knowledgeClient.queryPendingVduDocIds(engineContext));
        int selected = pendingDocIds.size();
        LOG.info("VDU pass captured {} documents", selected);
        report(progress, selected, 0, 0, BlockReason.NONE);
        if (selected == 0) {
            vduCapabilityState.clearAll();
            return report(progress, 0, 0, 0, BlockReason.NONE);
        }

        Long vramBytes = gpuCapabilitiesService.snapshot().effective().totalVramBytes();
        if (!VramRequirements.meetsGgufRequirements(vramBytes)) {
            vduCapabilityState.block(VduCapabilityState.REASON_INSUFFICIENT_VRAM);
            return report(progress, selected, 0, 0, BlockReason.INSUFFICIENT_VRAM);
        }
        vduCapabilityState.clear(VduCapabilityState.REASON_INSUFFICIENT_VRAM);
        if (!vduProcessor.hasVisionCapability()) {
            vduCapabilityState.block(VduCapabilityState.REASON_MISSING_MMPROJ);
            return report(progress, selected, 0, 0, BlockReason.MISSING_VISION);
        }
        vduCapabilityState.clear(VduCapabilityState.REASON_MISSING_MMPROJ);

        try {
            vduProcessor.enterVduMode();
        } catch (VduProcessor.VduException failure) {
            vduCapabilityState.block(VduCapabilityState.REASON_AI_OFFLINE);
            var modeFailure = new IllegalStateException("Failed to enter VDU mode", failure);
            try {
                report(progress, selected, 0, 0, BlockReason.AI_OFFLINE);
            } catch (RuntimeException checkpointFailure) {
                modeFailure.addSuppressed(checkpointFailure);
            } catch (Error fatal) {
                fatal.addSuppressed(modeFailure);
                throw fatal;
            }
            throw modeFailure;
        }
        vduCapabilityState.clear(VduCapabilityState.REASON_AI_OFFLINE);

        int processed = 0;
        int failed = 0;
        BlockReason blocked = BlockReason.NONE;
        Throwable primaryFailure = null;
        try {
            for (String docId : pendingDocIds) {
                checkInterrupted();
                if (shouldInterruptBatch.getAsBoolean()) {
                    blocked = BlockReason.ACTIVITY_OR_ENERGY;
                    break;
                }
                if (!circuitBreaker.isClosed()) {
                    vduCapabilityState.block(VduCapabilityState.REASON_CIRCUIT_OPEN);
                    blocked = BlockReason.CIRCUIT_OPEN;
                    break;
                }
                vduCapabilityState.clear(VduCapabilityState.REASON_CIRCUIT_OPEN);

                int retryCount = knowledgeClient.markVduProcessing(
                    docId, SchemaFields.VDU_MAX_RETRIES, engineContext);
                if (retryCount < 0) {
                    // The legacy scalar cannot distinguish a missing parent from retry exhaustion.
                    // Do not invent a committed failed-unit acknowledgement from that ambiguity.
                    blocked = BlockReason.PROCESSING_REFUSED;
                    break;
                }

                VduProcessor.VduResult result;
                try {
                    checkInterrupted();
                    Path filePath = Path.of(docId);
                    if (!Files.exists(filePath)) {
                        throw new VduProcessor.VduException("File no longer exists", null);
                    }
                    result = vduProcessor.process(filePath, engineContext);
                } catch (VduProcessor.VduException | RuntimeException failure) {
                    EngineFutures.rethrowCancellation(failure);
                    checkInterrupted();
                    LOG.warn("VDU processing failed for {}", docId, failure);
                    circuitBreaker.recordFailure(failure);
                    // Control/write failures are outside the model catch: never recursively mark
                    // an unacknowledged write as a successful FAILED document.
                    try { markVduFailed(knowledgeClient, docId, failure.getMessage(), engineContext); }
                    catch (RuntimeException | Error writeFailure) {
                        if (writeFailure != failure) writeFailure.addSuppressed(failure);
                        throw writeFailure;
                    }
                    recordFailed();
                    failed++;
                    report(progress, selected, processed, failed, BlockReason.NONE);
                    continue;
                }
                checkInterrupted();
                circuitBreaker.recordSuccess();
                String extractedText = result.extractedText();
                boolean hasText = extractedText != null && !extractedText.isBlank();
                GateVerdict verdict = result.gateVerdict();
                io.justsearch.ipc.VduUpdateOutcome outcome;
                String enrichment;
                String content;
                if (verdict.rejected()) {
                    outcome = io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT;
                    content = null;
                    enrichment = buildGateRejectionEnrichment(verdict, result.pageCount());
                } else if (hasText) {
                    outcome = io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT;
                    content = extractedText;
                    enrichment = result.enrichment();
                } else {
                    outcome = io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY;
                    content = null;
                    enrichment = buildNoTextEnrichment(result.pageCount(), result.enrichment());
                }
                if (!knowledgeClient.updateVduResult(
                        docId, content, outcome, enrichment, result.pageCount(), engineContext)) {
                    throw new IllegalStateException("VDU result was not acknowledged by the index");
                }
                if (verdict.rejected()) { recordRejected(); failed++; }
                else if (hasText) { recordCompleted(); processed++; }
                else { recordEmpty(); failed++; }
                report(progress, selected, processed, failed, BlockReason.NONE);
            }
            return report(progress, selected, processed, failed, blocked);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            finishVduMode(primaryFailure);
        }
    }

    private void finishVduMode(Throwable primaryFailure) {
        try { vduProcessor.exitVduMode(); }
        catch (RuntimeException | Error cleanup) {
            if (primaryFailure == null) throw cleanup;
            if (primaryFailure != cleanup) primaryFailure.addSuppressed(cleanup);
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("VDU pass interrupted");
    }

    private static OfflineProcessingOutcome report(Consumer<OfflineProcessingOutcome> progress,
            int selected, int processed, int failed, BlockReason blocked) {
        var outcome = new OfflineProcessingOutcome(
            selected, processed, failed, blocked, EmbeddingHandoff.NOT_EVALUATED);
        progress.accept(outcome);
        return outcome;
    }

    private void markVduFailed(KnowledgeClient knowledgeClient, String docId, String reason,
            EngineContext engineContext) {
        if (!knowledgeClient.updateVduResult(docId, null,
                io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED,
                buildErrorEnrichment(reason), 0, engineContext)) {
            throw new IllegalStateException("VDU failure was not acknowledged by the index");
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
