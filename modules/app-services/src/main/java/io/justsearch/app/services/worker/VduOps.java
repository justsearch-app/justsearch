/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.core.context.EngineContext;

import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.QueryPendingVduRequest;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VDU (Visual Document Understanding) RPC operations.
 *
 * <p>Handles pending counts, VDU result updates, processing marks, and recovery. All methods
 * delegate to the Worker via {@link IngestRpcExecutor}. Extracted from {@link
 * KnowledgeClient}.
 */
final class VduOps {
    private static final Logger log = LoggerFactory.getLogger(VduOps.class);

    private final IngestRpcExecutor rpc;
    private final java.util.concurrent.ConcurrentMap<java.util.UUID, java.util.Set<String>> attempts =
        new java.util.concurrent.ConcurrentHashMap<>();

    VduOps(IngestRpcExecutor rpc) {
        this.rpc = Objects.requireNonNull(rpc, "rpc");
    }

    int countPendingEmbeddings(EngineContext engineContext) {
        return rpc.execute("countPendingEmbeddings", KnowledgeClient.RpcDeadlineCategory.STANDARD,
                IngestServiceCalls::countPendingEmbeddings, engineContext);
    }

    int countPendingVdu(EngineContext engineContext) {
        var request = QueryPendingVduRequest.newBuilder().setLimit(1).build();
        return rpc.execute("queryPendingVdu", KnowledgeClient.RpcDeadlineCategory.STANDARD,
                calls -> calls.queryPendingVdu(request).getTotalCount(), engineContext);
    }

    boolean updateVduResult(
            String docId,
            String extractedContent,
            VduUpdateOutcome outcome,
            String enrichment,
            int pageCount, EngineContext engineContext) {
        return updateVduResult(docId, extractedContent, outcome, enrichment, pageCount, "", engineContext);
    }

    boolean updateVduResult(String docId, String extractedContent, VduUpdateOutcome outcome,
            String enrichment, int pageCount, String attemptId, EngineContext engineContext) {
        var builder =
                UpdateVduResultRequest.newBuilder()
                        .setDocId(docId)
                        .setAttemptId(attemptId)
                        .setOutcome(outcome)
                        .setVduEnrichment(enrichment != null ? enrichment : "")
                        .setPageCount(pageCount);

        // Only set extracted_content when present (proto3 optional allows presence detection)
        if (extractedContent != null) {
            builder.setExtractedContent(extractedContent);
        }

        var request = builder.build();

        var response =
                rpc.execute(
                        "updateVduResult",
                        KnowledgeClient.RpcDeadlineCategory.VDU_OPERATION,
                        stub -> stub.updateVduResult(request), engineContext);

        if (!response.getSuccess()) {
            log.error("updateVduResult failed for {}: {}", docId, response.getError());
            return false;
        }

        forget(attemptId, engineContext);
        log.debug("updateVduResult success for: {} (outcome={})", docId, outcome);
        return true;

    }

    List<String> queryPendingVduDocIds(EngineContext engineContext) {
        return queryPendingVduDocIds(100, engineContext);
    }

    List<String> queryPendingVduDocIds(int limit, EngineContext engineContext) {
        var request = QueryPendingVduRequest.newBuilder().setLimit(limit).build();

        var response =
                rpc.execute(
                        "queryPendingVdu",
                        KnowledgeClient.RpcDeadlineCategory.STANDARD,
                        stub -> stub.queryPendingVdu(request), engineContext);

        log.debug(
                "queryPendingVduDocIds: returned {} of {} pending",
                response.getDocIdsCount(),
                response.getTotalCount());
        return response.getDocIdsList();
    }


    int markVduProcessing(String docId, int maxRetries, EngineContext engineContext) {
        return markVduProcessing(docId, maxRetries, java.util.UUID.randomUUID().toString(), engineContext);
    }

    int markVduProcessing(String docId, int maxRetries, String attemptId, EngineContext engineContext) {
        var request =
                MarkVduProcessingRequest.newBuilder()
                        .setDocId(docId)
                        .setMaxRetries(maxRetries)
                        .setAttemptId(attemptId)
                        .build();

        var response =
                rpc.execute(
                        "markVduProcessing",
                        KnowledgeClient.RpcDeadlineCategory.STANDARD,
                        stub -> stub.markVduProcessing(request), engineContext);

        if (!response.getSuccess()) {
            log.warn("markVduProcessing failed for {}: {}", docId, response.getError());
            return -1;
        }

        engineContext.workId().ifPresent(owner -> attempts.compute(owner, (ignored, ids) -> {
            if (ids == null) ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
            ids.add(attemptId);
            return ids;
        }));
        log.debug(
                "markVduProcessing success for {}: retry {}", docId, response.getRetryCount());
        return response.getRetryCount();

    }

    boolean releaseVduProcessing(String docId, EngineContext engineContext) {
        return releaseVduProcessing(docId, "", engineContext);
    }

    boolean releaseVduProcessing(String docId, String attemptId, EngineContext engineContext) {
        boolean released = rpc.execute("releaseVduProcessing",
                KnowledgeClient.RpcDeadlineCategory.VDU_OPERATION,
                calls -> calls.releaseVduProcessing(docId, attemptId), engineContext);
        forget(attemptId, engineContext);
        log.debug("releaseVduProcessing {}: released={}", docId, released);
        return released;
    }

    RecoverVduProcessingRequest recoveryRequest(EngineContext context) {
        var owner = context.workId().orElseThrow();
        return RecoverVduProcessingRequest.newBuilder().setProcedureOwner(owner.toString())
            .addAllAttemptIds(attempts.getOrDefault(owner, java.util.Set.of())).build();
    }

    void retireRecoveryRequest(RecoverVduProcessingRequest request, EngineContext context) {
        request.getAttemptIdsList().forEach(id -> forget(id, context));
    }

    private void forget(String attemptId, EngineContext context) {
        context.workId().ifPresent(owner -> attempts.computeIfPresent(owner, (key, ids) -> {
            ids.remove(attemptId);
            return ids.isEmpty() ? null : ids;
        }));
    }

    int recoverVduProcessingForOwner(EngineContext engineContext) {
        return recoverVduProcessing(engineContext, recoveryRequest(engineContext));
    }

    int recoverVduProcessing(EngineContext engineContext) {
        return recoverVduProcessing(engineContext, RecoverVduProcessingRequest.getDefaultInstance());
    }

    private int recoverVduProcessing(EngineContext engineContext, RecoverVduProcessingRequest request) {
        var response = rpc.execute("recoverVduProcessing",
                KnowledgeClient.RpcDeadlineCategory.VDU_OPERATION,
                calls -> calls.recoverVduProcessing(request), engineContext);
        int recovered = response.getRecoveredCount();
        retireRecoveryRequest(request, engineContext);
        log.debug("recoverVduProcessing: recovered {} stuck documents", recovered);
        return recovered;
    }
}
