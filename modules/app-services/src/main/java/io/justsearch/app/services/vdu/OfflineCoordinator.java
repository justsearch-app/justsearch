/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.worker.KnowledgeClient;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates offline processing: VDU first (if needed), then embeddings.
 *
 * <p>Called when user goes idle or manually triggers "Process Now".
 * Ensures VDU runs with LLM before switching to SLM for embeddings.
 *
 * <p><b>Architecture:</b> Queries Worker (via gRPC) for pending work counts.
 * Does not access Lucene directly - Worker owns the index.
 *
 * <p><b>Tempdoc 737 (task 3):</b> this coordinator no longer drives inference modes directly.
 * The whole run is bracketed by {@link RuntimeReconciler#beginProcedure}/{@link
 * RuntimeReconciler#endProcedure}, and each engine-state need (Phase A "engine up", Phase B "park
 * to indexing") is a procedure-scoped request through {@link
 * RuntimeReconciler#procedureRequireEngine(boolean)}. When the procedure ends, the reconciler
 * returns the engine to spec — so the §3d never-switch-back bug is inexpressible: after Phase B
 * parks the engine, spec (not this class) decides whether it comes back online. The
 * {@link OnlineAiLifecycleControl} handle is retained for <b>read-only realized-state checks</b>
 * ({@code isOnline()}), never for transitions (R4).
 */
public class OfflineCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(OfflineCoordinator.class);

    // Tempdoc 518 Appendix F W4.2 — role-typed interface; off the concrete ILM. Tempdoc 737 R4:
    // used ONLY for realized-state reads (isOnline()), never to drive transitions.
    private final OnlineAiLifecycleControl inferenceManager;
    // Tempdoc 737: the single-writer authority. Procedure-scoped engine control routes here. May be
    // null in minimal/test constructions that never exercise the engine phases.
    private final RuntimeReconciler reconciler;
    private final VduBatchProcessor vduBatchProcessor;
    // Tempdoc 672: live supplier, not a captured value — the Worker client is null at Head
    // bootstrap (async connect) and must be re-read at use-time, never frozen at construction.
    private final Supplier<KnowledgeClient> knowledgeClientSupplier;
    private final VduCapabilityState vduCapabilityState;
    private final AtomicBoolean processing = new AtomicBoolean(false);

    public OfflineCoordinator(OnlineAiLifecycleControl inferenceManager,
                              VduBatchProcessor vduBatchProcessor,
                              Supplier<KnowledgeClient> knowledgeClientSupplier) {
        this(inferenceManager, null, vduBatchProcessor, knowledgeClientSupplier, new VduCapabilityState());
    }

    public OfflineCoordinator(OnlineAiLifecycleControl inferenceManager,
                              VduBatchProcessor vduBatchProcessor,
                              Supplier<KnowledgeClient> knowledgeClientSupplier,
                              VduCapabilityState vduCapabilityState) {
        this(inferenceManager, null, vduBatchProcessor, knowledgeClientSupplier, vduCapabilityState);
    }

    public OfflineCoordinator(OnlineAiLifecycleControl inferenceManager,
                              RuntimeReconciler reconciler,
                              VduBatchProcessor vduBatchProcessor,
                              Supplier<KnowledgeClient> knowledgeClientSupplier,
                              VduCapabilityState vduCapabilityState) {
        this.inferenceManager = inferenceManager;
        this.reconciler = reconciler;
        this.vduBatchProcessor = vduBatchProcessor;
        this.knowledgeClientSupplier = knowledgeClientSupplier;
        this.vduCapabilityState =
            vduCapabilityState != null ? vduCapabilityState : new VduCapabilityState();
    }

    /**
     * Start offline processing. Sequences VDU then Embeddings.
     *
     * <p>Flow:
     * <ol>
     *   <li>Query pending VDU/embedding counts via gRPC</li>
     *   <li>If VDU pending: ensure LLM loaded, process VDU batch</li>
     *   <li>Switch to Indexing Mode (SLM) for embeddings</li>
     * </ol>
     *
     * <p>Thread-safe: only one processing run at a time.
     */
    public void startOfflineProcessing() {
        if (!processing.compareAndSet(false, true)) {
            LOG.info("Offline processing already in progress, skipping");
            return;
        }

        // Tempdoc 737 (task 3): the entire run is a reconciler procedure. Engine states held during
        // the run are the procedure's business; endProcedure returns the engine to spec.
        boolean procedureBegun = false;
        if (reconciler != null) {
            reconciler.beginProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH, "offline-processing");
            procedureBegun = true;
        }
        try {
            KnowledgeClient knowledgeClient = knowledgeClientSupplier.get();
            if (knowledgeClient == null) {
                LOG.info("Offline processing skipped: Worker not connected yet");
                return;
            }

            LOG.info("Starting offline processing");

            // Recover any documents stuck in PROCESSING state from previous crash
            int recovered = knowledgeClient.recoverVduProcessing();
            if (recovered > 0) {
                LOG.info("Recovered {} documents stuck in PROCESSING state", recovered);
            }

            int pendingVdu = knowledgeClient.countPendingVdu();
            int pendingEmbeddings = knowledgeClient.countPendingEmbeddings();

            LOG.info("Pending work: {} VDU files, {} embeddings", pendingVdu, pendingEmbeddings);

            // Phase A: VDU Processing (requires LLM in Online Mode)
            if (pendingVdu > 0) {
                LOG.info("Phase A: Processing {} pending VDU files", pendingVdu);
                processVduPhase();
            } else {
                vduCapabilityState.clearAll();
            }

            // Phase B: Embedding Processing (requires SLM in Indexing Mode)
            // Re-query count - VDU sets embedding_status to PENDING for re-embedding
            pendingEmbeddings = knowledgeClient.countPendingEmbeddings();
            if (pendingEmbeddings > 0) {
                LOG.info("Phase B: Parking engine to Indexing Mode for {} pending embeddings",
                    pendingEmbeddings);
                processEmbeddingPhase();
            } else {
                LOG.info("No pending embeddings, staying in current mode");
            }

            LOG.info("Offline processing complete");
        } finally {
            // endProcedure BEFORE clearing the processing flag: the reconciler returns the engine to
            // spec now, so chatEnabled=true → engine returns ONLINE even after Phase B parked it;
            // chatEnabled=false → it stays down. THIS is what kills §3d.
            if (procedureBegun) {
                reconciler.endProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH);
            }
            processing.set(false);
        }
    }

    private void processVduPhase() {
        // R4: gate on REALIZED state (mode==ONLINE), never spec. inferenceManager.isOnline() reads
        // the FSM phase, so a chat session or a prior procedure that already brought the engine up
        // is honored and we don't restart it.
        if (!inferenceManager.isOnline()) {
            if (reconciler == null) {
                LOG.warn("Skipping VDU phase: no reconciler to bring the engine up");
                vduCapabilityState.block(VduCapabilityState.REASON_AI_OFFLINE);
                return;
            }
            try {
                LOG.info("Requesting engine UP for VDU processing (procedure-scoped)");
                reconciler.procedureRequireEngine(true);
            } catch (ModeTransitionException e) {
                LOG.error("Failed to bring engine up for VDU", e);
                vduCapabilityState.block(VduCapabilityState.REASON_AI_OFFLINE);
                return;  // Skip VDU phase
            }
        }

        // R4: re-read REALIZED state after the request.
        if (inferenceManager.isOnline()) {
            vduCapabilityState.clear(VduCapabilityState.REASON_AI_OFFLINE);
            int processed = vduBatchProcessor.processPendingFiles();
            LOG.info("VDU phase complete: {} files processed", processed);
        } else {
            LOG.warn("Skipping VDU phase: LLM not available");
            vduCapabilityState.block(VduCapabilityState.REASON_AI_OFFLINE);
        }
    }

    private void processEmbeddingPhase() {
        if (reconciler == null) {
            LOG.warn("Skipping embedding phase: no reconciler to park the engine");
            return;
        }
        try {
            // Procedure-scoped park to Indexing Mode. Worker will automatically process pending
            // embeddings because isMainGpuActive() will return false.
            reconciler.procedureRequireEngine(false);
            LOG.info("Indexing Mode active, Worker will process embeddings");
        } catch (ModeTransitionException e) {
            LOG.error("Failed to park engine to Indexing Mode", e);
        }
    }

    /**
     * Check if there is any pending offline work.
     *
     * @return true if VDU or embedding work is pending
     */
    public boolean hasPendingWork() {
        KnowledgeClient knowledgeClient = knowledgeClientSupplier.get();
        if (knowledgeClient == null) {
            return false;
        }
        return knowledgeClient.countPendingVdu() > 0
            || knowledgeClient.countPendingEmbeddings() > 0;
    }

    /**
     * Get count of pending VDU files.
     */
    public int getPendingVduCount() {
        KnowledgeClient knowledgeClient = knowledgeClientSupplier.get();
        return knowledgeClient == null ? 0 : knowledgeClient.countPendingVdu();
    }

    /**
     * Get count of pending embeddings.
     */
    public int getPendingEmbeddingCount() {
        KnowledgeClient knowledgeClient = knowledgeClientSupplier.get();
        return knowledgeClient == null ? 0 : knowledgeClient.countPendingEmbeddings();
    }

    /**
     * Check if offline processing is currently running.
     */
    public boolean isProcessing() {
        return processing.get();
    }

    public VduCapabilityState vduCapabilityState() {
        return vduCapabilityState;
    }
}
